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

(ns soundjutsu.audio.routing
  "Turn config :settings into a concrete engine output + volume."
  (:require [soundjutsu.audio.devices :as devices]
            [soundjutsu.audio.engine :as engine]
            [soundjutsu.state :as state]
            [clojure.java.io :as io]))

(defn monitor-index []
  (let [d (:monitor-device (state/settings))]
    (if (or (nil? d) (= d "default")) -1 (or (devices/find-index d) -1))))

(defn apply!
  "Set the engine output from current settings. Returns {:monitor idx}."
  []
  (let [m (monitor-index)]
    (engine/set-outputs! [m])
    {:monitor m}))

(defn play!
  "Route then play `abs-path`, honoring the monitor volume and an optional
   per-sound volume multiplier. A `key` registers the voice for stop-key!."
  ([abs-path] (play! abs-path 1.0 nil))
  ([abs-path sound-vol] (play! abs-path sound-vol nil))
  ([abs-path sound-vol key]
   (when-not (.exists (io/file abs-path))
     (throw (ex-info "sound file not found" {:path abs-path})))
   (let [{:keys [monitor] :as r} (apply!)
         {:keys [monitor-volume]} (state/settings)]
     (engine/play! abs-path (* sound-vol (or monitor-volume 1.0)) key)
     r)))
