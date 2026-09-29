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

(ns soundjutsu.state
  "In-memory app state: the loaded config plus runtime bits. Mutators persist
   config changes to disk immediately."
  (:require [soundjutsu.config :as config]))

(defonce state (atom {:config config/default-config}))

(defn init!
  "Load config from disk into the atom. Returns the config."
  []
  (:config (reset! state {:config (config/load-config)})))

(defn- cfg [] (:config @state))
(defn settings [] (:settings (cfg)))
(defn scenes [] (:scenes (cfg)))

(defn- scene-by
  "Scene by id or name, or nil."
  [scene-ref]
  (some #(when (or (= (:id %) scene-ref) (= (:name %) scene-ref)) %) (scenes)))

(defn active-scene
  "The active scene map, or nil when there are no scenes."
  []
  (scene-by (:active-scene (settings))))

(defn sounds
  "Resolved sound list for a scene (default: the active one)."
  ([] (some-> (active-scene) config/scene-sounds))
  ([scene-ref] (some-> (scene-by scene-ref) config/scene-sounds)))

(defn find-sound
  "Resolve a sound within a scene by id, name, or 0-based index."
  [scene-ref sound-ref]
  (let [ss (vec (sounds scene-ref))]
    (or (when (re-matches #"\d+" (str sound-ref))
          (get ss (parse-long (str sound-ref))))
        (some #(when (or (= (:id %) sound-ref) (= (:name %) sound-ref)) %) ss))))

(defn hotkey-map
  "Hotkeys to register right now: the *active* scene's bindings only, so the
   same keys can mean different sounds in each scene. The global stop hotkey is
   always included."
  []
  (let [stop (:stop-hotkey (settings))
        sc   (active-scene)
        per  (for [s (config/scene-sounds sc)
                   :when (:hotkey s)]
               [(:hotkey s) {:scene (:id sc) :sound (:id s)
                             :abs-path (:abs-path s) :volume (:volume s)}])]
    (cond-> (into {} per)
      (string? stop) (assoc stop {:action :stop}))))

;; ---- mutations -------------------------------------------------------------

(defn- update-config! [f]
  (let [c (config/save-config! (f (cfg)))]
    (swap! state assoc :config c)
    c))

(defn add-scene!        [folder]           (update-config! #(config/add-scene % folder)))
(defn set-scene-folder! [scene-ref folder] (update-config! #(config/set-scene-folder % scene-ref folder)))
(defn set-sound-hotkey! [scene-ref sref hk](update-config! #(config/set-sound-hotkey % scene-ref sref hk)))
(defn set-sound-volume! [scene-ref sref v] (update-config! #(config/set-sound-volume % scene-ref sref v)))
(defn set-setting!      [k v]              (update-config! #(config/set-setting % k v)))

(defn activate!
  "Make `scene-ref` the active scene (no-op if it doesn't exist)."
  [scene-ref]
  (when-let [sc (scene-by scene-ref)]
    (set-setting! :active-scene (:id sc))))

(defn remove-scene!
  "Delete a scene; if it was active, fall back to whatever is left."
  [scene-ref]
  (update-config! #(config/set-setting
                     (config/remove-scene % scene-ref)
                     :active-scene
                     (let [left (remove (fn [s] (or (= (:id s) scene-ref)
                                                    (= (:name s) scene-ref)))
                                        (:scenes %))
                           cur  (get-in % [:settings :active-scene])]
                       (if (some #(= (:id %) cur) left) cur (:id (first left)))))))
