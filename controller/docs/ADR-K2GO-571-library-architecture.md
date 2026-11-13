# ADR-K2GO-571 : The library architecture comes from the installed library

- Status: Accepted
- Date: 2026-11-13
- Ticket: K2GO-571 (parent K2GO-3; relates to K2GO-565)
- Author: AppDevForAll

## Decision in one line

The architecture of the library (the rootfs) is read from the installed library, not from the
app process. A 64-bit app accepts a 32-bit library when the phone can run 32-bit programs.

## Context (facts)

- Since K2GO-565 one APK carries `arm64-v8a` and `armeabi-v7a`. Android runs the app as 64-bit on
  every phone that lists `arm64-v8a`. Before, a 64-bit phone could run the 32-bit APK.
- Today the code answers "which architecture is the library?" with the app process, in four
  places that read it in two ways:
  - `Process.is64Bit()`: `RootfsManifest.appAbiId()` (backup identity file, backup file name,
    backup companion, restore identity and signed-image checks), the ELF fallback in
    `RootfsArchiveValidator`, the 64-bit-only module gate (`ModuleCards.runsOnThisRuntime`,
    `LibraryHomeFragment.unsupported`).
  - `nativeLibraryDir`: `CloneFragment.archBits` (Clone QR and gate),
    `SystemStateEvaluator.termuxArch` (fresh install).
- Results with the universal APK:
  - Clone of a library from a 32-bit phone to a 64-bit phone is blocked (the sender sends 32, the
    receiver app is 64, the gate needs equal values).
  - A phone that had the 32-bit APK and a 32-bit library becomes a 64-bit app after the update.
    Its backups then carry `arm64-v8a` in the identity file and companion (wrong), its own 32-bit
    backups are refused on restore, the Clone QR announces 64, and 64-bit-only modules (Kiwix)
    are offered for a library that cannot run them.
- The 64-bit APK already ships the 32-bit proot loader (`libproot-loader32.so`), and
  `ProotEnvironment` sets `PROOT_LOADER_32`. So a 64-bit app can run a 32-bit library when the
  kernel runs 32-bit programs: `Build.SUPPORTED_32_BIT_ABIS` is not empty. Some new 64-bit-only
  phones cannot.
- The installed tree carries its identity file (`installed-rootfs/iiab/.iiab-rootfs.json`, from
  the builder; `.k2go-rootfs.json`, from an app backup). Contract: `docs/ROOTFS_MANIFEST.md`.

## Decision

1. Two facts, one source each:
   - App ABI: `Process.is64Bit()`, in one helper. It answers "what can this app run" and the
     architecture of a fresh install (no library yet).
   - Library ABI: the ELF class of one program in the installed tree (the direct fact). When no
     program can be read, the identity file (`.k2go-rootfs.json`, else the older
     `.iiab-rootfs.json`). The program comes first because a backup made by an older app on a 64-bit
     app with a 32-bit library labels its identity file arm64, and a restore keeps that label in
     the tree. When no library is installed, there is no library ABI.
2. One rule, pure JVM and unit-tested: a library can run when its ABI equals the app ABI, or when
   the library is 32-bit, the app is 64-bit and the phone runs 32-bit programs.
3. The callers use the fact they mean:
   - Clone: the QR sends the library bits. The receiver applies the rule to the sender's bits.
   - Restore and import (identity check and ELF fallback): the rule, applied to the archive ABI.
   - Signed-image restore check: the archive ABI must be one that the rule accepts.
   - Backup (identity file, file name, companion): the library ABI. The companion `X.tar.json`
     also gets `deb_arch` (`arm64` or `armhf`), the same pair as the identity file, so the JSON alone
     tells a 32-bit backup from a 64-bit one. Readers ignore unknown keys, so older files still read.
   - 64-bit-only modules: offered only when the library is 64-bit. With no library, the app ABI
     decides. When a tree exists but its ABI cannot be read (a restore or a Clone mid-way), the
     module stays hidden (fail closed). The rule lives in `LibraryArch.supportsModule`.
   - Fresh install with no library: the app ABI (no change).
4. No cache. The identity file is a few hundred bytes, read when a caller needs it. A restore or
   a Clone that replaces the library is seen at once, and there is no state to set or clear.

## Consequences

- A 64-bit phone that runs 32-bit programs can receive a 32-bit library by Clone again, and a
  32-bit library keeps working after the update to the universal APK.
- A 64-bit-only phone keeps the current "different CPU" block (same screen, same strings).
- The change removes three duplicate answers to one question; it adds no state and no strings.
- Risk: a 64-bit proot that runs a 32-bit library is not yet tested on our devices. Before merge:
  restore an official 32-bit image on the 64-bit app (Samsung), start the server, open the library.
- Out of scope: Kiwix on 32-bit; choosing a 32-bit library on purpose for a fresh install on a
  64-bit phone.
