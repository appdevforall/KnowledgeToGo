# Device-to-device connectivity for KnowledgeToGo — options, mechanisms, and decision

Engineering note. 2026-08-19. Ticket: ADFA-5197 — preserve hotspot name/password across
restarts. Context: ADFA-4520 (LocalOnlyHotspot for SIM-less devices). Status: draft for review.

## TL;DR

The goal is a join experience that stays stable across sessions and, ideally, a credential
someone can print once and post on a wall. This note maps how to get there.

The clearest way to reach it is to **move the access point off the phone**: a low-cost router
holds a fixed SSID + passphrase, the K2Go phone joins it as a client and serves content on that
segment, and readers join normally. Print the QR once; it never changes across restarts or
updates

The complement is to reach the phone **by name, not by IP** — e.g.: `k2go.local` the app advertises its service with
NSD/mDNS, a standard, unprivileged API, so a DHCP address that changes between sessions doesn't
matter. That is Option E, the recommendation wherever a little hardware can be deployed — and note
the inversion: the platform reserves naming the *hotspot*, but hands us naming the *service*.

On the phone itself, Android reserves Wi-Fi AP naming to system components: LocalOnlyHotspot,
SoftAP and Wi-Fi Direct all hand the app a system-generated SSID + passphrase that a normal app
can read but not choose, and LocalOnlyHotspot regenerates them on every start. That is a design
boundary of the platform, so the sections below walk each on-phone path, show how far it gets,
and land on moving the AP off the device as the way the fixed-credential goal is actually met.
Where no hardware is available, today's LocalOnlyHotspot + on-screen QR keeps working as the
fallback — the credential rotates, but the join stays quick.

## 1. Problem statement

- The onboarding QR is different every session; nothing can be printed, laminated, or posted.
- Support/training material cannot reference a stable network name.
- In large venues, every host-app restart invalidates what clients already learned.

Goal: a join experience stable across sessions, without per-device manual entry, ideally a
fixed printable credential.

## 2. The hard constraint that shapes everything

**Client and host devices belong to end users, not to the organization.** K2Go ships as an
app, not a managed fleet. Any option requiring factory reset, enterprise provisioning, or
administrative ownership of the handset is out of scope in practice — this alone eliminates
Option C below, otherwise the only sanctioned way to pin SoftAP credentials.

## 3. How Android gates AP configuration (the platform boundary)

Wi-Fi is layered: the app calls public `WifiManager`/`WifiP2pManager`, which IPC (Binder) into
`system_server`, where `WifiServiceImpl`/`ConnectivityService` enforce permissions by UID
before touching the native daemons (`wpa_supplicant` for client/P2P, `hostapd` for SoftAP).
Naming/securing an AP must pass that server-side check. Since Android 7 the AP-config path is
UID/permission-checked there (throwing `SecurityException` "App not allowed to read or update
stored WiFi Ap config"), which is why the Android 4–6 reflection tricks stopped working.
Enforcement is server-side, not evadable from the app process; API 28+ adds the hidden-API
greylist as a second block.

## 4. Options at a glance

| Option | Credential stability | User friction | OEM risk | Concurrency | Cost | Verdict |
|---|---|---|---|---|---|---|
| A — LOHS + on-screen QR | None (rotates) | Low, repeats each session | Medium | Low | Zero | **Fallback** |
| B — Wi-Fi Direct (P2P) | None (system-generated) | Low in theory | High | Poor | Zero | Rejected |
| C — Device Owner + `setSoftApConfiguration` | Full | Prohibitive (factory reset) | Low | Good | High (provisioning) | Rejected for our model |
| D — Wi-Fi Aware (NAN) | N/A (no SSID) | Very low | Irregular HW | Poor for fan-out | Zero | Not primary |
| E — External AP, fixed creds | **Full** | Lowest (printed QR) | Removed | **Best** | Hardware/site | **Recommended** |

## 5. Options in detail (with the API mechanism folded in)

### Option A — Status quo: LocalOnlyHotspot + on-screen QR
`startLocalOnlyHotspot(callback, handler)` returns a **random, read-only** SSID + WPA2
passphrase. The custom-config overload (`startLocalOnlyHotspot(SoftApConfiguration, …)`) is
`@SystemApi`, gated behind `NETWORK_SETTINGS`/`NETWORK_SETUP_WIZARD`; `NEARBY_WIFI_DEVICES`
alone does not unlock it. So the app can read but never set or pin the credentials, and they
regenerate each start.

Today: `LocalHotspotManager.start()` (`controller/.../hotspot/LocalHotspotManager.java:122-144`)
uses the public overload and reads `getSoftApConfiguration()` (API 30+) / `getWifiConfiguration()`
below — the whole supported surface, hence the QR handoff.

Pros: works now; no hardware; no extra permissions; internet-free by design.
Cons: not printable; host screen must be visible/reachable (doesn't scale in a big room);
unstable for repeat visits.

### Option B — Wi-Fi Direct (Wi-Fi P2P)
`createGroup()` makes the device an Autonomous Group Owner that behaves on-air like a WPA2 AP,
and legacy clients can join with name+passphrase (no P2P logic client-side). **But it does not
solve the stated problem**: on our SDK floor `createGroup` takes no config, so the SSID
(`DIRECT-xy-…`) and passphrase are still system-generated (the config overload arrived API 29).
Persistent groups *tend* to retain credentials across sessions, but this is empirical and
OEM/version dependent — not a contract. Behavior lives in vendor firmware/HAL (discovery
reliability, interaction with an active Wi-Fi connection, client caps), and one GO fanning out
to many readers over one radio degrades fast.

Verdict: doesn't meet the goal, and reintroduces the OEM fragmentation Option E avoids. Not used
in the app today.

### Option C — Device Owner + `setSoftApConfiguration()`
`setSoftApConfiguration()` **can** pin the SoftAP SSID/passphrase persistently, but it requires
`NETWORK_SETTINGS`/`NETWORK_SETUP_WIZARD` (signature). Clarification worth recording (it came up
as a misconception): **Device Owner ≠ handset manufacturer** — it's a device-management role any
app can hold, provisioned by QR/NFC/ADB, *but*: (1) only on a device with no accounts (out of box
or post-factory-reset); (2) one Device Owner per device, changing it needs another reset; (3) it
grants broad control of the handset.

To verify (does not change the verdict): whether a plain Device Owner actually gets
`setSoftApConfiguration` on our target API levels, or whether it still needs `NETWORK_SETTINGS`
(a signature permission a DPC does not automatically hold). Wi-Fi controls exist via
`DevicePolicyManager` on fully-managed devices, but the exact SoftAP-pinning path is API-level
dependent and unconfirmed.

Verdict: technically the cleanest fixed-credential path, viable **only** for organization-owned
devices. Asking end users to wipe their personal phone and hand K2Go admin control is not
realistic. Rejected for the current model; revisit if a managed-device pilot is funded.

### Option D — Wi-Fi Aware (NAN)
`WifiAwareManager` (Android 8+) discovers peers and opens point-to-point **IPv6 data paths** with
no network to join and nothing to scan — conceptually elegant for "just connect me." But: it is a
per-device **hardware** capability (`PackageManager.FEATURE_WIFI_AWARE`), must be queried at
runtime with a mandatory fallback; it is a P2P data-path model, **not an AP with fan-out**, so one
host serving many readers is a poor fit; throughput and concurrent-session counts are limited and
unproven for our payloads.

Verdict: not a primary path. Possibly an *opportunistic* discovery layer later, never the only one.

### Option E — External access point with fixed credentials *(recommended)*
Take the phone's radio out of the serving role. A low-cost router provides the network with a fixed
SSID + passphrase; the K2Go host phone joins it **as a client** and serves content on that segment;
readers join normally.

Pros:
- The QR is printed once and posted; never changes across sessions, reboots, or app updates.
- Coverage, antennas, and concurrent-client capacity all improve vs. a handset (consistent with the
  earlier OnePlus 7T vs. Hikvision Wi-Fi 6 comparison).
- Eliminates the whole class of OEM SoftAP quirks (client isolation, subnet variance, client caps).
- The client app can use `WifiNetworkSuggestion` / `WifiNetworkSpecifier` (API 29+) so the join is
  automatic once installed — fewer manual steps. (Not present in the app today; new work.)

Cons:
- Requires hardware + power per site — a real constraint for K2Go's contexts.
- The host phone's LAN address is DHCP-assigned; needs a DHCP reservation on the AP **or** mDNS/NSD
  service discovery so clients find the server without a hardcoded IP.
- Where no AP can be installed, it falls back to Option A, so **both paths must remain supported**.

### 5.1 Stable host address without a static IP (the IP-independent counter-offer)

Separating the AP from the phone raises one detail: the phone's address on that LAN is
DHCP-assigned and could change. The clean answer is **not to pin an IP at all** but to reach the
host by **name** — which turns the whole objection around, because naming a *service* is a
capability the platform hands to any app (unlike naming the *AP*, which it does not).

- **Service discovery (NSD / mDNS) — recommended.** The host advertises its content service with
  `NsdManager` (e.g. an `_http._tcp` instance named `k2go`); clients resolve it by name and connect
  regardless of the current IP. It survives DHCP changes, reboots and reconnects with nothing to
  reconfigure.
  - **Android versions:** `NsdManager` has existed since **API 16 (Android 4.1)** — well below our
    `minSdk 24`, so every supported device has it. Receiving multicast reliably on some devices needs
    a `WifiManager.MulticastLock` held while discovering, under `CHANGE_WIFI_MULTICAST_STATE` — which
    the app **already declares** (`AndroidManifest.xml`).
  - **AP requirements:** none special. mDNS is link-local multicast (224.0.0.251:5353), peer-to-peer
    on the LAN — the router runs nothing extra. It only needs the AP to **pass multicast and not have
    client (AP) isolation** enabled. An AP that isolates clients or filters multicast would break mDNS
    *and* direct client→host connections alike, so it is a setting to verify, not a new mechanism.
  - **Standard, and not gated.** mDNS / DNS-SD is an industry standard (RFC 6762 / 6763;
    Bonjour / Avahi / zeroconf), so iOS, laptops and IoT clients resolve the same name natively.
    And unlike SoftAP naming, NSD is a **public, unprivileged app capability** — no system signature,
    no special permission. Android's security model does not block it; the only thing that can
    suppress it is a network that filters multicast, which we control on our own AP. It is the inverse
    of the hotspot problem: the platform reserves AP naming for the system, but hands service naming
    to any app.

- **DHCP reservation — optional belt-and-suspenders.** If we control the router and also want a
  predictable IP (logs, a printed URL), bind the phone's MAC to a fixed lease. Caveat: Android
  randomizes the Wi-Fi MAC per SSID, but that MAC is **persistent while the phone remembers the
  network** (default `RANDOMIZATION_PERSISTENT`), so the reservation holds; forgetting and re-adding
  the network rotates the MAC and requires re-doing the reservation. Without any reservation, most
  routers still hand the same device the same lease while the network is remembered — sticky in
  practice, but not guaranteed.

- **What the app cannot do:** set its own static IP. Pinning `IpConfiguration.STATIC` on a joined
  network needs system / Device-Owner privileges — a third-party app cannot modify a network config
  it did not create (the old `WifiConfiguration` + reflection route was closed on Android 6+). The
  user can set it manually in Wi-Fi → Advanced → IP settings → Static, and the app can deep-link
  there, but not apply it silently.

## 6. Cross-cutting: reachability of `:8085` (separate from credentials)

A prior report of clients not reaching the server port is unrelated to the credential problem and
is likely local to our stack:

1. **Bind address — server-side, not Android.** Content is nginx inside the proot on `:8085`
   (`config/BoxEndpoints.java` → `http://localhost:8085`; `:4000` Node behind it; tier-3 docs on
   `:8114`). proot shares the host network namespace, so external reachability depends on nginx's
   `listen` in the rootfs (must be `0.0.0.0:8085`, not `127.0.0.1`). Check the rootfs nginx config,
   not the Android socket.
2. **Interface discovery — already done.** `NetworkInterfaces.discover()` enumerates up interfaces
   at runtime (`wlan0`=Wi-Fi; `ap*`/`swlan*`/`wlan1`/`wlan2`=hotspot) and returns the IPv4; it does
   not hardcode `192.168.43.x`. The only hardcode is a **fallback** `192.168.49.1` when the scan
   returns null (`ConnectFragment.java:201`, `CloneFragment.java:776`).
3. **Client isolation.** Some OEMs isolate clients on LOHS; many venue APs enable AP isolation by
   default. On a router we control (Option E) this is a verifiable, disableable setting.
4. **Full tethering vs. local-only.** Standard tethering routes client→host traffic normally; what
   is usually blocked there is *upstream* internet, not traffic to the host.

Action: confirm (1) in the rootfs nginx config before attributing any reachability failure to the
network mode; (2) is already implemented.

## 7. Addressing by mode (one place, since it varies)

| Mode | Host/gateway address | Notes |
|---|---|---|
| LocalOnlyHotspot | commonly `192.168.49.1` | AOSP convention; verify at runtime (done) |
| Wi-Fi Direct GO | `192.168.49.1` | fixed by the P2P spec's embedded DHCP |
| Full tethering (SoftAP) | commonly `192.168.43.1` | convention, **not** guaranteed — Samsung and others differ |
| External AP (Option E) | AP's DHCP range | needs DHCP reservation or mDNS/NSD for host discovery |

Rule: never hardcode the served URL's IP; enumerate the active interface (already the app's
behavior) or resolve via NSD.

## 8. Permissions

| Permission | Level | Where it applies |
|---|---|---|
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | normal | LOHS, P2P, joining an AP |
| `INTERNET` | normal | local socket stack |
| `ACCESS_FINE_LOCATION` | dangerous (runtime) | LOHS/P2P discovery on API ≤ 32 |
| `NEARBY_WIFI_DEVICES` | runtime | replaces location when **targeting API 33+** |
| `NETWORK_SETTINGS` / `NETWORK_SETUP_WIZARD` | signature/system | required to pin SoftAP/LOHS creds — not grantable to a normal app |

The app declares `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_FINE_LOCATION`, `INTERNET`
(`AndroidManifest.xml`) — enough at `targetSdk 28`. It does **not** declare `NEARBY_WIFI_DEVICES`
(needed if we adopt the P2P path or raise `targetSdk` to 33+).

## 9. Recommendation

1. **Primary — Option E** (external AP + fixed creds + printed QR) for any site where hardware can
   be deployed. The only option delivering a stable, printable credential under our model. Add
   `WifiNetworkSuggestion` auto-join + NSD/mDNS host discovery on the client side (new work).
2. **Fallback — Option A** (LOHS + on-screen QR) for ad-hoc / hardware-less sites. Accept rotation;
   optimize the scan.
3. **Rejected:** B (doesn't solve it, adds vendor risk), C (incompatible with user-owned devices),
   D (hardware too irregular to depend on).

For ADFA-5197: the stable, printable credential the ticket asks for is delivered by moving the AP
off the phone (Option E). On a user-owned phone the platform keeps AP naming with system
components, so where hardware isn't available we still meet the underlying need — a quick, reliable
join — through the on-screen QR, and put our effort into that flow rather than into a fixed name the
platform reserves for system components.

## 10. Verified vs. to-verify

Verified (primary sources + our code):
- LOHS custom config is `@SystemApi` / system-permission-gated; tethering config needs
  `TETHER_PRIVILEGED`; reflection blocked since Android 7; P2P `createGroup` config overload is API
  29; `WifiP2pGroup.getPassphrase()` is owner-only.
- The app is LOHS-only, reads random creds, and already does runtime interface discovery
  (`NetworkInterfaces`); content server is nginx-in-proot on `:8085`; no `WifiNetworkSuggestion`/NSD.

To verify before sign-off:
- [ ] Confirm nginx `listen 0.0.0.0:8085` in the rootfs (bind-address check — server-side, not Android).
- [ ] Measure real concurrent-client degradation on our reference handsets (don't cite secondhand).
- [ ] Confirm exact `setSoftApConfiguration()` privilege per API level vs. AOSP, for any managed-device pilot.
- [ ] Evaluate `WifiNetworkSuggestion` auto-join UX on Android 10–16 for the Option E client flow.
- [ ] Device-test P2P persistent-group credential stability across target OEMs (only if B is ever reconsidered).
- [ ] Spec the reference AP: cost, power, mounting, provisioning per site.
- [ ] Confirm NSD/mDNS host discovery on our reference APs (multicast passed, no client isolation);
      keep a DHCP-reservation / printed-IP fallback for APs that filter multicast.

## Sources

- [Use a local-only Wi-Fi hotspot — Android Developers](https://developer.android.com/develop/connectivity/wifi/localonlyhotspot)
- [Wi-Fi Direct (P2P) overview — Android Developers](https://developer.android.com/develop/connectivity/wifi/wifip2p)
- [WifiP2pGroup — Android Developers](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pGroup)
- [WifiP2pConfig.Builder (API 29) — Android Developers](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig.Builder)
- [Wi-Fi Aware — Android Developers](https://developer.android.com/develop/connectivity/wifi/wifi-aware)
- [WifiNetworkSuggestion — Android Developers](https://developer.android.com/reference/android/net/wifi/WifiNetworkSuggestion)
- [Use network service discovery (NsdManager) — Android Developers](https://developer.android.com/develop/connectivity/wifi/use-nsd)
- [Networking and telephony — Android Enterprise (DPC)](https://developer.android.com/work/dpc/network-telephony)
- [Wi-Fi hotspot (Soft AP) — Android Open Source Project](https://source.android.com/docs/core/connect/wifi-softap)
- [Tethering — Android Open Source Project](https://source.android.com/docs/core/ota/modular-system/tethering)
