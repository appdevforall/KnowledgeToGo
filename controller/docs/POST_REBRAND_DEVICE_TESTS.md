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

**Repro (device):** install rootfs → create a backup → Finish → observe Home cards. **TODO:
capture on the OnePlus and confirm whether `serverAliveSinceMs` is reset across the deep-op (if
not, RED is immediate, with no amber grace).**

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
| F-g backup / F1 repro | ⏳ next | Needs a **basic-tier** install (fewer platforms present) → uninstall + reinstall (debug build, Firebase off). |
