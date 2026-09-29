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

(ns soundjutsu.core
  "CLI entrypoint.

   jolt run -m soundjutsu.core <command> [args]

     gui                             open the soundboard window
     scenes                          list scenes (* = active)
     scene <name|id>                 switch the active scene
     add-scene <folder>              add a scene for a folder of sounds
     list                            list the active scene's sounds
     config                          show config path + summary
     set-hotkey <sound> <hotkey>     bind a hotkey in the active scene
     set <key> <value>               change a setting (monitor-device,
                                     monitor-volume, stop-hotkey)
     play <sound>                    play a sound from the active scene
     play-file <path> [devices...]   play an arbitrary file
     --status                        list playback devices"
  (:require [soundjutsu.config :as config]
            [soundjutsu.audio.devices :as devices]
            [soundjutsu.audio.engine :as engine]
            [soundjutsu.audio.routing :as routing]
            [soundjutsu.hotkeys :as hotkeys]
            [soundjutsu.ui :as ui]
            [soundjutsu.ffi.webview :as webview]
            [soundjutsu.state :as state]
            [clojure.string :as str]))

(defn- wait-and-stop []
  (println "press Enter to stop and quit…")
  (read-line)
  (engine/shutdown!))

(defn- print-devices []
  (println "playback devices:")
  (doseq [{:keys [index name default?]} (devices/list-devices)]
    (println (format "  [%d] %s%s" index name (if default? "  (default)" "")))))

(defn- cmd-scenes []
  (let [active (:active-scene (state/settings))]
    (if (empty? (state/scenes))
      (println "no scenes — add one with: add-scene <folder>")
      (doseq [s (state/scenes)]
        (println (format "%s %-16s %s  (%d sounds)"
                         (if (= (:id s) active) "*" " ")
                         (:name s) (:folder s)
                         (count (state/sounds (:id s)))))))))

(defn- cmd-config []
  (println "config:" (config/config-path))
  (println "settings:")
  (doseq [[k v] (state/settings)]
    (println (format "  %-16s %s" (name k) (pr-str v))))
  (println (format "scenes: %d" (count (state/scenes))))
  (cmd-scenes))

(defn- cmd-list []
  (if-let [sc (state/active-scene)]
    (do (println (format "# %s — %s" (:name sc) (:folder sc)))
        (let [ss (vec (state/sounds))]
          (if (empty? ss)
            (println "  (no audio files)")
            (doseq [[i s] (map-indexed vector ss)]
              (println (format "  [%d] %-28s %s" i (:name s)
                               (if (:hotkey s) (str "hotkey=" (:hotkey s)) "")))))))
    (println "no active scene — add one with: add-scene <folder>")))

(defn- parse-setting-val [k v]
  (cond
    (= v "nil")                         nil
    (str/ends-with? (name k) "-volume") (parse-double v)
    :else                               v))

(defn- active-id [] (:id (state/active-scene)))

(defn- cmd-play [sound-ref]
  (if-let [s (state/find-sound (active-id) sound-ref)]
    (do (print-devices)
        (routing/play! (:abs-path s) (:volume s 1.0))
        (println "\nplaying" (:name s))
        (wait-and-stop))
    (println "no such sound in the active scene:" sound-ref)))

(defn- resolve-output [tok]
  (if (re-matches #"\d+" tok)
    (parse-long tok)
    (or (devices/find-index tok)
        (do (println "no device matches" (pr-str tok)) nil))))

(defn- cmd-play-file [path outs]
  (let [idxs (or (seq (keep resolve-output outs)) [-1])]
    (print-devices)
    (engine/set-outputs! idxs)
    (println "\nplaying" path "on outputs" (vec idxs))
    (engine/play! path)
    (wait-and-stop)))

(defn- cmd-gui []
  (print-devices)
  (routing/apply!)
  (try
    (let [{:keys [bound]} (hotkeys/start!)]
      (println (count bound) "hotkey(s) active"))
    (catch Exception _ (println "(global hotkeys unavailable)")))
  (ui/start!)
  (println "\nGUI running — close the window to quit.")
  (webview/run))                           ; blocks the main thread

(defn -main [& args]
  (devices/refresh!)
  (state/init!)
  (let [[cmd & more] args]
    (case cmd
      nil          (println (:doc (meta #'-main)))
      "help"       (println (:doc (meta #'-main)))
      "--status"   (print-devices)
      "config"     (cmd-config)
      "scenes"     (cmd-scenes)
      "list"       (cmd-list)
      "scene"      (do (state/activate! (first more)) (cmd-scenes))
      "add-scene"  (let [[folder] more]
                     (state/add-scene! folder)
                     (state/activate! (:id (last (state/scenes))))
                     (cmd-scenes))
      "set-hotkey" (let [[s hk] more]
                     (if-let [snd (state/find-sound (active-id) s)]
                       (do (state/set-sound-hotkey! (active-id) (:id snd) hk)
                           (println "bound" hk "->" (:name snd)))
                       (println "no such sound in the active scene:" s)))
      "set"        (let [[k v] more
                         kw (keyword k)]
                     (state/set-setting! kw (parse-setting-val kw v))
                     (println "set" k "=" (pr-str (get (state/settings) kw))))
      "play"       (cmd-play (first more))
      "gui"        (cmd-gui)
      "play-file"  (cmd-play-file (first more) (rest more))
      (println "unknown command:" cmd))))
