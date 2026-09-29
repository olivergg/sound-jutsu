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

(ns soundjutsu.ui
  "WebView soundboard: loads the ui.html resource, injects data, drains UI commands."
  (:require [soundjutsu.ffi.webview :as wv]
            [soundjutsu.state :as state]
            [soundjutsu.hotkeys :as hotkeys]
            [soundjutsu.audio.routing :as routing]
            [soundjutsu.audio.engine :as engine]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defonce ^:private draining (atom false))

;; ---- page ------------------------------------------------------------

(defn- js-str
  "Double-quoted JS string literal. A JSON array of these is also valid EDN
   (commas are whitespace), so the UI -> Jolt command channel needs no parser."
  [s]
  (str \" (-> (str s)
              (str/replace "\\" "\\\\")
              (str/replace "\"" "\\\"")
              (str/replace "\n" "\\n")
              (str/replace "\r" "\\r")
              (str/replace "</" "<\\/"))
       \"))

(defn- sounds-json
  "Sounds of the active scene only."
  []
  (let [sid (:id (state/active-scene))]
    (str "["
         (->> (for [s (state/sounds)]
                (str "{\"scene\":" (js-str sid)
                     ",\"id\":" (js-str (:id s))
                     ",\"name\":" (js-str (:name s))
                     ",\"hotkey\":" (js-str (or (:hotkey s) ""))
                     ",\"vol\":" (double (:volume s 1.0))
                     ",\"fav\":" (boolean (:favorite s)) "}"))
              (str/join ","))
         "]")))

(defn- scenes-json []
  (let [active (:active-scene (state/settings))]
    (str "["
         (->> (state/scenes)
              (map #(str "{\"id\":" (js-str (:id %))
                         ",\"name\":" (js-str (:name %))
                         ",\"folder\":" (js-str (:folder %))
                         ",\"active\":" (= (:id %) active) "}"))
              (str/join ","))
         "]")))

(defn- page []
  (-> (slurp (io/resource "ui.html"))
      (str/replace "%%SOUNDS%%" (sounds-json))
      (str/replace "%%SCENES%%" (scenes-json))
      (str/replace "%%MONITOR_VOL%%" (str (:monitor-volume (state/settings))))))

(defn- push-data!
  "Re-send scenes + sounds to the page after a config change."
  []
  (wv/eval-js (str "sjSetData(" (sounds-json) "," (scenes-json) ")")))

;; ---- command drain -----------------------------------------------------

(defn- switched!
  "Everything a scene change implies: stop sound, rebind hotkeys, refresh page."
  []
  (engine/stop-all!)
  (hotkeys/reload!)
  (push-data!))

(defn- dispatch [[cmd & args]]
  (case cmd
    "play"       (let [[sc s] args]
                   (when-let [snd (state/find-sound sc s)]
                     (routing/play! (:abs-path snd) (:volume snd 1.0)
                                    (str sc "/" (:id snd)))))
    "stop"       (let [[sc s] args]
                   (if (and sc s)
                     (engine/stop-key! (str sc "/" (:id (state/find-sound sc s))))
                     (engine/stop-all!)))
    "vol"        (let [[leg v] args]
                   (state/set-setting! (keyword (str leg "-volume")) (double v)))
    "sndvol"     (let [[sc s v] args]
                   (state/set-sound-volume! sc s (double v)))
    "bind"       (let [[sc s spec] args]
                   (state/set-sound-hotkey! sc s spec)
                   (hotkeys/reload!))
    "scene"      (let [[sc] args]
                   (state/activate! sc)
                   (switched!))
    "add-scene"  (let [[folder] args]
                   (state/add-scene! folder)
                   (state/activate! (:id (last (state/scenes))))
                   (switched!))
    "set-folder" (let [[sc folder] args]
                   (state/set-scene-folder! sc folder)
                   (switched!))
    "del-scene"  (let [[sc] args]
                   (state/remove-scene! sc)
                   (switched!))
    (println "[ui] unknown command:" (pr-str cmd))))

(defn- drain-loop []
  (while @draining
    (let [s (wv/poll)]
      (if (str/blank? s)
        (Thread/sleep 30)
        ;; covers malformed/truncated messages too: nothing may kill the drain thread
        (try (dispatch (edn/read-string s))
             (catch Exception e
               (println "[ui] error handling" s "->" (ex-message e))))))))

(defn- keys->js [ks]
  (str "[" (str/join "," (map js-str ks)) "]"))

(defn- active-poll-loop []
  (loop [prev nil]
    (when @draining
      (let [ks (try (engine/active-keys) (catch Exception _ #{}))]
        (when (not= ks prev)
          (wv/eval-js (str "sjPlaying(" (keys->js ks) ")")))
        (Thread/sleep 150)
        (recur ks)))))

;; ---- lifecycle -------------------------------------------------------

(defn start!
  "Create the window and start draining UI commands. Does NOT block —
   the caller must call (wv/run) on the main thread."
  []
  (let [rc (wv/create "sound-jutsu" 940 640)]
    (when (neg? rc) (throw (ex-info "failed to create webview" {:code rc}))))
  (let [icon (io/file "assets/icon.png")]
    (wv/brand (if (.exists icon) (.getAbsolutePath icon) "") "sound-jutsu"))
  (wv/set-html (page))
  (reset! draining true)
  (future (drain-loop))
  (future (active-poll-loop)))

(defn stop! []
  (reset! draining false)
  (wv/stop))
