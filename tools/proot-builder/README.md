# proot-builder

Builds the static native binaries the APK ships in `jniLibs/<arch>/`
(`proot`, `aria2c`, `tar`, `gzip`, `xz`, `rsync`, `nano`, `less`) from
termux-packages, via **`build_static.sh`**. CI workflow
`.github/workflows/bins-1-build.yml` runs the build and then gates the
release with **`smoke-native-binaries.sh`** (in this directory).

## Outputs
- `jniLibs/<arch>/lib*.so` — the binaries (renamed `lib*.so` for APK packaging) + proot loaders.
- `ninja_manifest.json` — per-binary sha256 (integrity).
- `cacert.pem` — Mozilla CA bundle (fetched from curl.se) for aria2c TLS; the app
  also ships it as an asset and passes `--check-certificate=true --ca-certificate=<cacert>`.

## Pinned sources
`termux-pins.env` pins the termux-packages commit and the builder image (by digest).
`make-manifest.sh` writes both in `ninja_manifest.json`. Without the pin, each build took
the newest termux-packages, and an upstream change came in with no review: rsync IDN
(K2GO-414), and NDK r30 on 2026-09-23 (the abort below). Move a pin in its own commit,
and test the new bundle on a phone with Linux older than 4.14 and on a new kernel.

## Old kernels: the NDK r30 arc4random
A static binary carries the libc.a of the NDK, not the libc of the phone. The NDK r30
`arc4random` aborts when `madvise(MADV_WIPEONFORK)` fails (Linux older than 4.14), and
every static program calls it before `main()`: all static binaries stop at start (exit
134) on those phones. `ndk-static-arc4random.sh` runs in the builder container before
the first build and puts `static-arc4random.c` in libc.a in place of the two arc4random
members. The smoke test fails a bundle that still has the abort text.

## Static-linking policy
Goal: binaries are statically linked so they don't depend on system libraries.

- **Required static:** `proot`, `tar`, `gzip`, `rsync`, `nano`, `less` — fully
  static; run on any matching-arch Linux and are testable off-device.
- **xz:** links `liblzma` via **libtool**, which swallows a plain `-static`
  (statically links only libtool libs, not libc). It needs **`-all-static` at
  link time only** (a `termux_step_make`). It must NOT be in configure-time
  `LDFLAGS` — configure's raw-clang test rejects `-all-static`
  ("C compiler cannot create executables").
- **aria2c — intentionally DYNAMIC.** `-all-static` makes it static, but a static
  aria2c **cannot resolve DNS off Android**: bionic's resolver needs Android's
  `netd`/`dnsproxyd`, and `--async-dns` is not built (`--without-libcares`). It
  works correctly dynamic on-device, where bionic is always present, and
  networking is its whole job — so we keep it dynamic. Its `cacert.pem` ships in
  the bundle for TLS.

> libtool rule of thumb: a fully static executable needs `-all-static` (a
> libtool-only flag), applied **at link**, never in configure-time `LDFLAGS`.

## Verification — `smoke-native-binaries.sh`
```
smoke-native-binaries.sh --jnilibs <dir-with-jniLibs> --arch arm64-v8a|armeabi-v7a
```
Runs the contract / "answer sheet":
- **static-linkage** (readelf, cross-arch, no execution) for the required set — a
  required binary that is dynamic FAILS the gate; `aria2c` is reported but allowed dynamic;
- **behavioral** round-trips (tar / gzip / xz / rsync), aria2c feature check, and a
  real **proot** launch (probed with the bundle's static `libtar`, so no busybox needed);
- **integrity** (sha256 vs `ninja_manifest.json`).

Run it on a **matching-arch host** for a faithful proot launch (native arm64 → the
real launch; foreign arch under QEMU → proot is version-only). A dynamic binary
(e.g. aria2c) can't execute on a plain runner, so its behavioral rows become
integrity-only; **aria2c's real download (TLS+DNS) is validated on-device or in an
Android-emulator job**, never on a plain runner.

## Retention and archive: `binaries-lifecycle.sh`
```
binaries-lifecycle.sh sync|prune      # DRY_RUN=1 reports only
```
`sync` archives every `binaries-*` release to R2 (`binaries/<tag>/`, never deleted).
`prune` deletes old GitHub releases but keeps the newest 6, every tag that a branch or a `v*`
tag pins, and every release not archived yet. `test-binaries-lifecycle.sh` checks these rules
with fake `gh`/`aws` (no network). The workflow `bins-2-archive.yml` runs the test, then
the script; see `controller/docs/UPDATING_NATIVE_BINARIES.md`.

## Tickets
Epic **ADFA-1028** · gate **ADFA-4465** · xz static fix **ADFA-4468** ·
version-adoption umbrella **ADFA-4464**.
Retention and R2 archive: **K2GO-506**.
