# TODOs: packaging and Homebrew

`jolt package` already builds a standalone `sound-jutsu-app/` (launcher +
`libexec/{binary, shim}`), tested in a sandbox without jolt, the source tree
or Homebrew.

## Packaging

- [ ] Dock icon: `assets/icon.png` is read from the current directory
      (`ui.clj`, `start!`). Embed it and write it to a temp file, or make
      `sj_ui_brand` take bytes (`NSImage initWithData:`).
- [ ] Run directly (without the launcher), the binary can load a
      `libsj_audio.dylib` from the current directory, because macOS searches
      it for bare library names. Ask jolt-lang/jolt to resolve relative
      `:jolt/native` paths against the executable in built binaries (jolt
      already does this on Windows).
- [ ] Single-file binary: `:jolt/native :static` can't pass `-framework …` or
      `-lc++`. Same upstream request.
- [ ] `jolt smoke` task for the sandbox check: copy the package to a temp dir,
      run it under `sandbox-exec` with the project, `~/.jolt` and
      `/opt/homebrew` unreadable and a fake dylib in the current directory,
      then `--status`.
- [ ] The launcher uses `readlink -f`, which needs macOS 12.3+. Document it or
      add a fallback.
- [ ] Release artifact: a tar.gz of `sound-jutsu-app/` per arch (arm64,
      x86_64) on a GitHub release.
- [ ] Signing and notarization (`jolt build --signable`, Developer ID,
      `notarytool`). Needed for a cask, and to share without `xattr`.
- [ ] CI on GitHub Actions (macOS): `jolt test`, `jolt package`, `jolt smoke`.
- [x] License: AGPL-3.0-only with a §7 permission for Jolt/Chez. `package`
      ships `LICENSE`, `THIRD_PARTY_NOTICES` and `licenses/`.
- [ ] AGPL §6: each binary release must offer the matching source (link the
      tag's source archive in the release notes). Update the Jolt/Chez
      versions in `THIRD_PARTY_NOTICES` when jolt is upgraded.

## Homebrew

Own tap: homebrew-core is out of reach while jolt isn't in it.

- [ ] Public GitHub repo and a first tag (`v0.1.0`).
- [ ] Tap repo `homebrew-sound-jutsu` with `Formula/sound-jutsu.rb`:
  - `depends_on :macos` until the Linux port, and
    `depends_on "jolt-lang/jolt/jolt" => :build`.
  - `resource "miniaudio"` pinned to 0.11.25 with its sha256, staged into
    `native/` before `jolt native`. Brew builds without network, so `vendor`
    only checks the checksum. `webview.h` is already in git.
  - Build with `-Sdeps` pointing `:jolt/native` at the absolute path
    `#{libexec}/libsj_audio.dylib`. jolt uses absolute paths as given, so no
    launcher is needed. Check this on a built binary first.
  - Install the binary and dylib into `libexec`, symlink the binary into `bin`.
  - `test do` must not need an audio device (CI runners may have none), e.g.
    `config` with `XDG_CONFIG_HOME=testpath`.
- [ ] Check that `jolt build` works with brew's sandboxed `HOME` (jolt caches
      in `~/.jolt`).
- [ ] `brew install --build-from-source`, `brew test`,
      `brew audit --strict --online`.
- [ ] Install instructions in the README
      (`brew install <user>/sound-jutsu/sound-jutsu`).
- [ ] Later: a cask with the notarized artifact; a Linux formula after the
      GTK/X11 port.
