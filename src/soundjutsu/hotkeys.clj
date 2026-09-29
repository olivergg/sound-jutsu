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

(ns soundjutsu.hotkeys
  "Bind config hotkeys to playback. C owns key capture + the Carbon run loop;
   here we assign ids, register specs, and drain the fired-id queue on a
   background thread."
  (:require [soundjutsu.ffi.hotkeys :as ffi]
            [soundjutsu.state :as state]
            [soundjutsu.audio.routing :as routing]
            [soundjutsu.audio.engine :as engine]))

(defonce ^:private draining (atom false))
(defonce ^:private bindings (atom {}))   ; id -> action map

(defn- dispatch [id]
  (when-let [action (get @bindings id)]
    (try
      (if (= :stop (:action action))
        (engine/stop-all!)
        (routing/play! (:abs-path action) (:volume action 1.0)
                       (str (:scene action) "/" (:sound action))))
      (catch Exception e
        (println "[hotkeys] play failed for id" id "->" (ex-message e))))))

(defn- drain-loop []
  (while @draining
    (let [id (ffi/poll)]
      (if (>= id 0)
        (dispatch id)
        (Thread/sleep 20)))))

(defn- start-drainer! []
  (when-not @draining
    (reset! draining true)
    (future (drain-loop))))

(defn- register-all!
  "(Re)register every hotkey in state/hotkey-map. Returns [[spec rc] ...]."
  []
  (ffi/unregister-all)
  (reset! bindings {})
  (doall
    (for [[id [spec action]] (map-indexed (fn [i x] [(inc i) x]) (state/hotkey-map))]
      (let [rc (ffi/register id spec)]
        (when (zero? rc) (swap! bindings assoc id action))
        [spec rc]))))

(defn- summarize [results]
  {:bound  (for [[s rc] results :when (zero? rc)] s)
   :failed (for [[s rc] results :when (not (zero? rc))] {:spec s :code rc})})

(defn start!
  "Register every hotkey in state/hotkey-map and start the background drainer.
   Returns {:bound [...] :failed [{:spec :code}]}. Throws if the platform has
   no hotkey support."
  []
  (let [rc (ffi/start)]
    (when (neg? rc)
      (throw (ex-info "global hotkeys unavailable on this platform" {:code rc}))))
  (let [r (register-all!)]
    (start-drainer!)
    (summarize r)))

(defn reload!
  "Re-read hotkeys from config and re-register (no run-loop change)."
  []
  (summarize (register-all!)))
