# ADR-K2GO-554 : Signed official rootfs skips the second archive pass

- Status: Accepted (2026-11-12, with Amendment 1)
- Date: 2026-11-07
- Ticket: K2GO-554 (relates to K2GO-555, K2GO-372, K2GO-511, K2GO-457, ADFA-5110)
- Author: AppDevForAll

## Decision in one line

Sign each official rootfs tarball with minisign (Ed25519, pinned public key). When
a download or an imported tarball verifies against our key, the app trusts those
exact bytes and skips the second decompression pass: the listing on a download, and
the listing plus the treehash on an import. A missing or bad signature falls back to
today's full path (fail closed). One signed file serves the app, developers on a PC,
and offline sharing.

## Context (facts)

- An official install decompresses the whole gzip twice: once to list members and
  once to extract. On the farm Q8 (32-bit) the listing took 1 h 21 min and the
  extraction 1 h 23 min. The listing pass exists only to run the path-traversal
  guard and to count the progress total. (K2GO-555; `TarExtractor.listEntries`.)
- An import decompresses more than twice: the listing, the treehash recompute, and
  the extraction. (`deepop/DeepOpService.java:449`; `deploy/data/RootfsIntegrity.java`.)
- aria2 checks the downloaded gzip sha-256 against the meta4. The app also re-hashes
  it: `DownloadVerifier.verify(file, meta4.size, meta4.sha256)` compares byte length
  and sha-256 and fails closed.
  (`controller/.../download/domain/DownloadVerifier.java`;
  `controller/.../Aria2Manager.java:212,507`.)
- The meta4 is a Metalink 4 file: a file-level `<hash type="sha-256">` of the gzip,
  a compressed `<size>`, and `<url>` mirrors. It is NOT signed. Trust today rests on
  HTTPS to the R2 host only.
  (`controller/.../download/domain/MetalinkFile.java`; `Aria2Manager.java:596`.)
- The builder already publishes the uncompressed size as a stable sidecar
  `latest_<tier>_<abi>.installed` (ADFA-5110), and the app already fetches it.
  (`tools/rootfs-builder/build-iiab-rootfs.sh:818`;
  `controller/.../rootfs/data/RootfsCatalog.java:132-135`.)
- The builder already computes a content treehash `iiab-tree-sha256-v1` over the
  logical members. The app can recompute it byte-for-byte (`RootfsIntegrity`,
  reference `iiab_tree_hash.py`), but only on IMPORT, not on the official download.
  (`tools/rootfs-builder/build-iiab-rootfs.sh:844-879`;
  `tools/rootfs-builder/iiab_tree_hash.py`.)
- The install extract calls `TarExtractor.startExtraction(...)` with
  validateRootfs=false, so the listing runs only for the traversal guard and the
  totals. Restore and import call it with validateRootfs=true (full check).
  (`controller/.../install/presentation/InstallService.java:656`.)
- K2GO-555 made the extract total an injected value: the extract weights progress by
  uncompressed bytes and reads the total from the listing, falling back to the member
  count when there are no sizes. The total is the only value the trusted path must
  supply. (`TarExtractor` Listing + byteMode.)
- The backup direction already measures byte-accurate progress from the Java-side
  gzip boundary: tar writes the uncompressed stream and Java compresses it, so the
  uncompressed byte count is observable without a listing.
  (`controller/.../backup/domain/BackupEngine.java:63-85,172-173`.)
- There is no signing infrastructure in `tools/` or `.github/workflows/` today. The
  only keys are the APK signing keystore (RSA, in `KEYSTORE_BASE64`) and OpenSSL as
  an aria2 build dependency. Rootfs signing is new.
- minisign is packaged on every target developer OS (apt, dnf, pacman, brew, scoop)
  and ships as a single static binary for offline use. Its signature format is its
  own (not OpenSSL-interoperable; signify-compatible). minisign prehashed mode (`-H`)
  signs a BLAKE2b-512 digest of the file, which suits large files.

## Problem

Low-end phones spend over an hour on passes that only re-establish facts the builder
already knows: the archive has no path-traversal member, and how big it is. The
sha-256 in the meta4 could let the app trust the bytes and skip those passes, but the
meta4 is unsigned, so a compromised host or CDN could serve a crafted tarball with a
matching meta4. The app needs a trust anchor stronger than HTTPS before it can safely
drop the on-device guards. The same anchor should let developers verify a tarball out
of band, and let offline-shared tarballs be trusted.

## Decision

- D1. Sign the tarball with minisign. A dedicated signing step in the bake workflow
  signs each official dated `k2go_<date>_<tier>_<sha>_<arch>.tar.gz` with minisign in
  prehashed mode (`-H`, BLAKE2b-512, suited to large files), after the build and the
  bake gate (D4). The step runs on the runner with the signing key as a step-scoped
  secret, exactly like the R2 upload step today; the builder itself stays secret-free
  (see D3 and the H custody notes). The output is a detached per-artifact sidecar
  `k2go_<date>_<tier>_<sha>_<arch>.tar.gz.minisig`, named in the SAME scheme as the
  existing `.sha256`, `.installed`, `.meta4` and `.torrent` sidecars. There is NO
  `latest_*` signature: the signature belongs to the immutable dated tarball, not to a
  pointer (see D2 and the lifecycle in PR1). The app resolves it by appending
  `.minisig` to the tarball URL the pointer `.meta4` names. The minisign format carries
  an algorithm tag, an 8-byte key id, the Ed25519 signature, a trusted comment, and a
  global signature over the signature plus the trusted comment.

- D2. One signed artifact for all consumers. The same `.minisig` serves the app
  download path, out-of-band developer verification on a PC (`minisign -Vm <tarball>
  -p <pubkey>`, one step), and offline or signed import. There is NO separate trust
  manifest and NO signed meta4: signing a file that already exists beats inventing one
  to sign. The RFC 5854 meta4 and the `.installed` sidecar are unchanged and stay
  unsigned; `.installed` only feeds the progress total, which is cosmetic (a wrong
  total skews the bar, never what gets written). We deliberately do NOT sign
  `.installed`: not signing it loses no function and no security (K2GO-555 already
  fails safe when the total is absent or out of range), while signing the pointer
  `.installed` would reintroduce exactly the promote-time lockstep the dated-sidecar
  model removes (the pointer `.installed` is copied on promote), which is the orphan
  risk we want to minimize.

- D3. Dedicated signing key and custody. A NEW Ed25519 minisign key, separate from the
  APK signing keystore. The APK key is RSA (it cannot produce an Ed25519 signature) and
  is the most sensitive secret in the project, so it stays isolated: one key per
  purpose. Custody (H): the release owner generates the key OFFLINE (`minisign -G -W`,
  no passphrase) and keeps an offline backup; the key never generates in CI. The secret
  key is base64 (`base64 -w0`) in the GitHub Actions secret `ROOTFS_MINISIGN_KEY_BASE64`
  (the `KEYSTORE_BASE64` pattern); the secret store is the at-rest encryption, so no
  passphrase is kept. Losing the GitHub secret is recoverable from the offline backup;
  losing both forces a rotation. The public key is committed at
  `controller/rootfs-signing.pub` (key id `C5E45B250781C960`) and is NOT secret. The app
  pins the minisign public key(s); the key id in the signature selects which pinned key
  verified. Rotation: pin a second public key in a future app release, sign with it,
  retire the old one after an overlap. No rotation automation is built now.

- D4. Bake gate. The signing step runs the path-traversal check on the built tarball
  right before it signs (reject any absolute or `..` member), so the gate and the
  signature are one atomic step with no trust gap. The signature then certifies a
  tarball that already passed the gate, which is what lets a trusted artifact skip the
  on-device listing guard: the guarantee moves from the device to the build. The
  bundled GNU tar also refuses `..` members and strips a leading `/` at extract time,
  so tar itself remains a second line of defense.

- D5. App verification library. The app verifies the minisign signature with
  BouncyCastle, using the lightweight `org.bouncycastle.crypto` API (not the JCA
  provider, so R8 strips the rest). It streams BLAKE2b-512 over the downloaded `.gz`
  once (`Blake2bDigest`), then runs two Ed25519 checks (`Ed25519`): the main signature
  over the 64-byte digest, and the global signature over the signature plus the
  trusted comment. It checks the key id against the pinned key(s). Tink is NOT used
  here: its Ed25519 verify needs the whole message in memory and the rootfs `.gz` is
  multi-GB, and it has no BLAKE2b; BouncyCastle does both and streams. Signature
  verify needs NO Android permission, so the manifest permission set is unchanged (a
  project constraint: do not grow permissions, to avoid Play review risk). Size: BouncyCastle is already a transitive dependency of the app, so the verify path
  adds about 0 MB (the spike confirmed it on host and on-device); PR2 declares the
  dependency explicitly since the app uses it directly. Ed25519 is
  deterministic by construction, so signing in CI needs no special RNG handling.

- D6. Trusted download: skip and inject. On an official download, after aria2 and
  DownloadVerifier confirm the `.gz` against the meta4 (download mechanics,
  unchanged), the app verifies the `.minisig`. On success the download is TRUSTED:
  the app skips `listEntries` and passes `uncompressed_bytes` from `.installed` as the
  extract total (K2GO-555 already injects a total). The traversal guard is covered by
  D4. For progress, see D8.

- D7. Trusted import: skip the treehash too. An imported tarball that arrives with a
  valid `.minisig` for a pinned key is also TRUSTED: import skips BOTH the listing and
  the on-device treehash recompute, for the same reason as a download (our signature
  exists only on tarballs that passed the bake gate). This roughly halves import
  processing on a slow device (one streaming hash pass to verify, versus a full
  decompression plus the treehash pass). Only our signature is trusted: a user backup
  or a third-party tarball with no valid `.minisig` keeps the full listing plus
  treehash, unchanged. The content treehash (`iiab-tree-sha256-v1`) stays as the
  integrity anchor for unsigned import only.

- D8. Progress without the listing. Without the listing there is no per-member size
  map, so the extract drives the byte bar from the uncompressed bytes the gzip feeder
  writes to tar's stdin, against `uncompressed_bytes`. Pipe backpressure keeps the fed
  count close to tar's consumption, and BackupEngine already proves this signal in the
  compress direction. Confirm on a slow device that it does not saturate early
  (K2GO-384's early saturation was the COMPRESSED measure, not this).

- D9. Fail closed. A missing `.minisig`, an unknown key id, a bad signature, or a
  download that does not match the meta4 falls back to today's full path: the listing
  on a download, and the listing plus treehash on an import. Trust is strictly
  additive; the slow path is always the floor.

## Backward compatibility and rollout (defensive design)

A hard requirement, not a nicety: a user on an OLD APK, who does not or cannot update,
must keep installing and updating rootfs exactly as today. Signing must never strand
them, and a workflow change must never break the app from the commit before this work.

- BC1. Strictly additive artifacts. The signing step only ADDS the `.minisig`. It does
  NOT modify or rename anything the current app reads: the `.tar.gz`, the `.meta4`, and
  the `.installed` keep the same names and bytes. An old APK never fetches the
  `.minisig`, so it is unaffected by construction. The bake gate reads the built
  tarball but does not change it.
- BC2. New app fails closed. The new app works with OR without a signature present: no
  `.minisig` means it falls back to today's full path (D9). So the app is correct
  before, during, and after signatures appear.
- BC3. Accept downgrade. A signed OLDER official tarball verifies and installs. We do
  NOT reject older-than-installed: an old-APK or offline user must still be able to
  take a rootfs that is updated in real time. Rejecting it would be a disservice, and
  an older OFFICIAL rootfs is our content, not a compromise.
- BC4. Rollout order (activation, not merge). Release the new app to ALL channels
  first. Only after every channel carries an APK that understands signatures do we turn
  on signing in CI. Because the artifacts are additive (BC1) and the app fails closed
  (BC2), no order can actually break an old APK; this order is the extra, cautious
  belt. Code for both PRs may be written in parallel, but the signing workflow is
  ENABLED last.

## Amendment 1 (2026-11-12): identity from a signed manifest, one hash pass

### Goals

The app must prove two facts, with the fewest full reads of the tarball, on a device
with no hardware crypto:

- Identity: the bytes are the bytes we signed, not a file with our names on it.
- Integrity: the bytes arrived complete and unchanged.

### Measurements

Instrumented tests in the app process (same ART and BouncyCastle as production).

| Device | SHA-256 (Conscrypt) | BLAKE2b-512 (BouncyCastle) | Ed25519 verify |
|---|---|---|---|
| Q8: Unisoc SC7731E, 4x Cortex-A7, 32-bit, no SHA2/AES extensions | 32-36 MiB/s | 3.4 MiB/s | 15.7 ms |
| OnePlus 7T: arm64, SHA2 extensions | about 860 MiB/s | (not the limit) | 4.7 ms |

- Time is linear in size (100 MiB and 250 MiB runs). Projection for the Q8 full
  armeabi-v7a tarball (3788 MiB): BLAKE2b 19 min, SHA-256 1.8 min.
- The SHA-256 rates in the table read files that are still in the page cache. On a
  real Q8 install the tarball is larger than RAM and is read from storage: SHA-256
  ran at 11.9 MiB/s (3 min 10 s for the 2.26 GB standard tarball).
- End to end with Amendment 1, OnePlus 7T, dev full arm64 (4.0 GB): the trust check
  took 0.16 s (2 min 12 s with the D5 BLAKE2b verify), and the time from download end
  to extraction done fell from 4 min 46 s to 2 min 31 s.
- End to end with Amendment 1, Q8, dev standard armeabi-v7a (2.26 GB): the trust
  check took 1.3 s, mostly the `.minisig` fetch on a slow link. No listing ran. The
  remaining verify cost is the existing SHA-256 pass, which A6 would remove.
- On the OnePlus 7T, the D5 verify over the 4 GB full tarball took 2 min 12 s with no
  progress. The UI stayed at the last download percent. SHA-256 over the same file
  took 3.4 s.
- The D5 verify reads the whole `.gz` a second time, after DownloadVerifier already
  read it for SHA-256. On a device with no hardware crypto, it is the slowest step
  after the extraction.
- aria2 1.37.0 with the app flags, against a local test server: a changed byte exits
  1, a short file exits 8, and a corrupted piece on resume is detected and fetched
  again (exit 0, correct bytes). aria2 does NOT compare the file-level hash: a forged
  meta4 (correct file hash, piece hashes of a different file) exits 0 with the
  different file. aria2 gives integrity per piece, not identity.

### Decisions

- A1. Manifest in the trusted comment (amends D1, D2). The signing step keeps one
  `.minisig` per tarball and keeps `-H`. Its trusted comment becomes a manifest:
  `k2go-rootfs v1 file=<tarball name> size=<gz bytes> sha256=<gz sha-256> meta4=<sha-256 of the dated .meta4>`.
  The global signature authenticates the trusted comment, and that check does not read
  the tarball. The main signature still covers the whole tarball, for
  `minisign -Vm` on a PC. There is no new sidecar, so the PR1 lifecycle is unchanged.
- A2. Identity check (amends D5). The app checks the key id and verifies the global
  signature only (one Ed25519 verify). Then it parses the manifest strictly: exact
  prefix and version, all required fields, size as digits, sha256 as 64 lowercase hex.
  An unknown version or a missing field means "no manifest".
- A3. Trusted download, one hash pass (amends D6). The download is trusted when the
  manifest is authentic, its `file`, `size` and `sha256` equal the meta4 values, and
  DownloadVerifier returns OK. DownloadVerifier is the existing SHA-256 pass
  (ADFA-4676), so the trusted path adds one small fetch and one Ed25519 verify to
  today's download. The device does not compute BLAKE2b. The signed `file` must also
  be the bake artifact name for the tier and ABI that the app requested
  (`k2go_<yyyy.ddd>_<tier>_<sha>_<abi>.tar.gz`). Without this check, an unsigned meta4
  could offer an official tarball of another ABI or tier, and the app would trust it
  and skip the guards.
- A4. BitTorrent on the signed rootfs download: kept as is (decided 2026-11-12). It is
  resolved together with A6, which needs the same local-meta4 handoff. The meta4 carries a
  `<metaurl mediatype="torrent">`, and aria2 turns the download into a BitTorrent
  session with the HTTP mirrors as web seeds. Pieces are then checked against the SHA-1
  hashes of the unsigned `.torrent`, not the meta4 SHA-256 pieces. Measured on
  2026-11-12: `--follow-torrent=false` does NOT disable this (it applies only to a
  `.torrent` fetched over HTTP, not to a metalink metaurl). With the app hash pass of
  A3 this is not an identity gap. To use only HTTP, the app must give aria2 a meta4
  without the metaurl (the same local-meta4 handoff as A6), or the builder must stop
  writing the metaurl. DHT and LPD were enabled in 2026-05 with no recorded reason.
- A5. Trusted import (amends D7). An import needs the tarball and its `.minisig`
  (2 files). Identity: A2. Integrity: one SHA-256 pass of the tarball against the
  manifest `sha256` and `size`. Then the import skips the listing and the treehash.
- A6. Deferred: zero hash passes on download. This is possible when the sha-256 of
  the meta4 bytes equals the manifest `meta4`, the app gives aria2 that exact meta4 as
  a local file (today aria2 fetches the meta4 URL again: `Aria2Manager.java:212` versus
  `:372`), and BitTorrent is off (A4). aria2 then checks each piece against
  authenticated hashes during the download, and the app checks only the size. This
  removes the ADFA-4676 re-hash, so it needs its own tests first.
- A7. No manifest means no fast path (amends D9). A `.minisig` without a manifest
  (for example the 2026.316 dev artifacts) is not trusted on the device, and the full
  path runs. There is no BLAKE2b fallback on the device: on a low-end phone it costs 7
  to 19 minutes with no progress.

### Transition

| Files published | Old APK | New APK, download | New APK, import | Developer on a PC |
|---|---|---|---|---|
| tarball, no `.minisig` | today | full path | full path | `sha256sum -c` (integrity only) |
| tarball + `.minisig`, no manifest | today | full path | full path | `minisign -Vm` |
| tarball + `.minisig` with manifest | today (ignores `.minisig`) | trusted, one SHA-256 pass | trusted, one SHA-256 pass | `minisign -Vm`; the trusted comment shows the expected sha-256 |

Order: (1) the app with A2 to A5 reaches all channels (BC4); (2) CI writes the
manifest (only the `-t` value changes); (3) new bakes carry the manifest. An older
`.minisig` without a manifest stays valid for `minisign -Vm`.

## Out of scope

- Replacing the meta4 or the aria2 download path. The signature is additive.
- Native (non-Java) decompression of the single remaining extract pass (a later
  speed-up: the bundled tar with -z needs a gzip binary, or a zstd rootfs). See
  K2GO-511.
- Signing the APK or the OTA channel (that is the APK keystore, unrelated).
- Changing the treehash spec (`docs/ROOTFS_MANIFEST.md`, frozen).
- OpenSSL interoperability of the signature (minisign format is its own; see Notes).

## Phasing (PR | contents)

- PR0 (this ADR): the signing contract and the decisions. No code.
- Spike (pre-PR1, done, GREEN; throwaway branch, not merged): BouncyCastle lightweight
  (`Blake2bDigest` stream plus `Ed25519`) verifies a minisign `-H` vector. Host JVM: 3
  of 3 (valid, tampered, wrong key). On-device inside K2Go (OnePlus 7T): 2 of 2.
  Streaming proven: a 1 GB file verifies under a 128 MB heap. Size: BouncyCastle is
  already a transitive dependency of the app, so the verify path adds about 0 MB. The
  Q8 timing of BLAKE2b over the full `.gz` is deferred (performance, not feasibility).
  Measured on 2026-11-12: see Amendment 1.
- PR1 (bake/CI): the signing key already exists (H and D3: secret
  `ROOTFS_MINISIGN_KEY_BASE64`, public key `controller/rootfs-signing.pub`). Add a
  dedicated signing step to the bake workflow that, after the build, runs the
  path-traversal bake gate on the built tarball and then signs it with `minisign -H`,
  emitting `<tarball>.minisig` beside the other dated sidecars. The step runs on the
  runner with the secret as step-scoped env (the R2-upload pattern); the builder stays
  secret-free. The step then self-verifies the fresh signature with the committed public
  key before upload, which catches a mangled secret at once. Lifecycle (simple, because
  the signed object is the immutable dated tarball, never a pointer):
  CREATE: the `.minisig` lands in `dist/` and rides the existing recursive data upload
  in the bake job (no exclude filter matches it), so no pointer step changes.
  PROMOTE and ROLLBACK: unchanged. The promote copies only the `latest_*` pointers
  (`.meta4`, `.installed`); the dated tarball and its `.minisig` never move. Add one
  additive guard: the promote validation refuses to repoint to a tarball whose
  `.minisig` is absent (fail closed, only once signing is enabled).
  SUPERSEDE: add `.minisig` to the prune sidecar list, so the signature is deleted in
  lockstep with its tarball.
  CONSISTENCY CHECK: a CI check that every published `k2go_*.tar.gz` has exactly one
  `.minisig` (no missing signature, no orphan `.minisig` without a tarball). It catches
  a half-finished bake or a missed prune; the app fails closed meanwhile, so a gap is a
  lost speedup, never a broken install.
- PR2 (app): bundle the BouncyCastle lightweight API; pin the public key(s); parse and
  verify the `.minisig` (stream BLAKE2b-512, two Ed25519 checks, key-id selection);
  wire the trusted-download skip and inject (D6) and the trusted-import skip (D7); the
  feeder-byte progress (D8); the fail-closed fallback (D9). JVM unit tests for the
  `.minisig` parse, the signature verify with minisign-generated test vectors, the
  key-id selection, the trust decision, and tamper cases (bad signature, wrong key,
  truncated file).
- Rollout: PR2 ships and reaches ALL channels BEFORE PR1 signing is enabled (BC4).

## Open questions

- Signature lifecycle: RESOLVED (see PR1). The signature is a per-artifact sidecar of
  the immutable dated tarball, so promote and rollback do not touch it; only CREATE
  (rides the data upload), SUPERSEDE (prune lockstep), the promote presence guard, and
  a consistency check remain, all owned by PR1 (ties to K2GO-457). The exact CI diff is
  an implementation detail of PR1.
- BouncyCastle size: RESOLVED. BouncyCastle is already a transitive dependency, so the
  verify path adds about 0 MB and there is no size budget concern. PR2 declares the
  dependency explicitly because the app uses it directly.

## Notes

- minisign format is not OpenSSL-compatible (its own two-line format); it IS
  verifiable with signify. Developers install minisign (apt, dnf, pacman, brew, scoop)
  or copy the single static binary for an offline machine, then verify with
  `minisign -Vm <tarball> -p <pubkey>` in one step.

## References

- Listing and byte-weighted extract: `controller/.../TarExtractor.java` (K2GO-555).
- Download verify and meta4: `controller/.../download/domain/DownloadVerifier.java`,
  `MetalinkFile.java`; `controller/.../Aria2Manager.java:212,507,596`.
- Uncompressed-size sidecar: `tools/rootfs-builder/build-iiab-rootfs.sh:818`;
  `controller/.../rootfs/data/RootfsCatalog.java:132-135` (ADFA-5110).
- Treehash: `tools/rootfs-builder/iiab_tree_hash.py`;
  `controller/.../deploy/data/RootfsIntegrity.java`; `docs/ROOTFS_MANIFEST.md`.
- Byte-accurate progress precedent: `controller/.../backup/domain/BackupEngine.java`.
- Install and import extract call sites:
  `controller/.../install/presentation/InstallService.java:656`;
  `controller/.../deepop/DeepOpService.java:449`.
- Promote and republish workflow: K2GO-457 (R2 republish).
