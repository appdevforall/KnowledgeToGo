# ADR-395 -- Network cost consent (metered-data gate)

- Status: Proposed
- Date: 2026-09-06
- Ticket: K2GO-395 (Relates to K2GO-4 "Resilient download contract")
- Author: AppDevForAll

## 1. Context

K2Go downloads very heavy content: the rootfs image is ~2-4 GB, ZIM files reach
tens to hundreds of GB, maps reach tens of GB. A user on a metered link (mobile
data, or a metered Wi-Fi hotspot) can spend real money or burn a monthly data
plan with a single tap, with no warning today.

K2GO-4 already makes downloads **resilient** -- they survive a network change
mid-download (Wi-Fi to mobile) by pause/resume/reconnect. Resilience is not the
same fact as **cost consent**. Surviving a switch to mobile data is not the same
as asking permission to spend it. This ADR defines the cost-consent mechanism.
There is no ticket and no code for it today (verified: a repo-wide search for
`isActiveNetworkMetered`, `NOT_METERED`, `setAllowedOverMetered` returns zero
hits).

### 1.1 The three-process reality (why this is not a socket problem)

Egress does not come from one place. It comes from three, and only some are
under the app's control:

| What is downloaded | Which process pulls the bytes | App can throttle the socket? |
|---|---|---|
| Rootfs tarball + proot Debian base (GB) | on-device `aria2` (`libaria2c.so`) | Yes (own process) |
| ZIM / Books / Maps / Kolibri content (GB) | the in-proot server (dash-node); device only POSTs + polls | **No** -- not the app's socket |
| OTA APK, portal APK/PDF | Android system `DownloadManager` | Via its own API |
| Manifests, `.meta4` size, catalog ETags (KB) | app process (`HttpURLConnection`) | Yes |

The key consequence: for content (the biggest cost driver) the app **cannot**
make the transfer "Wi-Fi only" at the network layer, because the in-proot server
holds the socket, not the app. The only honest lever is to **not authorize the
job to start** (gate before the POST), and to cancel/pause it through the
existing REST cancel if the user asks.

### 1.2 The primitive is metered, not "cellular vs Wi-Fi"

The user goal is "do not spend costed data without asking". The correct signal
is therefore **metered vs not-metered**, not transport. Two facts force this:

- A phone hotspot is Wi-Fi but costs data. "Wi-Fi" does not mean "free".
- On real hardware "cellular" is not one network. See the device evidence
  (Section 6): the carrier's IMS PDN reports NOT_METERED while its internet APN
  does not. Keying on `TRANSPORT_CELLULAR` would misjudge cost.

So the rule keys on the **active default network's** `NET_CAPABILITY_NOT_METERED`.

## 2. Decision

Add one **cost-consent gate** consulted at the START of every heavy download,
plus one process-wide **metered observer** for the proactive alert. Defensive by
design: do not start anything costly on a metered link without consent; do not
attempt fine control of a transfer already in flight (the app cannot).

### 2.1 Behavior

1. **Start gate.** Before a heavy download starts, classify the active default
   network. If unmetered -> proceed. If metered and the user has not consented
   this session -> ask ("You are on mobile data ... continue?"). If they decline,
   do not start. If no network -> tell them they are offline. Once consent is
   given, it holds for the session: 1 KB or 5 GB, it does not ask again.
2. **Proactive alert.** If the default network crosses INTO metered while the app
   runs -- even with nothing pending -- post a notification so the user knows
   further activity spends data.
3. **In flight = best effort only.** A transfer already running on a link the app
   does not own is left alone. Resilience (K2GO-4) means it can pause/resume, but
   this ADR does not add fine control. Offering "cancel or continue" on such a
   transfer is a possible follow-up, not part of this contract.

### 2.2 One source per fact (anti-duplication ledger)

| Fact | Existing owner | This design |
|---|---|---|
| "network changed" | `sync/transport/NetworkStateLiveData` (the only default-network callback) | REUSED via `observeForever`; no second registration |
| "what is the network" | `DashboardRebuild.hasInternet`, `InstallService.hasValidatedInternet` (two readers today) | New `AndroidNetworkClassifier` becomes the one reader; fold the two existing ones into it as a follow-up |
| "is a heavy transfer running" | `ContentDownloadSession`, `InstallProgressRepository` | READ if needed; never duplicated |
| "did the user consent to spend data" | none (new fact) | `SessionMeteredConsentStore` (in-memory) |

### 2.3 Lifecycle of the consent grant

- **Standing preference** (a future "Wi-Fi only" toggle) would be persisted,
  default on. Not built yet; the gate already behaves as if it is on.
- **Session grant** is ephemeral, in memory. Set by the consent dialog. Cleared
  by the observer when the network leaves metered, and by process death (the only
  store keeps it in memory). A persisted "always allow" is deliberately NOT
  offered: it would defeat cost awareness and would be the stuck-marker
  anti-pattern (a persisted flag nobody clears).
- **If the process dies mid-metered-download**: on restart the grant is gone; if
  still metered and a transfer wants to resume, the gate asks again. No stuck
  state.

## 3. Design (layered feature `networkpolicy`)

New self-contained feature package `org.appdevforall.k2go.networkpolicy`, wired
by hand (no DI), placed beside the existing `network` (DNS) feature.

```
networkpolicy/
  domain/       NetworkClass, NetworkPolicyDecision, NetworkPolicy,
                NetworkTransition, MeteredConsentStore   (pure JVM, unit-tested)
  data/         AndroidNetworkClassifier (the one ConnectivityManager reader),
                SessionMeteredConsentStore (in-memory grant)
  presentation/ NetworkPolicyGate (stateless start gate + consent dialog),
                MeteredNetworkObserver (process-wide alert + grant lifecycle)
```

- `NetworkPolicyGate` is stateless, in the style of `OpReturnNavigator`: it owns
  no "is metered" flag; it reads the live class and the session grant and returns
  a decision.
- `MeteredNetworkObserver` is one process-scoped owner started from
  `IIABApplication`, in the style of `ServerLifecycleReconciler`.

## 4. Seams (where the gate is consulted)

Each content family has a `*ConfirmFragment` with a Start/Add button whose click
is the user commit point. The gate wraps THAT click, never the background
`*Provisioner.drain` (it runs every ~2 s to re-hand an already-authorized
wishlist and would re-prompt). Every confirm fragment has the same shape as the
reference (`ZimConfirmFragment`), so each remaining seam is a one-line wrap.

| Family | Commit-point seam (exact) | Wrap |
|---|---|---|
| ZIM | `ZimConfirmFragment.java:105-110` -> `a.startZimDownload()` | LANDED (reference) |
| Books | `BooksConfirmFragment.java:82` -> `a.startBooksDownload()` | `guardHeavyStart(a, a::startBooksDownload)` |
| Kolibri | `KolibriConfirmFragment.java:227` -> `startLive(chosen)` | `guardHeavyStart(requireActivity(), () -> startLive(chosen))` |
| Maps (Get More, post-install) | `SetupLibraryActivity.openMapsIndex` (the `MapsConfirmFragment` live `else` branch, `MapsConfirmFragment.java:103`) | LANDED -- `guardHeavyStart` inside the `InstallConfirm.gate` body. Base layers pre-download over REST before the runrole: K2GO-394 moved the maps bytes onto dash-node (`InstallService.downloadMapsBasemapsThenRun` -> `RestContentClient("basemaps")`), so it IS a costed heavy start. NOT gated at `MapsProvisioner.drain`: that drain is a serialized proot stage where a refusal is TERMINAL (`SetupProgressActivity` retires it as `mapsStartFailed`, by design, to avoid an unexplained spinner), so a cost-hold does not fit there without an orchestrator "waiting for network" state -- that headless integration rides with the install/rootfs PR. |
| Dashboard update/install (LIVE) | `DashboardRebuild.start` -> `startRest` (`DashboardRebuild.java:96`) | LANDED -- `guardHeavyStart` on the LIVE branch only. dash-node >= 1.2.0 git-fetches + blue-green rebuilds over REST across the default network. The proot bridge (< 1.2.0, `startProot`) stops the box and is out of scope. Only `startRest` POSTs a new rebuild (`DashboardRebuildService` `ACTION_START`); `ACTION_ATTACH` re-owns a running one without POSTing, so one UI gate covers it. |
| Wizard/system-install maps (`mapsWizardConfirm`) | wizard banks; base-maps REST download runs during the install | COVERED by the `startWizardInstall` gate -- the wizard only banks; the maps download runs inside the install, which that gate already covers (session-wide consent). See sec.5. |

Dashboard and Get-More-Maps are user-driven Operations, not banked `ContentType`s,
so they gate at the UI commit (like FQR), NOT through `ContentAdmission` --
`ContentAdmission` already defers TO a dashboard update and to maps, so routing
them back through it would be circular.

DownloadManager seams differ -- no `Service.start`; the app enqueues and the
system transfers. Consult the gate first, then honor the decision (proceed on
consent, or set `setAllowedOverMetered(false)` as the fallback):

| Path | Enqueue site |
|---|---|
| OTA APK | `UpdateController.java:210-223` |
| Portal APK | `PortalActivity.java:464-477` |
| Portal PDF / other box file | `PortalActivity.java:502-513` |

### 4.1 Recipe (content seam)

Replace `X.startYDownload()` at the commit click with
`NetworkPolicyGate.guardHeavyStart(activity, activity::startYDownload)`, where
`activity` is the hosting Activity (the consent dialog needs an Activity context).
A seam with no Activity (a pure background start) cannot show the dialog -- but
those are post-authorization drains, correctly left ungated.

### 4.2 Recipe (DownloadManager seam)

Before `dm.enqueue(request)`: classify with `AndroidNetworkClassifier`. If metered
and not consented, either ask via the gate or set
`request.setAllowedOverMetered(false)` so the system holds it for Wi-Fi. If
unmetered or already consented, enqueue as today.

### 4.3 Native Kolibri import (WebView interception)

A distinct egress the app does NOT own: Kolibri's OWN web app (served by the box at
`/kolibri/`, shown in the PortalActivity WebView) has its own content-import
manager. Tapping Import there starts a server-side download from Kolibri Studio
over the metered link, bypassing Get More / ContentAdmission entirely. Device recon
(reproduced on the OnePlus over a metered hotspot: a 265 MB import began downloading
with no prompt) identified the trigger: `POST /api/tasks/tasks/` (cancel is
`POST /api/tasks/tasks/<id>/cancel/`; the queue polls
`GET /api/tasks/tasks/?queue=content`).

`KolibriGuardController` gates it, mirroring `FqrController`/`KiwixManageController`:
armed on the `/kolibri/` page, it injects JS that wraps XHR (axios) and fetch,
parks a remote-import task POST, calls the native bridge (`K2GoKolibri.gateImport`)
which runs `NetworkPolicyGate.guardHeavyStart`, and proceeds or aborts on the
result. Caveats (from the code-review second pass): it depends on Kolibri's internal
task API, so it fails OPEN (never bricks import) and logs a console warning on any
task POST it cannot classify (the regression signal); the remote-vs-local decision
is a body heuristic (`remote`/`channelupdate`, not `disk`) pending a task-type-field
match. This is the one seam that reaches into a third-party app's egress, so it is
inherently best-effort.

## 5. Reference implementation status (this change)

Landed as a compiling, tested starting point for the implementer:

- Domain, pure JVM, unit-tested: `NetworkClass`, `NetworkPolicy`,
  `NetworkTransition`, `NetworkPolicyDecision`, `MeteredConsentStore`
  (`NetworkPolicyTest`, `NetworkClassTest`, `NetworkTransitionTest` -- green).
- Data: `AndroidNetworkClassifier`, `SessionMeteredConsentStore`,
  `NetworkCostAdmission` (the one classify + consent + policy decision source).
- Presentation: `NetworkPolicyGate` (the UI prompt, + `BrandDialog`),
  `MeteredNetworkObserver`.
- Enforcement (headless): `ContentAdmission.canStart` defers when
  `NetworkCostAdmission.allowsHeavyStartNow` is false, so the ZIM, Books and
  Kolibri drains all HOLD a banked order on metered-without-consent (sec.10).
- Wiring: observer started in `IIABApplication`; the ZIM and Books commit points
  (`SetupLibraryActivity.startZimDownload` / `startBooksDownload`) bank first, then
  gate the drain, so a declined/offline order is queued, not lost.
- Strings translated to all 33 locales (machine-generated, pending human review),
  folded into `values*/strings.xml` (one string file; no per-feature file);
  `strings_untranslated.xml` is clear.

Done since: the Books and Kolibri commit-point prompts, the FQR maps-region seam
(sec.4.1), the native-Kolibri import gate (sec.4.3), the dashboard LIVE update/install
gate and the Get-More base-Maps gate (sec.4), all device-verified on a metered hotspot
except the last two (pending a device pass). Every REST-heavy egress the user can
trigger on a live box now routes through the gate: ZIM, Books, Kolibri (Get More +
native), FQR regions, dashboard live update, and Get-More base maps.

K2GO-404 (first PR) extends the gate to the non-REST egress:
- Rootfs install (aria2, rootfs image + proot-distro base): gated at the UI commit
  `SetupLibraryActivity.startWizardInstall`, wrapping the whole commit (marker + service
  + navigation) so a decline plants no InstallGuard marker. Auto-retry / resume stay
  headless via `InstallService.onValidatedNetworkReturned`, not re-prompted.
- OTA APK (`UpdateController.startDownload`): prompt at the enqueue (an Activity is
  present, so a prompt beats a silent `setAllowedOverMetered(false)`).
- Portal APK / PDF (`PortalActivity`): NOT gated -- the WebView `DownloadListener` only
  serves LOCAL box files (internal host; external downloads are ignored), so they are
  never metered internet egress. Gating them would false-alarm or block a local download.
- `hasInternet` fold: `AndroidNetworkClassifier.hasInternet` and `hasValidatedInternet`
  (the latter preserves `NET_CAPABILITY_VALIDATED`) are now the single reader;
  `DashboardRebuild.hasInternet` and `InstallService.hasValidatedInternet` are removed and
  their callers routed. One behavior delta: a null ConnectivityManager now reads as no
  internet (was "unknown -> true"), an edge effectively never hit; failing closed is safe.
- Proot module install (runrole, in-proot Ansible fetch): prompt at the commit
  `SetupLibraryActivity.openModuleIndex` (like rootfs/maps, gate at the START; the in-proot
  fetch is not device-side and cannot be measured). A decline leaves the modules banked --
  the module wishlist's "deferred is not a failure" contract. PARTIAL by design: unlike the
  REST content streams, the module drain has no headless `ContentAdmission`-style cost hold,
  and `SetupProgressActivity.orchestrateStep` drains a banked module from any entry that
  opens that screen (e.g. a Kolibri metered decline still navigates there). Closing that
  needs a non-terminal "waiting for network" state in the orchestrator (a `MapsProvisioner`/
  `ModuleProvisioner` drain refusal is terminal today, `moduleStartFailed`), tracked in
  K2GO-408. The residual is narrow (a checkbox-banked module reached via a non-module,
  un-consented path on metered) and, within a session, a metered consent already granted for
  any install covers it (`SessionMeteredConsentStore` is process-wide).

Wizard/system-install maps path (`mapsWizardConfirm`) -- RESOLVED as already covered, no separate
change (originally scoped as a second PR). The wizard only BANKS the maps selection; the base-maps
REST download runs later, during the same install, which starts ONLY via `startWizardInstall` --
gated in the first PR. That gate grants session-wide consent, so a metered install the user accepted
covers its maps sub-download too, and a decline never starts the install. The one residual -- start
on Wi-Fi, then switch to metered mid-install -- is in-flight best-effort by the sec.2.1 contract, and
the proactive alert already warns. A headless hold in the maps drain was considered and rejected: the
maps stage is a serialized proot step whose `MapsProvisioner.drain` refusal is terminal
(`mapsStartFailed`) and which pins the user on the progress screen (`prootActive`), so a "waiting for
network" state there is complex and worse UX for a case the design already covers ("prefer removing
over adding"). Nothing REST-heavy the user can trigger on a live box is now ungated.
(l10n done pending human review.)

## 6. Device evidence appendix (dark surfaces flattened)

Measured on Samsung SM-A165M (`RF8Y80CE2DA`), Android 15, SIM "Bienestar" LTE
(25 GB plan), via `adb shell svc wifi disable/enable` + `dumpsys connectivity`.

| Network | Transport | Has NOT_METERED? | Has INTERNET? | Classifier verdict |
|---|---|---|---|---|
| Wi-Fi "HIKVISION_B7FD" (home router) | WIFI | Yes (Metered hint: false) | Yes | UNMETERED |
| Cellular internet APN (rmnet1, default when Wi-Fi off) | CELLULAR | **No** | Yes | **METERED** |
| Cellular IMS PDN | CELLULAR | Yes | No (IMS only) | never the internet default |
| Phone hotspot "Galaxy A16 4AEC" (Samsung tether), seen by an OPPO CPH2557 client | WIFI | **No** (Metered hint: true) | Yes | **METERED** |

Conclusions, now empirical, not assumed:

1. Wi-Fi here is unmetered; the LTE internet APN is metered. The classifier rule
   (`NOT_METERED` on the active default) produces the right verdict for both.
2. "Cellular" is not monolithic: the IMS PDN carries NOT_METERED. A
   transport-based rule would have called the whole radio unmetered and leaked
   the plan. This is the concrete reason the rule keys on metered, not transport.
3. The active default flips correctly on Wi-Fi toggle (Wi-Fi network id when on;
   cellular id when off), so `getActiveNetwork()` is the right anchor.
4. A phone hotspot CAN be flagged metered natively: the Samsung A16 tether
   advertised the metered bit and the OPPO client's Wi-Fi network dropped
   NOT_METERED (Metered hint: true). The classifier read METERED with no special
   case -- the "Wi-Fi is not always free" case is caught by the same rule. Caveat:
   this is OEM/AP-dependent. A router or hotspot that does not advertise the bit,
   or a user who marks the Wi-Fi "unmetered", will read UNMETERED; the manual
   override and the proactive alert exist for exactly that residual gap.

## 7. Test protocol (remaining dark surfaces)

Run before shipping the full contract:

1. **Phone-hotspot metered detection (MEASURED -- Section 6, row 4).** Samsung A16
   tether -> OPPO CPH2557 client: the client's Wi-Fi network had Metered hint:
   true and no NOT_METERED, so the classifier read METERED with no special case.
   The residual case to keep in mind: an AP/router that does not advertise the
   metered bit, or a user who marks the Wi-Fi "unmetered", reads UNMETERED --
   covered by the manual override (future toggle) and the proactive alert, not by
   auto-detection. Re-run with other AP brands as they appear.
2. **Premise: server content download consumes the SIM.** With the box up and the
   device on cellular, start a small ZIM and watch `/proc/net/dev` `rmnet` bytes
   climb. Architecturally certain (rmnet is the only uplink; proot has no
   independent radio) -- measure once to confirm.
3. **DownloadManager over metered.** Enqueue with `setAllowedOverMetered(false)`
   on cellular; confirm it holds until Wi-Fi (and that the gate asks first, so the
   hold is a chosen fallback, not a silent stall).
4. **Callback latency.** Time `NetworkStateLiveData` firing after a transport flip
   to confirm the proactive alert is prompt.

## 8. Consequences

- Positive: one owner for cost policy; reuses the existing change callback;
  reduces (does not add) `hasInternet` duplication; empirically grounded rule.
- Cost: four seams still to wire; device verification per the protocol. (The UI
  strings are already translated to 33 locales, machine-generated, pending human
  review.)
- Deployment detail: the proactive alert posts a notification, so on Android 13+
  it needs the POST_NOTIFICATIONS runtime permission. The observer swallows the
  SecurityException when it is not granted, so the start gate (the primary cost
  protection) still works with no notification permission. If the app does not
  already request POST_NOTIFICATIONS elsewhere, the alert is silent until it does.
- Out of scope: fine control of in-flight transfers; a persisted "always allow";
  P2P (rsync clone -- LAN, no cost).

## 9. Alternatives rejected

- **Key on `TRANSPORT_CELLULAR`.** Rejected: the IMS PDN evidence shows cellular
  is not uniformly metered, and a metered Wi-Fi hotspot would be missed.
- **A per-transfer byte threshold ("ask only above N MB").** Rejected: a global
  threshold across every seam adds complexity for little gain. The model is
  binary consent. A small-payload exemption (< ~1 MB) may arrive later, per seam,
  not as a global rule.
- **Block at the socket / bind the process to Wi-Fi.** Rejected: cannot cover the
  in-proot server's egress, and would fight `WifiNetworkBinder` (LAN sync).

## 10. Open design questions (from the code-review second pass)

The reference wiring gates the immediate commit point. The two-pass review found
that this alone is not fully defensive, because the wishlist is a durable queue
drained by an UNGATED background pass. These must be resolved before the feature
is complete:

1. **The commit point both banks and drains.** `SetupLibraryActivity.startZimDownload()`
   calls `ZimWishlist.add(cart)` (the durable queue) and then `ZimProvisioner.drain()`.
   Wrapping the whole method means a BLOCKED (offline) or declined start also skips
   the banking, so the selection is lost instead of queued. Offline is not a cost
   decision -- it should still bank for a later drain. Fix direction: bank
   unconditionally; gate only the drain.

2. **Banked items drain ungated.** `ZimProvisioner.drain` runs every ~2 s from
   Home/SetupProgress and starts the real download with no gate (by design, to
   avoid re-prompting). So any banked item downloads on whatever network is
   active. The wizard-bank path (`zimWizardConfirm`, `banks == true`) banks
   without ever passing the gate, so those ZIMs can download on metered data with
   no consent. Gating the UI commit point does not cover them.

3. **Consequence:** to be truly defensive the provisioner drain must be
   consent-aware -- HOLD a banked item on metered-without-consent instead of
   downloading, and surface the consent prompt when an Activity is next
   foreground (the drain itself has no UI). The proactive alert only informs; it
   does not hold the transfer. This is the real depth of the feature and should
   be designed before wiring the remaining seams, not after.

### Resolution (implemented)

Enforcement moved to the single choke every content drain already consults:
`ContentAdmission.canStart` (system/data) -- "the one answer to may a REST content
stream start now". It now also defers when `NetworkCostAdmission.allowsHeavyStartNow`
is false (metered without consent), so ZIM, Books and Kolibri drains all HOLD a
banked order rather than spend mobile data -- the commit point, the wizard-bank
path and every background re-drain, covered in one place, with no per-provisioner
edit and no new persistent state (a held order is simply left banked, the existing
"deferred is not a failure" contract).

`NetworkCostAdmission` (networkpolicy/data) is the single source of the classify +
consent + policy decision, used both by that headless admission and by the UI gate.

The prompt stays at the UI commit point (`NetworkPolicyGate`), but banking now
happens BEFORE the gate (`SetupLibraryActivity.startZimDownload` banks, then gates
the drain), so a declined or offline order is queued, not lost. This removes the
commit-point special case rather than adding one.

The ZIM, Books and Kolibri commit points now bank-then-prompt. The Get-More base-Maps
download (K2GO-394 moved its bytes onto REST) is gated at its UI commit
(`openMapsIndex`), NOT at `MapsProvisioner.drain`: unlike the content drains, the maps
drain is a serialized proot stage whose refusal is TERMINAL (`mapsStartFailed`), so a
cost-hold there would read as a hard failure, not a deferral. The wizard/system-install maps
path is covered instead by the install gate (`startWizardInstall`, sec.5), so no maps-drain
hold is added at all. The dashboard LIVE update/install is gated at its UI commit (`startRest`). Both are
user-driven Operations, so they gate at the UI (like FQR), not through `ContentAdmission`
(which defers TO them -- routing back would be circular).

Done in K2GO-404 (first PR): the rootfs-image aria2 install and the OTA DownloadManager, both
gated at their UI commits; the two-`hasInternet` fold. Portal downloads are exempt (local box
files). The wizard/system-install maps path is resolved as covered by the install gate (sec.5).
The metered cost-consent feature is complete: every REST-heavy egress the user can trigger on a
live box is gated.
