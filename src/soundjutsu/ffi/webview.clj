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

(ns soundjutsu.ffi.webview
  "Bindings to native/sj_webview.mm."
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn create   "sj_ui_create"   [:string :int :int] :int)
(ffi/defcfn brand    "sj_ui_brand"    [:string :string] :void)
(ffi/defcfn set-html  "sj_ui_set_html" [:string] :void)
(ffi/defcfn eval-js   "sj_ui_eval"     [:string] :void)
(ffi/defcfn poll      "sj_ui_poll"     [] :string)
(ffi/defcfn run       "sj_ui_run"      [] :void :blocking)
(ffi/defcfn stop      "sj_ui_stop"     [] :void)
