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

(ns soundjutsu.audio.engine
  "Control plane over the native miniaudio engines — one per active output
   device, so a sound plays on all of them at once."
  (:require [soundjutsu.ffi.miniaudio :as ma]))

;; {device-index engine-ptr}. device-index -1 means the system default device.
(defonce ^:private engines (atom {}))

(defn- open-engine [idx]
  (let [e (ma/engine-open idx)]
    (when (nil? e)
      (throw (ex-info "failed to open audio engine" {:device idx})))
    e))

(defn set-outputs!
  "Make `idxs` the active output devices (default: [-1], the system default).
   Opens engines that are missing, closes ones no longer wanted."
  ([] (set-outputs! [-1]))
  ([idxs]
   (let [want (set idxs)]
     (swap! engines
            (fn [m]
              (doseq [[idx e] m :when (not (want idx))]
                (ma/engine-close e))
              (reduce (fn [acc idx]
                        (assoc acc idx (or (get m idx) (open-engine idx))))
                      {} want))))
     (keys @engines)))

;; key -> {device-index voice-id}, so a play can be stopped or tracked as a unit.
(defonce ^:private plays (atom {}))

(defn play!
  "Playback on every active output. Opens the default device if none are set
   yet. `volume` is a scalar or a {device-index volume} map. With a `key`, the
   resulting voices are registered so stop-key! / active-key? can act on them.
   Returns {device-index voice-id}."
  ([path] (play! path 1.0 nil))
  ([path volume] (play! path volume nil))
  ([path volume key]
   (when (empty? @engines) (set-outputs!))
   (let [voices (into {}
                      (for [[idx e] @engines]
                        (let [v (float (if (map? volume) (get volume idx 1.0) volume))
                              r (ma/play-file e path v)]
                          (when (neg? r)
                            (throw (ex-info "playback failed"
                                            {:path path :device idx :ma-result r})))
                          [idx r])))]
     (when key (swap! plays assoc key voices))
     voices)))

(defn stop-key!
  "Stop just the voices from the most recent play registered under `key`."
  [key]
  (doseq [[idx vid] (get @plays key)]
    (when-let [e (get @engines idx)] (ma/stop-voice e vid)))
  (swap! plays dissoc key))

(defn stop-all! []
  (doseq [[_ e] @engines] (ma/stop-all e))
  (reset! plays {}))

(defn active-keys
  "Set of registered keys that still have at least one voice playing.
   Prunes finished entries as a side effect."
  []
  (let [live (into {}
                   (for [[key voices] @plays
                         :let [on (some (fn [[idx vid]]
                                          (when-let [e (get @engines idx)]
                                            (pos? (ma/voice-active e vid))))
                                        voices)]
                         :when on]
                     [key voices]))]
    (reset! plays live)
    (set (keys live))))

(defn shutdown! []
  (doseq [[_ e] @engines] (ma/engine-close e))
  (reset! engines {}))
