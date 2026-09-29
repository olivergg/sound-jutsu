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

(ns soundjutsu.ui-test
  "Needs the shim built (`jolt native`): ui loads the webview bindings."
  (:require [clojure.test :refer [deftest is]]
            [soundjutsu.ui :as ui]
            [soundjutsu.state :as state]
            [soundjutsu.ffi.webview :as wv]))

(deftest js-str-escapes-everything-that-breaks-a-js-literal
  (is (= "\"q\\\" b\\\\ n\\n r\\r <\\/script>\""
         (#'ui/js-str "q\" b\\ n\n r\r </script>"))))

(deftest drain-loop-survives-bad-messages
  ;; given: unparseable, non-vector and throwing messages, then a valid one
  (let [queue (atom ["[\"play\"" "42" "[\"vol\",\"monitor\",\"abc\"]" "[\"vol\",\"monitor\",0.5]"])
        seen  (promise)]
    (with-redefs [wv/poll            #(let [s (or (first @queue) "")] (swap! queue rest) s)
                  state/set-setting! (fn [k v] (deliver seen [k v]))]
      ;; when
      (reset! @#'ui/draining true)
      (let [drainer (future (#'ui/drain-loop))]
        ;; then: the loop is still alive and handles the valid command
        (is (= [:monitor-volume 0.5] (deref seen 2000 :timeout)))
        (reset! @#'ui/draining false)
        @drainer))))
