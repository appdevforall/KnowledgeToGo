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
| Maps | `MapsConfirmFragment.java:90` -- the live `else` branch (~:99), NOT the `wizard` branch | wrap the live-download body |
| Rootfs/modules install | `InstallService` started at `SetupProgressActivity.java:1401`; commit point is the wizard "Install" confirmation | wrap that confirm before starting InstallService |

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

## 5. Reference implementation status (this change)

Landed as a compiling, tested starting point for the implementer:

- Domain, pure JVM, unit-tested: `NetworkClass`, `NetworkPolicy`,
  `NetworkTransition`, `NetworkPolicyDecision`, `MeteredConsentStore`
  (`NetworkPolicyTest`, `NetworkClassTest`, `NetworkTransitionTest` -- green).
- Data: `AndroidNetworkClassifier`, `SessionMeteredConsentStore`.
- Presentation: `NetworkPolicyGate` (+ `BrandDialog` consent), `MeteredNetworkObserver`.
- Wiring: observer started in `IIABApplication`; gate wired at the ZIM commit
  point (`ZimConfirmFragment`).
- Strings translated to all 33 locales (machine-generated, pending human review)
  in `values*/strings_networkpolicy.xml`; `strings_untranslated.xml` is clear.

Remaining to finish the contract: the other four seams and the two-way fold of the
existing `hasInternet` readers into the classifier. (l10n is done pending review.)

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
