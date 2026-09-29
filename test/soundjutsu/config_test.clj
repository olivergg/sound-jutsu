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

(ns soundjutsu.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [soundjutsu.config :as config]))

(defn- with-folder
  "Call f with a temp folder holding empty files named `names`."
  [names f]
  (let [d (fs/create-temp-dir)]
    (try (doseq [n names] (fs/create-file (fs/path d n)))
         (f (str d))
         (finally (fs/delete-tree d)))))

(deftest scene-sounds-merges-overrides-and-drops-duplicate-stems
  (with-folder ["a.mp3" "b.mp3" "b.ogg" "notes.txt"]
    (fn [dir]
      ;; when
      (let [ss (config/scene-sounds {:folder dir :sounds [{:path "a.mp3" :hotkey "1"}]})]
        ;; then: override kept, b.ogg shadowed by b.mp3, non-audio ignored
        (is (= ["a" "b"] (map :id ss)))
        (is (= "1" (:hotkey (first ss))))
        (is (= (str dir "/b.mp3") (:abs-path (second ss))))))))

(deftest set-scene-folder-keeps-only-overrides-found-in-new-folder
  (with-folder ["a.mp3"]
    (fn [dir]
      ;; given
      (let [cfg {:scenes [{:id "s" :name "old" :folder "/old"
                           :sounds [{:path "a.mp3" :hotkey "1"} {:path "gone.mp3" :hotkey "2"}]}]}
            ;; when
            sc  (first (:scenes (config/set-scene-folder cfg "s" dir)))]
        ;; then: id stays so :active-scene keeps resolving
        (is (= {:id "s" :name (fs/file-name dir) :folder dir
                :sounds [{:path "a.mp3" :hotkey "1"}]}
               sc))))))

(deftest set-sound-hotkey-adds-then-updates-one-relative-override
  (with-folder ["a.mp3"]
    (fn [dir]
      ;; given: a scanned sound with no override yet
      (let [cfg {:scenes [{:id "s" :folder dir :sounds []}]}
            ;; when: bound twice
            cfg (-> cfg
                    (config/set-sound-hotkey "s" "a" "1")
                    (config/set-sound-hotkey "s" "a" "2"))]
        ;; then
        (is (= [{:path "a.mp3" :hotkey "2"}] (-> cfg :scenes first :sounds)))))))

(deftest migrate-v1-tabs-to-scenes
  ;; when
  (let [c (#'config/migrate {:tabs     [{:id "t1"} {:id "t2"}]
                             :settings {:monitor-volume 0.5 :virtual-device "x" :virtual-volume 1.0}})]
    ;; then
    (is (= {:version  2
            :scenes   [{:id "t1"} {:id "t2"}]
            :settings {:monitor-volume 0.5 :active-scene "t1"}}
           c)))
  (testing "stale :tabs never overwrite existing (even empty) scenes"
    (let [v2 {:version 2 :scenes [] :tabs [{:id "stale"}]}]
      (is (= v2 (#'config/migrate v2))))))
