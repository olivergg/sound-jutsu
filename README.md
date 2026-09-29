# sound-jutsu 音

> **Experimental.** macOS only for now. The CLI and config format may still
> change, and there are no releases yet.

A soundboard in the spirit of [Soundux](https://github.com/Soundux/Soundux),
written in [Jolt](https://jolt-lang.net/) (Clojure on Chez Scheme, no JVM).
Audio goes through a small C shim over [miniaudio](https://miniaud.io/).

## Usage

```
jolt native   # fetch pinned miniaudio.h/webview.h (sha256-checked), build the shim
jolt run -m soundjutsu.core gui
jolt test     # unit tests
```

The window lists the sounds of the active scene. Click to play, filter by
name, set a volume per sound, stop one sound or all of them. Click a sound's
key badge and press a combo to bind a global hotkey; keys are captured by
position (`event.code`), so AZERTY works. Hotkeys stay active while the window
is open.

A scene is a folder of sounds. Only one is active at a time, and only its
hotkeys are registered, so `1`..`9` can mean different sounds per scene.
Scenes are added, re-pointed and deleted from the UI.

Other commands (a sound is an id, a name or an index in the active scene):

```
jolt run -m soundjutsu.core config | scenes | list
jolt run -m soundjutsu.core add-scene <folder>
jolt run -m soundjutsu.core scene <id|name>                 # activate
jolt run -m soundjutsu.core set-hotkey <sound> <hk>
jolt run -m soundjutsu.core set <key> <value>               # e.g. set monitor-volume 0.8
jolt run -m soundjutsu.core play <sound>
jolt run -m soundjutsu.core play-file <path> [devices...]
jolt run -m soundjutsu.core --status                        # list playback devices
```

The config is `$XDG_CONFIG_HOME/sound-jutsu/config.edn` (default
`~/.config/sound-jutsu/config.edn`). On first run, `~/Music/soundux` becomes a
scene if it exists. Old configs with `:tabs` are migrated to scenes.

## Packaging

```
jolt package  # -> sound-jutsu-app/
```

| Path | Content |
|---|---|
| `sound-jutsu-app/sound-jutsu` | launcher script |
| `sound-jutsu-app/libexec/sound-jutsu` | the binary (`jolt build --dynamic`, `resources/` embedded) |
| `sound-jutsu-app/libexec/libsj_audio.dylib` | the native shim |

The folder runs anywhere, without jolt or the source tree. Start it through
the launcher: it puts `libexec/` on the library path, so the binary never picks
up a `libsj_audio.dylib` from the current directory. The binary is unsigned;
on another Mac, run `xattr -d com.apple.quarantine` on it first.

## How it works

| Path | Role |
|---|---|
| `native/sj_audio.c` | miniaudio wrapper: one engine per output device, each play is a tracked voice that can be stopped |
| `native/sj_hotkeys.c` | global hotkeys with Carbon `RegisterEventHotKey` (macOS only); fired ids go through a ring buffer |
| `native/sj_webview.mm` | the window (`webview` library) and a queue of UI commands |
| `resources/ui.html` | the page, filled in with the sound list at load |
| `src/soundjutsu/ffi/` | Jolt bindings to the shim |
| `src/soundjutsu/ui.clj` | builds the page, handles UI commands |
| `src/soundjutsu/hotkeys.clj` | registers hotkeys, handles fired ones |
| `src/soundjutsu/config.clj` | config load/save, folder scan |
| `src/soundjutsu/state.clj` | in-memory config, saved on each change |
| `src/soundjutsu/audio/` | devices, per-device engines, routing |
| `src/soundjutsu/core.clj` | CLI entry point |

Jolt never gets called back from C. Hotkeys and UI commands are queued on the
C side and polled from Jolt threads. UI commands are JSON arrays of scalars,
which are also valid EDN, so Jolt reads them without a JSON parser.

## Roadmap

- Linux: GTK/WebKitGTK window and folder picker, X11 hotkeys. Wayland has no
  global key grab; bind the `play` command in the desktop's shortcuts instead.
- Homebrew tap.
- Single-file binary. Blocked: `jolt build` can't yet link the macOS
  frameworks the shim needs.
- Dock icon in packaged builds (`assets/icon.png` is still read from the
  current directory).

## License

Copyright (C) 2026 Olivier G. [GNU Affero General Public License, version 3
only](LICENSE), with an additional permission (AGPL §7) to combine it with
Jolt and Chez Scheme, whose EPL and Apache-licensed parts are embedded in
built binaries. See the header of any source file. Built binaries also ship
`THIRD_PARTY_NOTICES` and `licenses/`.
