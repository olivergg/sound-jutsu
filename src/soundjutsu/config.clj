;; sound-jutsu — a soundboard with global hotkeys.
;; Copyright (C) 2026  Olivier G
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Affero General Public License, version 3,
;; as published by the Free Software Foundation.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; Additional permission under GNU AGPL version 3 section 7
;;
;; If you modify this Program, or any covered work, by linking or combining
;; it with Jolt (https://github.com/jolt-lang/jolt), including its runtime,
;; standard library and bundled libraries, and with Chez Scheme (or modified
;; versions of them), containing parts covered by the terms of the Eclipse
;; Public License version 1.0 or 2.0 or of the Apache License version 2.0,
;; the licensors of this Program grant you additional permission to convey
;; the resulting work.  Corresponding Source for a non-source form of such a
;; combination shall include the source code for the parts of Jolt and Chez
;; Scheme used as well as that of the covered work.

(ns soundjutsu.config
  "Load/save the EDN config and resolve a scene's folder into a sound list.

   A scene is one folder on disk. Exactly one is active at a time; only its
   sounds are shown and only its hotkeys are registered."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(def default-config
  {:version  2
   :settings {:monitor-device "default"   ; name substring, or "default"
              :monitor-volume 1.0
              :stop-hotkey    nil
              :active-scene   nil}
   :scenes   []})                          ; {:id :name :folder :sounds}

(defn config-path ^String []
  (let [base (or (System/getenv "XDG_CONFIG_HOME")
                 (str (System/getProperty "user.home") "/.config"))]
    (str base "/sound-jutsu/config.edn")))

;; ---- folder scan / sound resolution ----------------------------------------

(def audio-exts #{"mp3" "wav" "flac" "ogg" "opus" "aiff" "aif" "m4a"})

(defn- file-name [path] (str/replace path #"^.*/" ""))

(defn- ext [name]
  (let [i (str/last-index-of name ".")]
    (when i (str/lower-case (subs name (inc i))))))

(defn- audio-file? [f]
  (and (.isFile f) (contains? audio-exts (ext (.getName f)))))

(defn- scan-folder
  "Sorted vector of absolute audio-file paths directly under `dir` (not recursive).
   Empty for nil or a folder that no longer exists — no scenes is a valid state."
  [dir]
  (let [d (some-> dir io/file)]
    (if (and d (.isDirectory d))
      (->> (.listFiles d) (filter audio-file?)
           (map #(.getAbsolutePath %)) sort vec)
      [])))

(defn- slug [s]
  (-> (str/lower-case s) (str/replace #"[^a-z0-9]+" "-") (str/replace #"(^-|-$)" "")))

(defn- base-name
  "File stem: strip any directory and the extension, trim whitespace."
  [path]
  (-> path file-name (str/replace #"\.[^.]+$" "") str/trim))

(defn- folder-name [folder]
  (-> (str folder) (str/replace #"/+$" "") file-name))

(defn- abs-path [folder path]
  (if (or (str/starts-with? path "/") (nil? folder))
    path
    (.getAbsolutePath (io/file folder path))))

(defn scene-sounds
  "Explicit `:sounds` overrides merged with files discovered under `:folder`.
   Each result: {:id :name :abs-path :hotkey :volume :favorite}.
   Overrides win and are matched to folder files by absolute path."
  [{:keys [folder sounds]}]
  (let [explicit (for [s sounds]
                   (let [ap   (abs-path folder (:path s))
                         stem (base-name (:path s))]
                     [ap {:id       (or (:id s) (slug stem))
                          :name     (or (:name s) stem)
                          :abs-path ap
                          :hotkey   (:hotkey s)
                          :volume   (or (:volume s) 1.0)
                          :favorite (boolean (:favorite s))}]))
        by-path  (into {} explicit)
        scanned  (for [ap (scan-folder folder)
                       :when (not (by-path ap))]
                   (let [stem (base-name ap)]
                     {:id (slug stem) :name stem
                      :abs-path ap :hotkey nil :volume 1.0 :favorite false}))
        ;; drop duplicate stems (e.g. foo.mp3 + foo.ogg) — first scan wins
        taken    (set (map (comp :id second) explicit))]
    (->> (concat (map second explicit)
                 (->> scanned
                      (reduce (fn [[acc seen] s]
                                (if (seen (:id s))
                                  [acc seen]
                                  [(conj acc s) (conj seen (:id s))]))
                              [[] taken])
                      first))
         vec)))

;; ---- load / save -----------------------------------------------------------

(defn- seed-scenes
  "First-run convenience: pick up an existing ~/Music/soundux folder."
  []
  (let [d (io/file (str (System/getProperty "user.home") "/Music/soundux"))]
    (if (.isDirectory d)
      [{:id "soundux" :name "Soundux" :folder (.getAbsolutePath d) :sounds []}]
      [])))

(defn- migrate
  "v1 :tabs -> v2 :scenes, dropping settings from the removed virtual-mic routing."
  [c]
  (if (and (:tabs c) (not (:scenes c)))
    (-> c
        (assoc :scenes (:tabs c) :version 2)
        (dissoc :tabs)
        (assoc-in [:settings :active-scene] (:id (first (:tabs c))))
        (update :settings dissoc :virtual-device :virtual-volume))
    c))

(defn- ensure-active
  "Point :active-scene at a scene that exists (first one, or nil if none)."
  [c]
  (let [ids (set (map :id (:scenes c)))]
    (cond-> c
      (not (ids (get-in c [:settings :active-scene])))
      (assoc-in [:settings :active-scene] (:id (first (:scenes c)))))))

(defn load-config
  "Config merged over defaults, migrated from v1 if needed."
  []
  (let [f (io/file (config-path))]
    (ensure-active
      (if (.exists f)
        (-> (merge default-config (migrate (edn/read-string (slurp f))))
            (update :settings #(merge (:settings default-config) %)))
        (assoc default-config :scenes (seed-scenes))))))

(defn save-config! [cfg]
  (let [f (io/file (config-path))]
    (.mkdirs (.getParentFile f))
    (spit f (with-out-str (pp/pprint cfg)))
    cfg))

;; ---- mutation helpers (pure; caller persists) ------------------------------

(defn add-scene
  "Append a scene for `folder`; its name is the folder's basename."
  [cfg folder]
  (let [nm (folder-name folder)]
    (update cfg :scenes (fnil conj [])
            {:id (slug nm) :name nm :folder folder :sounds []})))

(defn remove-scene [cfg scene-ref]
  (update cfg :scenes
          #(vec (remove (fn [s] (or (= (:id s) scene-ref) (= (:name s) scene-ref))) %))))

(defn- update-scene [cfg scene-ref f]
  (update cfg :scenes
          (fn [scenes]
            (mapv (fn [s]
                    (if (or (= (:id s) scene-ref) (= (:name s) scene-ref))
                      (f s) s))
                  scenes))))

(defn set-scene-folder
  "Re-point a scene at `folder`, keeping only the overrides whose filename also
   exists there — so moving a sound folder preserves its hotkeys, while pointing
   at an unrelated folder starts clean. :id stays put so :active-scene and
   in-flight playback keys keep resolving."
  [cfg scene-ref folder]
  (let [keep? (set (map file-name (scan-folder folder)))]
    (update-scene cfg scene-ref
                  #(assoc % :folder folder
                            :name   (folder-name folder)
                            :sounds (filterv (comp keep? file-name :path) (:sounds %))))))

(defn- update-sound
  "Merge `attrs` onto the sound identified (id/name/abs-path) within `scene-ref`.
   Works for scanned sounds too; one gains an override entry keyed by its path
   (relative to the scene folder when possible)."
  [cfg scene-ref sound-ref attrs]
  (update-scene cfg scene-ref
    (fn [sc]
      (let [folder (:folder sc)
            hit    (some #(when (or (= (:id %) sound-ref)
                                    (= (:name %) sound-ref)
                                    (= (:abs-path %) sound-ref)) %)
                         (scene-sounds sc))]
        (if-not hit
          sc
          (let [ap     (:abs-path hit)
                rel    (if (and folder (str/starts-with? ap (str folder "/")))
                         (subs ap (inc (count folder))) ap)
                sounds (vec (:sounds sc))
                match? #(= (abs-path folder (:path %)) ap)]
            (assoc sc :sounds
                   (if (some match? sounds)
                     (mapv #(if (match? %) (merge % attrs) %) sounds)
                     (conj sounds (merge {:path rel} attrs))))))))))

(defn set-sound-hotkey [cfg scene-ref sound-ref hotkey]
  (update-sound cfg scene-ref sound-ref {:hotkey hotkey}))

(defn set-sound-volume [cfg scene-ref sound-ref volume]
  (update-sound cfg scene-ref sound-ref {:volume volume}))

(defn set-setting [cfg k v]
  (assoc-in cfg [:settings k] v))
