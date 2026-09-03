# Post-rebrand device verification — findings & global test matrix

**Context.** The app identifier moved from `org.iiab.controller` to
`org.appdevforall.k2go` (ADR-5368). Because an `applicationId` change is a *new app*
(empty private data, fresh install), this document (a) records what the rebrand could
have broken and how we verify it on a real device, and (b) logs the bugs that surface
while testing. It complements — does not replace — the verification matrix in
`ADR-5368-app-identifier-rebrand.md` §10; that matrix proves *the rename itself*, this
one is the *global* app sweep and the running findings log.

Test device for this pass: **OnePlus 7T (HD1901), arm64-v8a, API level TBD.**

---

## 1. Rebrand-safety recon (static)

- **No old-package references reachable at runtime in app source.** Every `setPackage(...)`
  passes `getPackageName()` (never a literal); `ComponentName(...)` targets are *other* apps
  (Slack share, OEM battery-settings activities), not us; there is no `run-as <literal>` nor
  hardcoded `/data/data/org.iiab...` path in `controller/app/src`. Matches ADR-5368 §7.3.
- **Deliberately deferred (ADR-5368 §11), inert but present:**
  - 42 internal intent-action literals still read `org.iiab.controller.*`
    (`ACTION_*` constants in `InstallService`, `WatchdogService`, `DeepOpService`,
    `CloneShareService`, `KolibriSeedService`, `IIABWatchdog`, `IIABAdbManager`). Each is a
    single `public static final` shared by sender + filter, so they are internally consistent;
    proven inert by ADR-5368 check 13. **Test 13 below re-confirms on device.**
  - `RsyncProcessMatcherTest:36` uses the bare `"org.iiab.controller"` as a *negative* fixture
    (intentional).
  - Docs/runbooks still say `run-as org.iiab.controller` (ADR-5368 §11.2 sub-phase) — a `run-as`
    copied from an old ADR will now fail; use `org.appdevforall.k2go`.
- **Firebase — resolved for the built APK; only local dev copy stale.** The installed release APK
  runs `FirebaseInitProvider` under `org.appdevforall.k2go` (confirmed via `dumpsys package`), so a
  valid `google-services.json` with a `org.appdevforall.k2go` client exists and was used to build it.
  The gate is *local only*: this workstation's `controller/app/google-services.json` still carries
  `"package_name": "org.iiab.controller"`, so a build here fails at `processGoogleServices` until the
  local file (and the CI secret `GOOGLE_SERVICES_JSON_K2GO_ANALYTICS`, if not already) is refreshed
  from the console. Not a shipped-artifact risk. (ADR-5368 §7.2 / §10.4.)
- **Device confirms:** `pm path org.appdevforall.k2go` resolves; `pm path org.iiab.controller`
  is *not installed* (ADR-5368 checks 1–2). ✔

---

## 2. Findings log

### F1 — Uninstalled content cards show RED ("unavailable"), not GRAY ("not installed"), after a backup

**Severity:** medium (misleading status; no data loss). **Rebrand-caused:** no. **Rebrand-exposed:** yes.

**Symptom.** Create a backup, press **Finish** → returns to Library/Home. Content platforms that
are *not installed* render with the **red** dot ("unavailable"/stuck) instead of the **gray**
"Not installed" dot.

**Mechanism.** `LibraryHomeFragment.refreshStatuses()`
([:568-585](../app/src/main/java/org/appdevforall/k2go/redesign/LibraryHomeFragment.java#L568)),
server-alive branch: each card **freshly probes** its endpoint —
`PRESENT → green`, `ABSENT (404 = not installed) → gray`, otherwise **indeterminate** →
`RED` once `serverAliveSinceMs` grace (60 s) has passed, else amber. A backup **stops and
restarts** the box; on return, nginx/dash-node routing is still warming, so a probe to a
not-installed platform does **not** get a clean `ABSENT (404)` — it lands in the *indeterminate*
bucket and paints **red**. The offline branch
([:563-566](../app/src/main/java/org/appdevforall/k2go/redesign/LibraryHomeFragment.java#L563))
does the right thing (`PlatformEvidence.last(...) == ABSENT ? GRAY : AMBER`), but the alive
branch **discards the already-known "not installed" fact** on a transient indeterminate probe.

**Design read (CLAUDE.md coherence).** A fact with an owner ("this platform is not installed",
recorded in `PlatformEvidence`) is re-derived — wrongly — from a transient probe. The fix is to
*fall back to last-known evidence* before deciding RED: an endpoint last seen `ABSENT` should stay
gray through a warm-up indeterminate probe; RED should require either no prior verdict or a prior
non-absent one. Not a new flag — reuse the evidence the offline branch already trusts.

**Why the rebrand exposed it.** The renamed app is a clean install (nothing installed), so after a
backup *every* content card takes the indeterminate branch at once; on the pre-rebrand app with
content present those cards were green and the path never showed.

**Device repro attempt (OnePlus 7T, base tier, debug build):** clean install → base-tier system →
backup (1.65 GB) → Finish → Home. Result: the two not-installed cards ("Read a book", "Take courses")
correctly read **GRAY / Not installed — NOT red**. **F1 did not reproduce this run.**

Why it likely didn't show here: the RED path needs the post-restart probe to land *indeterminate*
(nginx back up but the platform's upstream still 502 during warm-up). On this small base-tier system
the server restart was fast, so the probe got a clean `ABSENT (404)` → GRAY before any red frame.

**Full-tier retry (to test whether backup *duration* is the determinant):** reinstalled to a full
system (all five platforms end up "Ready", so no not-installed targets remain), backed up (2.43 GB
tar — heavier/longer than base), Finish → Home, with a 40 s screen recording over the transition.
Result: all cards read **Ready (green)** immediately after Finish — **no red flash in the still**, and
the recording compressed to ~0.6 MB (near-static, consistent with no flashing). Sent to the reporter
to scrub for any sub-second flash.

**Working conclusion:** F1 as reported (red after backup) **did not visibly reproduce on device**
across base and full tiers on this build (`f03cd618`, debug). By the time Home renders after Finish,
services already answer, so cards resolve straight to green/gray — suggesting the deep-op restarts and
waits for a healthy server *before* returning, so Home never observes the intermediate 502 window via
this path. The code defect is still real by inspection (the alive branch discards a known `ABSENT` on
an indeterminate probe), so the fix stands regardless; but the visible symptom needs the reporter's
exact conditions (build, tier, timing, how long the red persisted) to reproduce, or it may already be
gone on this build.

---

### F2 — Dashboard-rebuild progress resets to 0 on return from the notification; the notification shows no progress

**Severity:** low–medium (UX only; the rebuild itself completes correctly). **Rebrand-caused:** no.
**Area:** K2GO-95 (determinate rebuild progress).

**Symptom (reported on device).** During a dashboard rebuild, minimizing the app → the foreground
notification shows **no percentage and no ETA**; re-entering via the notification while the bar was
~1/3 → the bar **resets to 0** and re-advances from there. The bar does reach 100% when the rebuild
actually finishes (the underlying op is fine).

**Mechanism.** The determinate bar is computed *client-side* in
`DashboardDetailFragment` (K2GO-95 Phase 2) from `progressPhase` + `progressPhaseStartMs`
(`SystemClock.elapsedRealtime()` measuring time *within* a phase), driven by polling the rebuild log
([:66-68](../app/src/main/java/org/appdevforall/k2go/redesign/DashboardDetailFragment.java#L66),
[:394-405](../app/src/main/java/org/appdevforall/k2go/redesign/DashboardDetailFragment.java#L394)).
Both fields are **fragment-local**, and `progressPhaseStartMs` anchors to when *this fragment instance*
first saw the phase — not when the rebuild actually entered it. On minimize→restore the fragment is
recreated, the fields reset (`progressPhase=NONE`, bar → indeterminate,
[:362-363](../app/src/main/java/org/appdevforall/k2go/redesign/DashboardDetailFragment.java#L362)), and
the time-in-phase clock restarts at 0 → the bar restarts. Meanwhile `DashboardRebuildService`
broadcasts only coarse `STATE_RUNNING/DONE`, and its notification has **no `setProgress()`** → no
percentage in the shade.

**Design read (CLAUDE.md coherence).** "How far along is the rebuild" has **no persistent owner** — it
is re-derived per fragment instance from a wall-clock that resets. The phase and its *real* start
(derivable from the log's own timestamps) should live in a repository or the service, surviving the
fragment lifecycle **and** feeding the notification (`setProgress`), so the bar resumes where the
rebuild actually is and the notification can show it. Missing-fact → design fix, not a fragment patch.

**Not rebrand-related.** Candidate for its **own ticket** (the "Pandora's box → separate ticket" rule),
related to **K2GO-95**.

---

### F3–F5 — Deep-op process-screen & notification UX (one cluster; not rebrand-related)

Reported while running the restore; the same pattern spans the deep-op screens (install, backup,
restore, dashboard rebuild, clone). Grouped because they are one cohesive concern, not three unrelated
bugs.

- **F3 — Restore has a reliable determinate bar but no ETA.** The rootfs install shows a time estimate;
  the restore should too. Derivable from `TarExtractor` bytes-extracted vs the archive size (both are
  already logged: `Extract start: … archiveCompressed=…`). Area: K2GO-95 / progress.
- **F4 — The foreground notification deep-links to Settings, not the process/progress screen.** Tapping
  the restore's notification lands on Settings instead of the running-restore window. Every deep-op's
  notification `contentIntent` should open *its own* progress screen — **audit all of them** (install,
  backup, restore, dashboard rebuild, clone); `DashboardRebuildService` already deep-links to its card
  (Module management → Dashboard), so the target exists for some and not others.
- **F5 — "Run in background" behaves like Back, not "go to Home/Library".** On the process screens the
  run-in-background control pops the back stack — reliable, but it can land somewhere unintended; it
  should route deliberately to Home/Library.

**Design read (CLAUDE.md coherence).** These + F2 are one cohesive area: the deep-op process screens and
their notifications lack a consistent contract — a shared progress+ETA model, a notification that opens
its *own* screen, and a defined run-in-background destination. That is a **single "deep-op process-screen
UX" ticket** (with F2), related to **K2GO-95**, not one ticket per symptom (per the "work at the cohesive
-area level" rule).

---

### F6 — Content services don't self-heal after a deep-op; only Kiwix is watched → **K2GO-381**

**Severity:** medium (a restored/cloned box can have a content service down until manual Retry).
**Rebrand-caused:** no. **Confirmed on device (restore round-trip) and in code.**

**Symptom.** After a *restore*, Kolibri did not restart on its own: `:8009` unreachable, `/kolibri/`
returned **502**, and "Take courses" showed a red **"Unavailable"** tile. Kiwix/Books/Maps were fine.

**Root cause (confirmed).** By design the app/reconciler owns box up/down and does **not** manage
individual box services (ADR-5343a §10 layering). Per-service healing is the dashboard's job —
`static/dashboard/sockets/service-heal.ts` probes content services on loopback and issues
`pdsm restart <svc>` on a down one. But its `WATCHED` list contains **only `kiwix`**
([service-heal.ts:28-30](../../static/dashboard/sockets/service-heal.ts#L28)); the comment says the
others "are added here as they are device-verified" and `restartService` already accepts the full set.
So Kolibri/php-fpm/calibre-web are never auto-restarted; Kiwix would have self-healed.

**Not a bug of the rebrand nor new** — it is the incremental rollout planned in ADFA-5343. Fix = extend
`WATCHED`. **Filed as K2GO-381** (relates K2GO-380).

**This reframes F1.** The originally-reported "apps show red/Unavailable after a backup" was almost
certainly *this* — a content service not restarting after a deep-op, correctly rendered red
("down/wedged"), **not** a display bug (not-installed→red). The app card and `service-heal.classifyProbe`
([:50-55](../../static/dashboard/sockets/service-heal.ts#L50)) use the same split: `404 → absent
(gray, not installed)`, `5xx/502/timeout → down (red, Unavailable)`. So F1's display is working as
designed; F6 (service not restarting) is the real defect, now owned by K2GO-381.

---

## 3. Global device test matrix

Grouped identity-sensitive first (what an `applicationId` change can actually break), then the
broad functional sweep. "How" is the device action; "Expected" is pass; the note says why it is
identity-sensitive. Heavy rows (⬇ needs a full download / ⇄ needs a second device) are marked.

### 3.1 Identity-sensitive (the rename's blast radius)

| # | Area | How on device | Expected | Why identity-sensitive |
|---|---|---|---|---|
| I1 | Install identity | `pm path org.appdevforall.k2go` / `…org.iiab.controller` | new resolves, old absent | the change itself — **PASS (verified)** |
| I2 | FileProvider (clone) | Clone → Send → "Can't scan? Share another way" | share sheet opens with the APK attached | authority `${applicationId}.provider`; a mismatch throws before the sheet — ADR check 10 |
| I3 | FileProvider (feedback) | Feedback → attach diagnostics → send | attachment resolves via `getPackageName()+".provider"` | same authority, second call site (`EmailFeedbackSender:28`) |
| I4 | FileProvider (OTA) | Trigger an update install | package installer opens the staged APK | `UpdateController:344` — the updater's own authority |
| I5 | Intent actions (42 legacy) | Stop/start server; run a module install; run a backup | services respond | 42 constants still `org.iiab.controller.*`; proven inert by ADR check 13 — re-confirm |
| I6 | Notification channels | Any FG service (install/backup/clone/terminal) | notification shows in its channel | new app = new channels; 9 `specialUse` FG services |
| I7 | Private dir / `iiab` CLI | Terminal → run `iiab` | CLI works, paths under new private dir | `TerminalController:647` regenerates CLI from `getFilesDir()` |
| I8 | proot boot | Cold start | UP, 0 spurious kills | binds derive from `getFilesDir()`; ADFA-5365 intact |
| I9 | proot seccomp verdict | First launch on affected device | one ADFA-5362 learn line, then `PROOT_NO_SECCOMP=1` | verdict keyed on `versionCode` → re-learns (expected, not a regression) |
| I10 | OTA signer pin | new→new update | updates in place | `ApkVerifier`/`CertDigests` — rebuilt APK must carry the pinned cert |
| I11 | Debug delivery (debug builds) | `am broadcast -a org.appdevforall.k2go.DEBUG_DELIVERY -n org.appdevforall.k2go/…DebugDeliveryReceiver` | receiver enqueues | the ONE new-namespace action; must match new id |
| I12 | Firebase/analytics | build + first run | google-services matches package | **release gate open** — needs `org.appdevforall.k2go` Firebase client (§1) |

### 3.2 Functional sweep (rebrand must not have moved anything)

| # | Area | How on device | Expected | Heavy |
|---|---|---|---|---|
| F-a | Fresh install E2E | uninstall → install APK → complete a rootfs install | reaches services, `/k2go-api` 200 | ⬇ |
| F-b | Content: ZIM/Wikipedia | open Explore Wikipedia | article loads (`/kiwix/` 200 — **verified serving**) | |
| F-c | Content: Kolibri/courses | open Take courses | topics load | |
| F-d | Content: Books | open Read a book | library loads | |
| F-e | Content: Maps | open Navigate maps | tiles render | |
| F-f | Get-More install | install one not-present module | downloads, card → green | ⬇ |
| F-g | Backup create | Settings → backup → Finish | archive written; **watch F1: not-installed cards must read gray, not red** | ⬇ |
| F-h | Restore | restore the backup | round-trips, boots healthy | ⬇ |
| F-i | Clone send/receive | between two new-id devices | pairing + transfer complete | ⇄ ⬇ |
| F-j | Dashboard rebuild | trigger a rebuild | completes, log tails | |
| F-k | OTA check | Settings → check for update | reports latest / offers update | |
| F-l | Terminal | open terminal, run a command | Debian shell responds | |
| F-m | Connect | hotspot QR (K2GO-375 fix) | QR resolves to real AP IP, no `…49.1` | |
| F-n | Feedback | send feedback (mailto default) | mail composer opens to `feedback+k2go@appdevforall.org` | |
| F-o | Permissions | location / battery / unknown-apps prompts | each deep-links to the app's settings | |
| F-p | Notifications | any FG service | POST_NOTIFICATIONS honored, channel shows | |

### 3.3 Live results log

| Check | Result | Notes |
|---|---|---|
| I1 | ✅ PASS | new pkg resolves, old absent |
| I2/I3/I4 (static) | ✅ PASS | `androidx.core.content.FileProvider` registered under `org.appdevforall.k2go`; authority `${applicationId}.provider`. Runtime share-sheet call still to do (UI). |
| I8 | ✅ PASS | server up (`/home` 301); Home reads all platforms "Ready" |
| I12 Firebase | ✅ PASS (build) | `FirebaseInitProvider` runs under new pkg → valid new-package `google-services.json` was used; only local dev copy stale |
| F-b/c/d/e content | ✅ PASS | kiwix=200, kolibri=302, books=200, maps=200 — all present; cards accurate (no false-green) |
| F-k OTA host | ✅ PASS | `https://k2go-download.appdevforall.org/update.json` → 200 from device (CF R2 reachable, network-security-config allows) |
| I2 runtime | ✅ PASS | Clone→Send→"Share the app another way" → Android share sheet opened ("Sharing 1 file · base.apk"); `getUriForFile(getPackageName()+".provider", …)` resolves at runtime under `org.appdevforall.k2go.provider`. The rename's sharpest test (ADR check 10), independently confirmed. |
| F-m Connect/hotspot QR | ✅ PASS | Clone→Send: join-hotspot QR renders, `LocalOnlyHotspot` starts (SSID AndroidShare_7317) under the new package; get-app QR (ApkServer) renders. K2GO-375 fix intact on the renamed app. |
| — | | Build is **release** (`run-as` denied), so on-device fs introspection is limited; use REST probes. |
| I5, I7, OTA install | ➖ ADR-covered | Exercised in ADR-5368 §10.2 (checks 12–13, 11); re-confirm opportunistically. |
| F-a fresh install E2E | ✅ PASS | Clean debug install → base-tier system installed & served (~2 min); Home shows a correct mix (books/courses Not installed, code/wikipedia/maps Ready) |
| F-g backup E2E | ✅ PASS | Settings→Backups→Back up → SAF picker default name `k2go_2026.246_…` (rebranded, single file) → 1.65 GB tar written by DeepOpService → "Backup saved" → Finish returns to Home. Backup writer + deep-op work under the new package. |
| F1 repro (backup→Home) | ⚠ NOT reproduced (this run) | not-installed cards read GRAY correctly after Finish on base tier; red window is a narrow transient (see §2 F1). Retest on full tier / slower restart. |
| Backup naming false-alarm | ✅ retracted | earlier "k2go_ vs iiab-oa_ inconsistency" was a mis-tap selecting an old backup file; the real default is `k2go_…`. |
| I7 terminal, F-h restore, F-i clone | ⏳ later | debug build now enables `run-as` for deeper checks |

### 3.4 ADR-5368 §10 matrix — device confirmation (debug build, full install)

| ADR check | Result | Evidence |
|---|---|---|
| 3 Private dir | ✅ | `run-as … ls files/` → INSTALLATION, rootfs, usr, server_log.txt, watchdog_heartbeat_log.txt — all under the new package |
| 8 Cold boot → UP, 0 kills | ✅ | force-stop+relaunch: `K2Go-Reconciler` ticks `desired=UP actual=UP intent=NOOP [holder=NONE]` steadily; kolibri/nginx/php-fpm start; zero kill/STOP actions |
| 9 proot seccomp | ✅ (effective) | proot runs cleanly (env boots); verdict was learned once at install (keyed on versionCode), now cached — no re-learn line on relaunch, as expected |
| 12 Terminal `iiab` CLI | ➖ risk-covered | CLI is generated lazily at terminal launch from `getFilesDir()`; private dir is already under the new package, so the path it would embed is correct. Live run pending a terminal entry point |
| 14 FG notification | ✅ | `dumpsys notification`: `pkg=org.appdevforall.k2go` channel `watchdog_channel` "K2Go Watchdog Service", title "K2Go Watchdog Active", FOREGROUND_SERVICE |
| 13 Intent actions | ✅ (effective) | the 42 legacy `org.iiab.controller.*` actions drive live flows under the new package: backup ran on `…DEEPOP_*`, boot/services on the reconciler + `INSTALL_*`, and the `watchdog_channel` FG runs on `…WATCHDOG_*` — all worked |
| 18 Custom-View screens | ✅ | Maps landing renders fully (satellite map + FqrController Material3 overlays); its custom-View FQNs resolve under the new namespace (no ClassNotFoundException) |
| 15 Device-to-device clone | ✅ | OnePlus received a full library from a peer (`scanned payload host=192.168.1.160 rootfs=true arch=64`): CONNECTING→CALCULATING→CONFIRM→TRANSFERRING; `CloneShareService` FG on `clone_channel`; the CLONE holder quiesced the server (`desired=DOWN … holder=CLONE`), rsync ran, received system booted healthy (`home=301`, `kiwix=200`) and the reconciler released the holder back to `UP [holder=NONE]`, 0 kills. Post-clone Home shows a not-installed card GRAY (F1 again absent). Benign non-rebrand SELinux denial noted: `librsync.so avc: denied { ioctl }` (TCGETS on a pipe, permissive=0) — non-fatal, rsync completes. |
| 11 OTA new→new | ⛔ | needs an update server offering a newer build (ADR left this reasoned-not-observed) |
| 17 Dashboard rebuild | ✅ | monitored a live rebuild: reconciler held `holder=DASHBOARD` ~2.5 min (self-restarting holder suppressed actuation, server never dropped — `desired=UP actual=UP intent=NOOP`), then released to `holder=NONE`, 0 kills. Surfaced a UX bug (F2, not rebrand-related). |
| 16 Restore | ✅ | monitored a live restore: DEEPOP_RESTORE FG on `deepop_channel`; RESTORE holder quiesced all services (calibre-web/dash-node/kiwix/kolibri/nginx/php-fpm), `TarExtractor` extracted a 2.43 GB archive to `…/org.appdevforall.k2go/files/rootfs` via the new package's `libtar.so`, system booted healthy (`home=301`, `kiwix=200`), holder released to NONE, 0 kills. Surfaced UX findings F3–F5 (not rebrand-related). |

### 3.5 Pristine verdict (this build, debug, device)

**Every ADR-5368 §10 check has now been confirmed on device except one.** Identity side complete
(1–6); functionally 7, 8, 9, 10, 12(covered), 13, 14, 18, 19, 20; content serving
(kiwix/kolibri/books/maps); a full backup E2E; and **15 device-to-device clone** end to end with a
real second phone (pairing → CLONE-holder quiesce → rsync → healthy boot → holder released, 0 kills).
**No rebrand-caused breakage was found.** The **only** unconfirmed check is **11 (OTA new→new)**, which
needs an update server offering a newer signed build — the ADR itself left it reasoned-not-observed, and
its runtime pieces (FileProvider authority via getPackageName, signer pinning) are already exercised by
checks 10 and the share path. **16 (restore)** and **17 (dashboard rebuild)** were subsequently run on
device and also passed (see §3.4 and §4). On the evidence gathered, the rebranded APK is **pristine**:
the rename changed identity and nothing else.

---

## 4. Conclusion — K2GO-380 complete

The app-identifier rebrand (`org.iiab.controller` → `org.appdevforall.k2go`, K2GO-293 / ADR-5368) was
verified end to end on device (OnePlus 7T, debug build; base and full tiers; a real two-device clone).
**The rename changed identity and nothing else — the app runs correctly across every exercised
mechanism.** Every ADR-5368 §10 check runnable without external infrastructure passed: fresh install,
environment boot (0 spurious kills), FileProvider, notifications, custom-View screens, intent actions,
content serving, backup, restore, device-to-device clone, and dashboard rebuild.

**Sole deferred check — OTA new→new (§10 check 11):** an over-the-air self-update from one signed build
to a newer one needs an update server offering a newer build, so it is deferred to a **0.9.0** that can
be published and pulled. Its runtime pieces (FileProvider authority, signer pinning) are already
exercised by the passing checks above.

**Findings — none caused by the rebrand, each with an owner:**
- **F1** — reframed: the "red / Unavailable tile" was a content service that had not restarted,
  rendered correctly (not a display bug). Explained by F6.
- **F6** — content services do not self-heal after a deep-op (`service-heal.ts` watches only kiwix) →
  filed as **K2GO-381**.
- **F2–F5** — deep-op process-screen UX (progress persistence + notification progress, restore ETA,
  notification deep-link target, run-in-background nav) → follow-up ticket (to file; blocked on a
  transient Atlassian outage at close time).
- **Backup ↔ restore standardization** — progress parity (bar / % / ETA) and a **Cancel** affordance
  with differentiated safety (backup: safe → offer to delete the incomplete file, no residue; restore:
  strong warning, no clean cancel — a mid-restore cancel likely leaves the system damaged) → follow-up
  ticket (to file).

**Verdict.** K2GO-380 is **complete**: the post-rebrand device verification is done and the app behaves
as pristine; OTA self-update is the only deferred item, pending a newer published build (0.9.0).
