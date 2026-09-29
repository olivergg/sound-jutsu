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

(ns soundjutsu.ffi.miniaudio
  "Bindings to native/sj_audio.c (the miniaudio shim)."
  (:require [jolt.ffi :as ffi]))

;; :blocking on anything that talks to CoreAudio (device enum/open/close) —
;; without it a slow call pins the GC for every Jolt thread while it runs.
(ffi/defcfn init           "sj_init"              [] :int :blocking)
(ffi/defcfn device-count   "sj_device_count"      [] :int)
(ffi/defcfn device-name    "sj_device_name"       [:int] :string)
(ffi/defcfn device-default? "sj_device_is_default" [:int] :int)

;; engine* is an opaque pointer owned by C.
(ffi/defcfn engine-open  "sj_engine_open"  [:int] :pointer :blocking)
(ffi/defcfn engine-close "sj_engine_close" [:pointer] :void :blocking)

;; Not :blocking — ma_engine_play_sound hands decoding to the audio thread and
;; returns fast, and Chez forbids :string args on collect-safe foreign calls.
;; play-file returns a positive voice id, or a negative ma_result on failure.
(ffi/defcfn play-file     "sj_play_file"     [:pointer :string :float] :int)
(ffi/defcfn stop-all      "sj_stop_all"      [:pointer] :void)
(ffi/defcfn stop-voice    "sj_stop_voice"    [:pointer :uint32] :int)
(ffi/defcfn voice-active  "sj_voice_active"  [:pointer :uint32] :int)
