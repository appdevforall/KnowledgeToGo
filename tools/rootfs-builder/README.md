# rootfs-builder

Tooling that builds the precompiled IIAB rootfs images the app downloads for fast
installs, by replicating the APK's PRoot environment on a Linux host (no Android
device or emulator needed).

- **`build-iiab-rootfs.sh`** — runs the IIAB Ansible installer under the real
  `libproot.so` with the same flags as `PRootEngine.java` (native arm64 when the
  host CPU can run it, else a QEMU user-static fallback). Validates a clean build
  (Ansible `PLAY RECAP`, key files, `chk` answer-sheet) and packages the rootfs
  plus an integrity manifest.
- **`iiab_tree_hash.py`** — reference implementation of the rootfs integrity
  digest (`iiab-tree-sha256-v1`); spec frozen in `docs/ROOTFS_MANIFEST.md`.
  Byte-parity with the in-app `deploy/domain/RootfsTreeHash`.

Sibling of [`tools/proot-builder/`](../proot-builder/), which builds the native
binaries the APK ships.
