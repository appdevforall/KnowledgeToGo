# IIAB Controller — Technical Debt Register & Remediation Plan

> Consolidated, English-language successor to `controller/TECH_DEBT_AUDIT.md` (previously Spanish). Produced from four parallel line-level audits — UI/lifecycle, deploy/install, sync/ADB, and monitoring + build/infra. Scope: 34 Java files, ~11,707 LOC under `app/src/main/java/org/iiab/controller/`. Date: 2026-06-16. Repo status: proof-of-concept. See `docs/ARCHITECTURE.md` for how the module works.

## Progress log

_Last updated: 2026-06-23. Tracks remediation work against the findings below. IDs map to the register in this file (F/D/S/M) and to `FORK_DELTA_ANALYSIS.md` (K)._

> **Current status (2026-06-26):** Phase 0 (guardrails) ✅, Phase 1 (security) ✅, and **Phase 2 (concurrency) ✅ closed**. 28 JVM unit-test files; lint is a hard gate. **Phase 2 sweep (epic ADFA-1028, all four areas):** introduced a shared `util/AppExecutors` (pooled `io()` + `scheduler()`); Dashboard polling → lifecycle-scoped ViewModel off the main thread (ADFA-4457); Sync/ADB pairing-thread leak fixed, rsync daemon start moved off the UI thread, process handle made `volatile` (ADFA-4456); Deploy/PRoot `currentProcess` `volatile` + reaped on kill (ADFA-4459, D19); MainActivity one-off threads → executor and recurring `Handler` loops audited-clean (ADFA-4460). **Deferred Phase-2 residuals (low priority, kept open under ADFA-4459):** `D9` (`DeployFragment` `static` download state → instance/ViewModel) and the long-lived proot worker threads; `F12` (`MODE_MULTI_PROCESS`) and the VPN dead-code removal also deferred. `M1` (WatchdogService wakelock) intentionally keeps no timeout (multi-hour transfers; released in `onDestroy`). **Phase 3 — major progress:** `D1` (`DeployFragment`) **DONE** — carved 3,032 → ~855 LOC across backup / planner / install / reset-delete / ADB-share (ADFA-4434, ADFA-4440, ADFA-4441), and the **3 Fragment lint detectors are RE-ENABLED** (ADFA-4443) — the workaround below is **retired**. `MissingTranslation` is back to a hard error (ADFA-4453). **Phase 3 remainder:** `F1` (`MainActivity` ~2,384 LOC) and `S14` (`SyncFragment` ~811) not carved; `D3` (central config/endpoints) not done. **Phase 4:** `M15` done; `M8` (jcenter ×2), `M9` (targetSdk 28 — blocks Play Store), `minifyEnabled false`, and `M19` (`DEBUG_ENABLED` hardcoded) still open. Sections 1–6 below are the original 2026-06-16 audit snapshot.

**Lint — `androidx.fragment` lifecycle detector — RE-ENABLED 2026-06-25 (ADFA-4443)** (was PR `fix/lint-fragment-detector-hang`)
> **Update (2026-06-25):** re-enabled in ADFA-4443. These are method-call detectors that only do expensive work at `observe`/`addCallback`/`addMenuProvider` call sites *inside a Fragment*; after the `DeployFragment` carves there are **0** such sites in any Fragment (they live in non-Fragment controllers + `MainActivity`, an Activity = out of scope), so `:app:lintDebug` finishes in ~minutes (verified ~41s local, CI green). The `disable` line was removed from `build.gradle`. The text below is kept for history.
- `androidx.fragment`'s `UnsafeFragmentLifecycleObserverDetector` **hangs** `:app:lintDebug` for hours — its recursive call-graph walk degrades to near-exponential cost on the oversized `DeployFragment`/`MainActivity` methods (CI lint ~50 min, up to ~3 h on heavy branches).
- **Workaround:** `lintOptions` disables that detector's 3 issue IDs — `FragmentBackPressedCallback`, `FragmentLiveDataObserve`, `FragmentAddMenuProvider` (the only IDs it emits). All other 450+ checks (and `abortOnError true` + baseline) stay active.
- **Re-enable condition:** once `DeployFragment`/`MainActivity` are carved into smaller collaborators (Phase 3 — `D1`/`F1`/`S14`), remove the `disable` and confirm lint completes. This is the trigger to retire the workaround.
- **Root-cause analysis (2026-06-23 — concrete carve targets):** the hang scales with **method size**, not file count — the recursive call-graph walk explodes on a few oversized methods reachable from the Fragment/Activity entry points. Measured: `MainActivity.addNewTerminalSession()` **~727 LOC**, `MainActivity.onCreate()` ~329; in `DeployFragment` the `bind*ButtonLogic` methods (`bindResetButtonLogic` ~184, `bindInstallButtonLogic` ~175, `bindBackupButtonLogic` ~174, `bindBackupMenuLogic` ~130) plus `onRootfsSizeResolved` ~136 and `updateDynamicButtons` ~127 — ~19 methods ≥40 LOC summing ~1,844 LOC (61% of the file). Extracting these into per-area collaborators (install/backup/reset button controllers; a terminal-session builder) shrinks the graph reachable from `onViewCreated`/`onCreate` and is the concrete first step of `D1`/`F1` that retires this workaround.

**Phase 0 — Guardrails: DONE** (PR `chore/phase0-guardrails`, merged as #4)
- Extracted `SystemStatsUtil` and added the first JVM unit tests (`SystemStatsUtilTest`, `SyncHandshakeHelperTest`); added unit-test infra (`returnDefaultValues` + real `org.json`). Addresses **M10**.
- CI gate: blocking `testDebugUnitTest`, blocking `:app:lintDebug` (grandfathered via a committed `lint-baseline.xml`), and the `assembleDebug` compile gate. Addresses **M11**.
- Added a root `.gitignore` and `FORK_DELTA_ANALYSIS.md`.
- **Hardening (DONE):** lint is now a hard gate for `:app` — `abortOnError true` + committed `lint-baseline.xml`, and CI dropped `continue-on-error`. Existing backlog grandfathered; new lint errors fail the build. (Generate/commit the baseline once: `./gradlew :app:lintDebug`.)
- **Tests broadened (DONE):** extracted the `local_vars.yml` reader to a pure, unit-tested `util/LocalVarsYamlParser` (`LocalVarsYamlParserTest`) — the "YAML parser" item and first step on tech-debt **D14**. Notes on the other two named targets: `LogManager.getFormattedSize` is Android-coupled (`Context` + string resources) and its byte→human formatting is already covered by the tested `util/ByteFormatter`; `InstallationPlanner` OS sizing moved into the rootfs domain (covered by `GetRootfsSizeUseCaseTest` / `ByteFormatterTest`), so no separate pure sizing remains there to test.

**K1 — Fork delta (Termux ExtraKeys): DONE** (PR `feat/k1-extrakeys-in-app`, merged as #5; details in `FORK_DELTA_ANALYSIS.md`)
- **K1**: `loadIIABDefaultKeys()` moved out of upstream `ExtraKeysView` into app `IIABExtraKeys` (public APIs only). DONE.
- **K3**: layout is now a single-source-of-truth constant. DONE.
- **K4**: falls back to a minimal layout if the default fails to load. DONE.
- **K5**: unit test validating the layout grid (`IIABExtraKeysTest`). DONE.
- Submodule now points to `appdevforall/termux-app` with a committed SHA (`30ebb2d`, v0.117-436); pointer is in place. **K2** (squash the 8 messy K1 commits) is superseded — that history already merged to `main` via #5, so rewriting it is not worth it. **K6** is cosmetic, upstream-only (priority 10).

**Reference slice — Rootfs live sizes (Clean Architecture pilot): DONE** (PR #6, merged)
- First feature built across all three layers (`org.iiab.controller.rootfs.{domain,data,presentation}` + `util/ByteFormatter`); serves as the copy-paste template for future slices. See root `CLAUDE.md` (design map) and `ROOTFS_SIZE_PILOT_ANALYSIS.md`.
- Replaced the hardcoded `OS_*_GB` constants with live sizes from the Deploy server (`latest_*.meta4`), with per-ABI byte fallbacks for offline/error. Domain is pure JVM and unit-tested (`GetRootfsSizeUseCaseTest`, `ByteFormatterTest`).
- **Strangler step (DONE):** `DeployFragment`'s projection UI now consumes `RootfsViewModel` directly (observes live/fallback OS size) instead of going through `InstallationPlanner.resolveOsSizeGb()`. `InstallationPlanner.calculateProjectedSize` gained a `osSizeGb` overload so the OS-size resolution leaves the planner; the legacy overload (still resolving internally) is retained only for the non-UI install flow.
- **Offline UX (DONE):** addresses the "connectivity gating" watch item. `checkInternetAccess()` now stores a `hasInternet` flag; `updateDynamicButtons()` disables the install button (label "No connection") and the click listener shows a snackbar instead of starting a doomed download; an "Estimated sizes (offline)" caption shows whenever the size is a fallback (`RootfsUiState.live == false`); and offline we skip the live fetch (new `attemptLive` flag on the use case / ViewModel) to avoid the ~6 s timeout. The gauge itself was intentionally left untouched.
- Remaining (optional): later remove the legacy `resolveOsSizeGb` path once the install flow no longer needs it.

**Slice — Device architecture (`org.iiab.controller.deviceinfo`): DONE** (refactor-by-feature while fixing a dashboard bug)
- Bug: the dashboard "device architecture" field reported the *app's* ABI (via `nativeLibraryDir`), so a 32-bit build on a 64-bit device wrongly showed 32-bit (we install the 32-bit app on 64-bit hardware to test the 32-bit path).
- Fix: new layered slice — `domain` (`DeviceAbiProvider` port + `GetDeviceArchUseCase`, prefer-64-bit rule, pure JVM) and `data` (`BuildDeviceAbiProvider` reading device-level `Build.SUPPORTED_*_ABIS`). `DashboardFragment` now shows the real device arch; `getTermuxArch()` stays for app/content arch (modules, termux, debian). Unit test `GetDeviceArchUseCaseTest` covers the 32-bit-app-on-64-bit-device case.

**S1 — Sync credential injection (Phase 1 security): DONE** (PR `feat/phase1-security-sync-credential-validation`)
- Closes **S1**: QR-scanned sync credentials (host/port/user/pass) were interpolated into `rsyncd.conf` and the `rsync://` client URL unescaped, enabling config-directive and URL injection.
- New pure domain rule `org.iiab.controller.sync.domain.SyncCredentialValidator` (strict user/host charsets, port range, control-char-free password, `isSafeConfigValue` for config lines). Unit-tested (`SyncCredentialValidatorTest`) plus three new malicious-payload cases in `SyncHandshakeHelperTest`.
- Validation applied at the untrusted boundary (`SyncHandshakeHelper.parsePayload` -> `null` on invalid) and defensively in `RsyncManager` (server config + client URL paths), with a new `rsync_error_invalid_credentials` string (en + es). The validator is the reusable contract the remaining injection fixes (**S4**, **D2**) can build on.

**M4 + S3 — Phase 1 security one-liners: DONE** (PR `fix/phase1-security-m4-s3`)
- **M4** (`IIABWatchdog.broadcastLog`): the `ACTION_LOG_MESSAGE` broadcast carrying log text was implicit (no `setPackage`), leaking it to any installed app and crashing on API 34+. Now scoped to our own package, matching every other broadcast in the app.
- **S3** (`IIABAdbManager` key spec): the ADB identity key was created with `ENCRYPT | DECRYPT` + non-randomized encryption padding on top of sign/verify. Verified no `Cipher` uses this alias, so the spec is now `SIGN | VERIFY` only (encryption paddings / `setRandomizedEncryptionRequired(false)` removed). Alias kept at `v3` (non-disruptive: hardens new key generation without forcing existing devices to re-pair ADB).
- No new unit tests: both are framework-bound legacy line-fixes with no extractable pure logic; verified by inspection + compile + CI lint.

**D6 — Download integrity + TLS fail-closed (Phase 1 security): DONE** (PR `fix/phase1-security-d6-download-integrity`)
- Closes **D6**: the rootfs (`latest_*.meta4` -> `.tar.gz`) is extracted and executed as root, but downloads had no integrity check and `Aria2Manager` silently fell back to `--check-certificate=false` when `cacert.pem` was missing.
- **TLS fail-closed**: `cacert.pem` is now required (fail with a clear error if it cannot be provisioned) and `--check-certificate=true` is always passed — the insecure fallback is removed. Applies to all aria2 downloads (rootfs + ZIM).
- **Integrity**: `--check-integrity=true` makes aria2 verify the SHA-256 checksums published in the `.meta4` (confirmed present: file-level + per-piece) during download; on mismatch aria2 exits non-zero, `onError` fires and the archive is never extracted. Uses the server's published hash + verified TLS, so no redundant on-device re-hash of the ~1.2 GB file.
- Follow-up (separate items): ZIM content integrity (Kiwix publishes no embedded hash today; needs `.sha256` sidecars) and the cleartext OTA APK path in `MainActivity` (**F15**).

**D2 — Module-name command injection (Phase 1 security): DONE** (PR `fix/phase1-security-d2-module-injection`)
- Closes **D2**: `DeployFragment.processNextInQueue()` interpolated the module name into a `sed ... && echo ... && ./runrole NAME` command executed as root in the container. Names come from the fixed `ModuleRegistry` catalog but round-trip through SharedPreferences, so a tampered/unknown value with shell metacharacters could inject commands.
- New pure domain rule `org.iiab.controller.deploy.domain.ModuleName.isAllowed(name, known)`: the name must be a known catalog key (allowlist) AND match `[a-z0-9_-]` (no quotes/`;`/`&&`/`$()`). `ModuleRegistry.validYamlKeys()` is the single allowlist source. Unit-tested (`ModuleNameTest`).
- The guard fails closed: an unrecognized/unsafe module is logged and skipped before the command is built; the command structure is unchanged for legitimate modules. Lower-risk on-device `sh -c` pipes (extract/backup, app-internal paths) are a documented follow-up.

**D12 — Undrained process pipes / swallowed exec errors (Phase 1 security): DONE** (PR `fix/phase1-security-d12-process-drain`)
- Closes **D12**: several `Runtime.exec(...).waitFor()` calls in `DeployFragment` never read the child's output (deadlock risk once the ~64 KB pipe buffer fills — notably the backup `tar | gzip` pipe on a large rootfs) and some swallowed failures in empty `catch (Exception ignored) {}`.
- New shared `util/ProcessRunner.run(cmd)`: `redirectErrorStream(true)` + full drain + returns `{exitCode, output}`, so a single read cannot deadlock and callers can log/handle failures.
- Migrated the raw `exec().waitFor()` sites (backup, `chmod -R`, the three `rm -rf` wipes); empty catches now log. Left the extraction path (already drains stderr) and the `getprop` read (reads stdout) as-is. No new unit test (process glue, not pure logic — fragile to run a shell in unit tests on a Windows dev box); verified by inspection + CI compile.

**M15 — Build coupled to network for native artifacts: FIXED** (PR `fix/m15-build-binary-sync-fallback`)
- `:app:syncNativeArtifacts` (`preBuild` dependency) called the GitHub API **unauthenticated on every build**; since `jniLibs/*.so` are gitignored, CI always downloads and hit GitHub's 60/hr unauthenticated limit -> intermittent **HTTP 403** ("tag not found"), failing `assembleDebug` on unrelated PRs.
- Fix: (1) **cache-first** — check the local tracker/binaries/manifest before any network call, so builds with artifacts present skip the API entirely; (2) **fallback auth** — fetch release metadata UNAUTHENTICATED first (so forks without a token still build), and only on failure retry with `GITHUB_TOKEN`/`GH_TOKEN` from the env; (3) clearer rate-limit error. CI passes `secrets.GITHUB_TOKEN` to the gradle steps (job-level env). No token is ever *required*.

**S3 — ADB keystore scope: REVERTED / not real debt** (PR `fix/adb-keystore-revert-s3`)
- S3 (PR #10) re-scoped the ADB identity key to `SIGN | VERIFY` only, assuming the `ENCRYPT|DECRYPT` + non-randomized PKCS1/NONE encryption paddings were unused attack surface. **That assumption was wrong.** The ADB connection runs over TLS and the libadb/conscrypt handshake signs with the key via a raw RSA op (`Cipher "RSA/ECB/NoPadding"`), which **requires** `PURPOSE_ENCRYPT` + `ENCRYPTION_PADDING_NONE/PKCS1` + `setRandomizedEncryptionRequired(false)`. On a freshly generated key the keystore rejected the op (`INCOMPATIBLE_PADDING_MODE`) and **every ADB connection broke** (confirmed via logcat).
- Reverted: restored those capabilities and bumped the alias `iiab_adb_key_v3` -> `v4` so already-broken keys regenerate (one-time ADB re-pair). Added a comment marking the capabilities as required. **S3 is withdrawn from the register** (misdiagnosis, not debt).

**D11 — Archive path-traversal on extraction (Phase 1 security): DONE** (PR `fix/phase1-security-d11-archive-traversal`)
- Closes **D11**: `TarExtractor` extracted with `tar -xf -C destDir` without validating member names. An **imported/restored backup is untrusted** (`Import backup` accepts any file and `restore` extracts it into `filesDir/rootfs`), so a crafted `.tar.gz` using `../` or absolute members could escape the destination and overwrite app files.
- New pure domain rule `org.iiab.controller.deploy.domain.ArchiveEntry.escapesRoot(name)` (rejects absolute paths and `..` that climb above the root; allows benign `./` and internal `a/../b`). Unit-tested (`ArchiveEntryTest`).
- `TarExtractor` now **pre-lists** entries (`tar -t`, gzip decompressed in Java) and **fails closed** if any member escapes — for *every* extraction (the verified rootfs install included, defense in depth). Also single-quoted the paths in the backup-creation `sh -c` pipe (the "unquoted backup pipe" half of D11).
- Verify on a real install/restore that legitimate rootfs/backup members are relative (they are by convention) so the guard does not false-positive.

**S4 — Arbitrary ADB shell command (Phase 1 security): DONE** (PR `fix/phase1-security-s4-adb-command`)
- Closes **S4**: `IIABAdbManager.executeCommand` ran `openStream("shell:" + command)` — an on-device shell over ADB — with no validation. Callers pass fixed commands today, but a value with shell metacharacters could chain/substitute extra commands run with ADB privileges.
- New pure domain rule `org.iiab.controller.adb.domain.AdbShellCommand.isSafe(command)` (rejects `; | & $ \` ( ) < > ' " \\`, CR/LF and control chars). Unit-tested (`AdbShellCommandTest`).
- `executeCommand` now fails closed (logs + does not open the stream) on an unsafe command; the two legitimate `settings put` / `device_config put` calls are unaffected.

**F15 — OTA self-updater security + correctness, PR A** (PR `feat/ota-updater-security-redesign`)
- The OTA updater downloaded the new APK to **public Downloads** and installed it with **no integrity check**; `DownloadManager` reports "complete" even for an HTML/text error page, so a wrong/MITM'd response was installed as garbage (the "downloaded a text file" bug), and the completion receiver was registered **EXPORTED**.
- PR A (security + functional correctness, layered `org.iiab.controller.update` slice):
  - `domain/` — `UpdateCheck` (version rule) + `CertDigests.sameSigner` (pure, unit-tested).
  - `data/ApkVerifier` — the downloaded APK must be signed by the **same certificate as the running app** (public certs via `PackageManager`, no secrets); rejects MITM/tampered APKs and non-APK downloads (kills the text-file bug).
  - `MainActivity` seam: stage the APK in the app's **private** external dir (not public Downloads); only install when `DownloadManager` status is SUCCESSFUL; **verify signature before install**; handle the API 26+ "install unknown apps" permission; register the receiver **NOT_EXPORTED**.
- Follow-ups: **PR B** (presentation: `UpdateViewModel` + in-app download-progress UX) and a separate `network-security-config` to scope cleartext to the local box hosts (**S18**; deferred to avoid risking box connectivity).

**Backup import/restore validation (ABI separation + rootfs sanity): DONE** (PR `feat/rootfs-import-restore-validation`)
- The import flow accepted **any** file as a backup, with no check that it is a rootfs or the right architecture. Per the ABI-separation policy (ARM64↔ARM64, 32↔32), a 32-bit rootfs must not be importable/restorable into a 64-bit app (and vice-versa), and a non-rootfs (e.g. a ZIM) must not be treated as one.
- New pure domain: `deploy/domain/ElfClass` (read 32/64 from an ELF header) + `RootfsArchive` (structural "looks like a rootfs" + pick a probe binary). Unit-tested.
- `deploy/data/RootfsArchiveValidator` lists the archive, runs the structural check, and probes one internal binary's ELF class vs the app's ABI (`Process.is64Bit()`). **Two gates, hard-block, fail-closed:** at **import** (reject + delete) and at **restore** (`TarExtractor` gains a `validateRootfs` overload that reuses its D11 listing). A *definite* wrong-arch is blocked; if arch can't be determined we don't block on arch (the structural check still applies).
- **Identity manifest (soft):** also reads the build's `installed-rootfs/iiab/.iiab-rootfs.json` (per `docs/ROOTFS_MANIFEST.md`) — when present it authoritatively gates `kind` + `arch`; when **absent** it shows a non-blocking "manifest not found" alert and falls back to the ELF/structure heuristic. (Integrity `iiab-tree-sha256-v1` / `Result.CORRUPT` shipped separately in **#37** — see next entry.)
- **Integrity verification + writer: DONE** (PR **#37** `feat/rootfs-import-restore-integrity`, merged). `deploy/domain/RootfsTreeHash` (pure-JVM `iiab-tree-sha256-v1`, byte-parity with `tools/rootfs-builder/iiab_tree_hash.py`, proven by `RootfsTreeHashTest`) + `deploy/data/RootfsIntegrity` (one-pass, dependency-free ustar/GNU/pax reader; **no Apache Commons Compress** — minSdk 24 has no core-library desugaring and CC reaches into `java.nio.file` API 26+). Matrix: absent→`OK_NO_MANIFEST`; `origin:device-backup`/`algo:none`→`OK_NO_CHECKSUM` (proceed + transparency); match→`OK`; **mismatch/unreadable→`CORRUPT` (blocks at import like `WRONG_ARCH`)**. Integrity runs only at the import gate and rides identity (device backups recognized from the first header — no full pass). The backup **writer** stamps an identity manifest with `origin:device-backup` packed first (no device-side treehash). Tests + checked-in fixtures (ustar/GNU/pax/mismatch/none/absent).
- **Pending (documented):** on-device round-trip with a real 32-bit and 64-bit backup; the arbitrary-file attack-vector analysis; folding the rare builder-rootfs-manual-import verify into the import copy (one extra pass for that path only); relay the `origin`/`algo:none` addendum to `docs/ROOTFS_MANIFEST.md`.

**Phase 1 — Security hardening: COMPLETE (core).** Done: **S1** (#9), **M4** (#10), **D6** (#12), **D2** (#13), **D12** (#16), **D11** (#15), **S4** (#28), and **F15** — PR A security redesign (#30) + PR B in-app progress UX (#32). **S3 reverted** (#21, misdiagnosis). Beyond the original register: rootfs import/restore **ABI + identity validation** (#31) and **integrity `iiab-tree-sha256-v1` + device-backup writer** (#37). Remaining (lower-priority follow-ups): `S18` network-security-config (deferred), `D17` `ApkServer` HTTP/auth, `S11` rsync plaintext secrets, and the lower-risk `sh -c` pipe inputs (D2 follow-up).

**F4 + M7 — confirmed mitigated (2026-06-23, verified by inspection):** **F4** — `MainActivity` has no leaking recurring runnables: `sizeUpdateHandler` (10s) and `serverCheckHandler` are stopped in `onPause` and re-armed in `onResume` with remove-before-post; `timeoutHandler` is one-shot. **M7** — `DashboardFragment.onResume` posts the 5s refresh loop and `onPause` calls `removeCallbacks`, so it cannot stack (Android guarantees `onPause` between `onResume` calls). Both can be marked closed in the register (section 3); optional polish: add a defensive remove-before-post in `DashboardFragment.onResume`.## 1. Executive summary

The Controller is functional and shows real security intent (it SHA256-audits native binaries at build time, scrubs the keystore in CI, and scopes most broadcasts). But it carries debt on four fronts that scale badly toward the README's "millions of users" goal:

1. **Security — most urgent.** Shell commands run **as root inside the container** from concatenated strings (command injection), with **no integrity verification** of multi-gigabyte downloads, over cleartext HTTP that can silently disable TLS validation. A compromised mirror or MITM can deliver a malicious rootfs that then executes as root. The ADB identity key is also mis-scoped, and an unscoped log broadcast leaks data to any installed app.
2. **Monolithic architecture.** Two God classes — `MainActivity` (2,209 LOC) and `DeployFragment` (2,754 LOC) — hold ~8 responsibilities each. Shared state lives in **public mutable fields on `MainActivity`** that fragments read/write directly, even from background threads.
3. **Ad-hoc concurrency.** ~30 raw `new Thread()` calls, no executor, no cancellation, no lifecycle awareness; callbacks touch `Activity`/`Context` after teardown; non-`volatile` flags race; process pipes risk deadlock.
4. **No safety net.** **Zero tests** despite JUnit/Espresso being declared; CI only runs `assembleDebug` with lint commented out and `abortOnError false`.

**Recommendation:** do **not** open with a massive rewrite. Phase 0 installs guardrails (tests + CI gate on pure logic), Phase 1 closes the security holes, and only then do Phases 2–4 attack concurrency and architecture with the net already in place.

## 2. Scoring method

Each item scores **Impact** (1–5, how much it slows the team), **Risk** (1–5, what happens if unfixed), and **Effort** (1–5, lower = easier). **Priority = (Impact + Risk) × (6 − Effort)** — higher is more urgent. IDs are prefixed by cluster: **F** = UI/lifecycle, **D** = deploy/install, **S** = sync/ADB, **M** = monitoring + build/infra.

## 3. High-priority register (Priority ≥ 24)

| ID | Location | Category | Issue | Imp | Risk | Eff | Prio |
|----|----------|----------|-------|----|----|----|----|
| M4 | `IIABWatchdog.java:382` | Code/Sec | `broadcastLog` sends `ACTION_LOG_MESSAGE` (log text) with no `setPackage()` — implicit system-wide broadcast = info leak + API 34 crash | 3 | 4 | 1 | 35 |
| S3 | `IIABAdbManager.java:54` | Security | ADB keystore key created with `ENCRYPT/DECRYPT` + no padding + `setRandomizedEncryptionRequired(false)`; should be sign/verify-only | 4 | 4 | 2 | 32 |
| M1 | `WatchdogService.java:83` | Code | `PARTIAL_WAKE_LOCK` acquired with **no timeout**; a crash before `onDestroy` pins the CPU awake until reboot | 4 | 4 | 2 | 32 |
| M3 | `AndroidManifest.xml:34`, `WatchdogService.java:66` | Code/Infra | `specialUse` FGS without typed `startForeground` + subtype property; will be rejected on API 34+ (blocks SDK upgrade) | 4 | 4 | 2 | 32 |
| D2 | `DeployFragment.java:1337,1204,1816` | Security | Command injection: module names/paths/lang strings interpolated into `sh -c`/`sed` and run as container root | 5 | 5 | 3 | 30 |
| D6 | `Aria2Manager.java:96`; no `MessageDigest` | Security | No SHA256/signature check of downloaded rootfs/ZIM before extract+exec; aria2 silently drops TLS check if cacert missing | 5 | 5 | 3 | 30 |
| S1 | `RsyncManager.java:73,134,231` | Security | rsyncd.conf + client URL built by unescaped concat from QR-scanned user/pass/path → config/URL injection | 5 | 5 | 3 | 30 |
| M7 | `DashboardFragment.java:191` | Code | `onResume` posts the 5 s refresh loop without `removeCallbacks` first → stacking poll loops + ping-thread storms | 3 | 3 | 1 | 30 |
| M8 | `build.gradle` (root):17,40 | Dependency | Dead `jcenter()` repo declared twice (sunset 2021) — build-reliability + supply-chain risk | 3 | 3 | 1 | 30 |
| F4 | `MainActivity.java:96,164,433` | Code | No `onDestroy()`: three recurring `Handler` runnables capture and leak the Activity | 3 | 4 | 2 | 28 |
| F12 | `Preferences.java:115` | Code | `MODE_MULTI_PROCESS` SharedPreferences (deprecated/unreliable) → cross-process state desync | 3 | 4 | 2 | 28 |
| D12 | `DeployFragment.java:1022,1073,1111,1163` | Code | `Runtime.exec(...).waitFor()` with empty `catch{}` and undrained pipes → silent failures + deadlock risk | 3 | 4 | 2 | 28 |
| S4 | `IIABAdbManager.java:191` | Security | `executeCommand` concatenates into `"shell:" + command` with no sanitization = arbitrary on-device shell | 5 | 4 | 3 | 27 |
| F5 | `MainActivity.java:582,825` | Code | Asymmetric receiver register/unregister across lifecycle pairs — fragile | 3 | 3 | 2 | 24 |
| F7 | `MainActivity.java:491,1043` | Code | Bare `catch(Exception){return false;}` hides network/parse failures; no retry/backoff | 3 | 3 | 2 | 24 |
| F14 | `MainActivity.java:1135` | Code | Synchronous `HttpURLConnection`, no shared client, `getResponseCode()` called twice (double round-trip) | 3 | 3 | 2 | 24 |
| F15 | `MainActivity.java:1066`; Manifest:28 | Security | Cleartext OTA + install APK from public Downloads → MITM/supply-chain vector | 3 | 5 | 3 | 24 |
| D3 | `InstallationPlanner.java:22`; `DeployFragment.java:987` | Code/Config | Hardcoded mirror host, GitHub raw URL, pinned distro/proot versions scattered across files | 4 | 4 | 3 | 24 |
| D9 | `DeployFragment.java:117,358` | Architecture | `static Aria2Manager` + `static isDownloadingRootfs` → download survives Fragment recreation, desyncs UI | 4 | 4 | 3 | 24 |
| D11 | `DeployFragment.java:1204`; `TarExtractor.java:49` | Security | Extraction trusts archive entries (no `../` path-traversal guard); unquoted backup pipe | 4 | 4 | 3 | 24 |
| D13 | `InstallationPlanner.java:107` | Code | Kiwix catalog built by regex-scraping an HTML listing; hardcoded GB constants drift → wrong storage gating | 4 | 4 | 3 | 24 |
| D14 | `DeployFragment.java:1567` | Code | Hand-rolled YAML "parser" splits on `:` — breaks on nesting/quotes/comments | 3 | 3 | 2 | 24 |
| D17 | `ApkServer.java:20,39` | Security | APK server over plain HTTP, no auth/host check; conflicting `Content-Length` on chunked response | 3 | 3 | 2 | 24 |
| D19 | `PRootEngine.java:328` | Code | `killProcess` calls `destroy()` without `waitFor()`; orphaned proot children + lingering mounts; `currentProcess` not `volatile` | 3 | 3 | 2 | 24 |
| S7 | `SyncFragment.java:83`; `RsyncManager.java:31` | Code | Hardcoded ports (8730/8080), user `iiab_peer`, module/URL path; no port-in-use fallback | 3 | 3 | 2 | 24 |
| S8 | `SyncFragment.java:116,518`; `AdbPairingReceiver.java:25` | Code/Arch | Raw threads + per-call main-Handler; dialogs shown from background after `onDestroyView`; pairing ExecutorService never shut down | 4 | 4 | 3 | 24 |
| S9 | `SyncFragment.java:280`; `RsyncManager.java:285` | Code | `catch(Exception ignored)` across IP discovery, reachability, stop, progress parsing — silent field failures | 3 | 3 | 2 | 24 |
| S11 | `RsyncManager.java:64,125` | Security | Plaintext secrets written to cacheDir; client passfile leaks if killed; server secrets never deleted on stop | 3 | 3 | 2 | 24 |
| S12 | `AdbPairingReceiver.java:35` | Security | Pairing host/port taken straight from intent extras; unvalidated beyond PIN length | 3 | 3 | 2 | 24 |
| S13 | `TermuxCallbackReceiver` + Manifest | Code | Targeted by a `PendingIntent` but **never declared/registered** → callback path is dead/broken | 3 | 3 | 2 | 24 |
| M10 | no `src/test`/`src/androidTest` | Test | Zero unit/instrumented tests despite declared deps; all pure logic unverified | 4 | 4 | 3 | 24 |
| M11 | `.github/workflows/android-sanity-check.yml:34` | Infra | CI only `assembleDebug`; lint commented out + `abortOnError false` → no quality gate | 3 | 3 | 2 | 24 |

## 4. Medium / lower-priority themes (Priority < 24)

Rather than list ~30 more rows, the remaining findings cluster into recurring themes; full line references live in the per-cluster audit notes.

**Concurrency & lifecycle (Prio 16–21):** dashboard spawns a new thread with N+1 blocking pings every 5 s (`M2`); deploy uses uncancelled handlers/threads guarded only by `isAdded()` (`D8`); a 7-flag implicit install state machine is race-prone (`D7`); `SyncFragment`/`RsyncManager` share unsynchronized process handles and start the daemon on the UI thread (`S10`); an unbounded `top -b -d 1` monitor thread never stops (`S17`).

**God classes & duplication (Prio 9–18):** `MainActivity` (`F1`) and `DeployFragment` (`D1`) are the structural root; `SyncFragment` is a third (`S14`). Download/extract pipelines (`D4`), `PRootEngine` exec paths (`D10`), and view-tree navigation by `getChildAt`/`performClick()` (`D15`) are duplicated/fragile.

**Hardcoding & magic values (Prio 12–20):** scattered URLs/ports/timeouts (`F10`), OTA version math (`F11`), inline hex colors (`S15`), undocumented `ppk_value` magic strings (`S16`).

**Rendering & memory (Prio 18–20):** gauges force `LAYER_TYPE_SOFTWARE` and animate at 60 fps on the main thread (`M6`); per-session 5,000-line scrollback grows RAM (`F25`); `AppListActivity` loads all packages + icons on the main thread with no view recycling (`F21`, `F22`); QR built pixel-by-pixel (`F23`).

**Build / infra / deprecation (Prio 7–20):** `abiFilters` contradicts the `splits` block (`M12`); build couples to network for native artifacts every `preBuild` (`M15`); `minifyEnabled false` on release; deprecated `onBackPressed`/`ListActivity` (`F20`); `DEBUG_ENABLED=true` shipped (`M19`); broad `MANAGE_EXTERNAL_STORAGE`/`SYSTEM_ALERT_WINDOW`/cleartext permissions (`M14`, `S18`).

**Documentation & dead code (Prio 5–16):** residual Spanish comments violating the English-only standard (`F16`, `D20`); dead `ServiceReceiver` with a null-action NPE (`M13`); duplicate one-liner methods (`D18`); `cacheDir` vs `filesDir` proot_tmp mismatch (`D21`); missing runbook for the manual Firebase/signing deploy and the `targetSdk 28` rationale (`M20`).

**Strategic (Prio 7, but blocking):** `targetSdk 28` (`M9`) keeps dangerous patterns "working" and blocks Play Store (requires 34+). High effort, but it gates the app's distribution future — track it as an epic, not a cleanup.

## 5. Phased remediation roadmap

The plan is designed to run **alongside feature work**, lowest-risk-first.

**Phase 0 — Guardrails (1 sprint).** Add JVM unit tests for the pure functions that have no Android deps — `parseMemLine`, `getDebianArch`, `evaluateSystemState`, log `maintenance` rotation, `getFormattedSize`, `InstallationPlanner` size math, `SyncHandshakeHelper` JSON parse, the YAML parser. Turn the CI lint gate back on (`abortOnError true`, uncomment `lintDebug`) and add a `testDebugUnitTest` step. This is the net everything else depends on (`M10`, `M11`).

**Phase 1 — Security hardening (1–2 sprints).** Close the injection and integrity holes that run as root: parameterize all shell invocations / validate-and-quote inputs (`D2`, `S1`, `S4`, `D11`); add mandatory SHA256 (and ideally signature) verification of every download before extract/exec, and fail closed if cacert is missing (`D6`); scope the log broadcast and remaining implicit broadcasts (`M4`); fix the ADB keystore key to sign/verify-only (`S3`); harden `ApkServer`/rsync secret handling (`D17`, `S11`); scope cleartext via a network-security-config instead of the global flag (`F15`, `S18`).

**Phase 2 — Concurrency & lifecycle stabilization (2–3 sprints).** Introduce a single shared executor + lifecycle-scoped cancellation; replace raw threads and convert recurring `Handler` loops to be torn down in `onDestroy`/`onPause` (`F4`, `M1`, `M7`, `D8`, `S8`, `M2`); make shared process handles `volatile`/synchronized and add `waitFor()` after `destroy()` (`D9`, `D19`, `S10`); drain process pipes and stop swallowing exec errors (`D12`, `S9`); replace `MODE_MULTI_PROCESS` with a real IPC/state mechanism (`F12`).

**Phase 3 — Architecture decomposition (ongoing, behind the net).** Extract config constants into a single `DeployConfig`/`Endpoints` class (`D3`, `F10`, `S7`). Introduce ViewModels + a thin repository layer so state leaves `MainActivity`'s public fields (`F8`, `M17`). Carve `DeployFragment` into download/extract/provision services and `MainActivity` into updater/terminal/server-controller collaborators (`D1`, `F1`, `S14`). Once these methods shrink, **remove the `lintOptions` disable of the `Fragment*` checks** (see Progress log). De-duplicate the download and PRoot exec paths (`D4`, `D10`).

**Phase 4 — Build, distribution & polish.** Plan the `targetSdk 28 → 34+` epic (`M9`, `M3`) once Phases 1–2 remove the patterns that depend on the SDK exemption. Remove `jcenter()` (`M8`), align `abiFilters`/`splits` (`M12`), decouple native-artifact fetch from every build (`M15`), enable `minifyEnabled`, translate residual Spanish comments (`F16`, `D20`), delete dead code (`M13`, `D18`), and write the deploy runbook (`M20`).

## 6. Week-1 kickoff — start paying debt now

Begin field work immediately with this batch — all low-effort, high-signal, and safe to ship piecemeal:

1. **One-liners (a single PR):** scope `broadcastLog` with `setPackage()` (`M4`); add `removeCallbacks` in `DashboardFragment.onResume` (`M7`); delete both `jcenter()` lines (`M8`); add `onDestroy` removing the three `MainActivity` handlers (`F4`); wire `DEBUG_ENABLED` to `BuildConfig.DEBUG` (`M19`); add a `WAKE_LOCK` timeout (`M1`); declare or delete `TermuxCallbackReceiver` (`S13`); remove dead `ServiceReceiver` (`M13`).
2. **Keystore fix:** drop encrypt/decrypt + padding flags from the ADB key spec → sign/verify only (`S3`).
3. **CI gate:** uncomment `lintDebug`, set `abortOnError true`, add `testDebugUnitTest` (`M11`).
4. **First tests:** unit-test the pure functions listed in Phase 0 to seed the safety net (`M10`).
5. **Config extraction (start):** move scattered URLs/ports/versions into one constants class (`D3`, `S7`) — mechanical, unblocks later phases.

After this 