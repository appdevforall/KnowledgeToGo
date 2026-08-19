# Controlling hotspot credentials on Android: what a non-system app can and cannot do

Engineering research note. 2026-08-19. Ticket: ADFA-5197 — preserve hotspot name/password
across restarts. Context: the ADFA-4520 LOHS family.

## TL;DR

A regular app (no root, not platform-signed, no privileged install) **cannot set the SSID
or password** of the hotspot it raises — neither the Local-Only Hotspot (LOHS) nor the
conventional tethering / SoftAP. Those knobs are gated behind system-signature permissions.
K2Go already lives with this: `LocalHotspotManager` starts a LOHS and only **reads** the
system-generated SSID/passphrase, then hands them off by QR.

If the goal is a **stable, self-managed local Wi-Fi that the host app controls** (predictable
enough that clients can reconnect), the only path that stays inside the public SDK and the
permission model is **Wi-Fi Direct running as an Autonomous Group Owner** (`WifiP2pManager.createGroup()`).
On our SDK floor it still cannot *pre-set* the SSID/passphrase (that overload arrived in API 29),
but the credentials it generates can be read back and, via persistent groups, tend to remain
stable across sessions — with the caveat that this stability is empirical, not contractually
guaranteed, and must be device-verified before we rely on it.

This note is the backing for saying "no, we can't rename/secure the LOHS ourselves," and for
scoping the Wi-Fi Direct alternative if a stable local AP becomes a requirement.

## Scope and the app's real SDK range

The relevant constraint is: no root, no platform signature, no Google Play Services dependency.
The app is `minSdk 24 / targetSdk 28 / compileSdk 34` (`controller/app/build.gradle`). LOHS is an
API-26 feature, so `LocalHotspotManager.isSupported()` gates on `Build.VERSION.SDK_INT >= O`; on a
true Android 7.0 (API 24) device LOHS does not exist at all. So statements below are qualified by
API level rather than framed as "API 24 only".

## How Android gates network configuration

Wi-Fi is layered: the app calls the public `WifiManager` / `WifiP2pManager` in the app process,
which IPC (Binder) into `system_server`, where `WifiServiceImpl` / `ConnectivityService` enforce
permissions by UID before talking to the native daemons (`wpa_supplicant` for client/P2P, `hostapd`
for SoftAP). Any attempt to name or secure an AP must pass that server-side check. Since Android 7,
the AP-config path is explicitly UID/permission-checked there — which is why reflection tricks that
worked on Android 4–6 stopped working (details below). Enforcement is server-side in `system_server`,
not something the caller can evade from the app process; on API 28+ the hidden-API greylist adds a
second block to the reflected symbols.

## Local-Only Hotspot (LOHS): read-only credentials

The public entry point is `WifiManager.startLocalOnlyHotspot(callback, handler)`. The system picks a
random SSID and a high-entropy WPA2 passphrase and hands them to the app through the reservation. The
app can read them but not choose them:

| Capability | Permission required | Feasible without root / platform signature |
|---|---|---|
| Read the generated SSID / passphrase | none (public callback) | Yes — this is what the app does |
| Set a custom SSID | `NETWORK_SETTINGS` | No — signature-level, OEM/platform only |
| Set a custom passphrase / BSSID | `NETWORK_SETUP_WIZARD` | No — reserved for setup/provisioning apps |
| Pin / reuse the same credentials next time | n/a | No — regenerated per reservation |

A custom-config overload exists (`startLocalOnlyHotspot(SoftApConfiguration, executor, callback)`) but
it is `@SystemApi` (absent from the public SDK) and still requires `NETWORK_SETTINGS` /
`NETWORK_SETUP_WIZARD`; `NEARBY_WIFI_DEVICES` alone does not unlock the custom-config path for a normal
app. The official developer guide only documents the no-config overload. Net: for K2Go, LOHS SSID and
password are **read-only and non-stable** (they change each time the hotspot comes up).

How we use it today: `LocalHotspotManager.start()` (`controller/.../hotspot/LocalHotspotManager.java:122-144`)
calls the public overload and, in `onStarted`, reads `getSoftApConfiguration()` (API 30+) or
`getWifiConfiguration()` below it. That is the entire supported surface — hence the QR handoff.

## Conventional tethering / SoftAP: also system-only

The historical Android 4–6 approach set a `WifiConfiguration` and used reflection to call hidden
`setWifiApConfiguration` / `setWifiApEnabled`. Android 7 closed this: `WifiServiceImpl` now checks the
caller's UID/permission and throws `SecurityException` with "App not allowed to read or update stored
WiFi Ap config" for unprivileged callers, and the enable path requires `TETHER_PRIVILEGED`.

| Permission | Protection level (API 24+) | Verdict |
|---|---|---|
| `CHANGE_WIFI_STATE` | normal (auto-granted) | Insufficient — controls the client, not SoftAP config |
| `WRITE_SETTINGS` | appop / signature | Insufficient — does not pass the AP-config check |
| `TETHER_PRIVILEGED` | signature / system | Required to start/stop/config tethering — OEM/root only |

`WifiManager.setWifiApConfiguration` / `startTethering` are therefore not usable by a distributed APK.
"Not possible for a non-system app" — not "impossible" in the absolute sense (a platform-signed or
rooted build could).

## Fallback for routed tethering: delegate to the Settings UI

If routed tethering (clients reaching the internet via the phone's WAN) is a hard requirement, the only
user-space-respecting option is to send the user to the system UI with an `Intent`:

- Universal: `Settings.ACTION_WIRELESS_SETTINGS`.
- Deep-link (fragile across OEMs): explicit component `com.android.settings.TetherSettings`, wrapped in
  a try/catch that falls back to `ACTION_WIRELESS_SETTINGS` on `ActivityNotFoundException`.

The user then sets or reads the SSID/password manually. This is a UX regression (multi-step, error-prone)
but it is the only framework-legal route to a *routed* hotspot without system privileges.

## The viable self-managed path: Wi-Fi Direct Autonomous Group Owner (AGO)

For a private, internet-isolated local network the app can host without system permissions, use Wi-Fi
Direct (`android.net.wifi.p2p.WifiP2pManager`). Calling `createGroup(channel, actionListener)` forces the
device to become the **Group Owner** immediately, skipping role negotiation — an Autonomous Group Owner.
An AGO behaves on the air exactly like a WPA2-PSK SoftAP: it beacons, authenticates clients, and routes
local frames.

- On our SDK floor, `createGroup(channel, actionListener)` takes **no** configuration — the SSID
  (`DIRECT-xy-...`, per the Wi-Fi Direct spec) and WPA2 passphrase are auto-generated by `wpa_supplicant`.
- The overload that lets you pre-set name/passphrase (`createGroup(channel, WifiP2pConfig, actionListener)`
  built via `WifiP2pConfig.Builder.setNetworkName()/setPassphrase()`) was added in **API 29 (Android 10)**.
  So pre-setting credentials is available only when running on API 29+.
- **Legacy client compatibility:** the Android docs state that legacy Wi-Fi clients join an AGO with the
  network name + passphrase like any WPA2 AP — no P2P logic on the client side. This is the key property:
  the host runs P2P internals; iOS, laptops, IoT devices just see a normal Wi-Fi network.

This is a **proposal, not current code** — K2Go has no `WifiP2pManager` usage today (LOHS only). Adopting
it is new work.

## Credential stabilization via persistent groups — with a caveat

Wi-Fi Direct persistent groups serialize the group's credentials (SSID, passphrase, band) so a device can
re-form the same group later; on AOSP this lives in the on-device supplicant config. In practice, on many
devices, re-creating the group reuses the stored credentials, giving stable SSID/passphrase across sessions
and reboots until the user clears "remembered" P2P groups or does a network reset.

Caveat (corrected from the source material): this stability is **empirical and OEM/version dependent**, not
a documented contract for the auto-generated AGO SSID. `createGroup()` without config does not guarantee the
same SSID on every device. **Before relying on "stable credentials," device-test the target hardware** (form
group, read creds, tear down, re-form, confirm identical) across the OEMs we ship to.

## Reading the credentials back

Credentials the host generated must reach clients (e.g. rendered as a QR, the pattern we already use):

1. Register a `BroadcastReceiver` for `WIFI_P2P_CONNECTION_CHANGED_ACTION`.
2. On connection, call `WifiP2pManager.requestGroupInfo(channel, listener)`.
3. From the returned `WifiP2pGroup`: `getNetworkName()` → SSID, `getPassphrase()` → WPA2 key.
   `getPassphrase()` returns non-null **only on the Group Owner** (clients get null — a deliberate
   anti-leak). So the host app, being the GO, can read and display them.

## Topology notes (why this fits a "local only" model)

- **MAC randomization:** the P2P interface uses a randomized/virtual MAC; do not build security on static
  MAC allow-lists.
- **Addressing:** the GO runs an embedded DHCP on the P2P interface with gateway **192.168.49.1** (clients get
  192.168.49.x). Note: LOHS on AOSP commonly uses the same 192.168.49.1 gateway, which is why the app's
  hardcoded fallback works today (`ConnectFragment.java:201`, `CloneFragment.java:776`).
- **No WAN bridging:** a P2P GO does not NAT the phone's mobile data to clients — the network is internet-isolated.
  That is a feature for a "local only" use case, not a bug.

## Permissions matrix (Wi-Fi Direct path)

| Permission | Level | Why |
|---|---|---|
| `ACCESS_WIFI_STATE` | normal | query radio state |
| `CHANGE_WIFI_STATE` | normal | form / remove P2P groups |
| `INTERNET` | normal | local socket stack |
| `ACCESS_FINE_LOCATION` | dangerous (runtime) | required for P2P discovery on API ≤ 32; without it P2P callbacks return empty/permission errors |
| `NEARBY_WIFI_DEVICES` | runtime | replaces the location requirement when **targeting API 33+** |

The app currently declares `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `ACCESS_FINE_LOCATION`, `INTERNET`
(`AndroidManifest.xml`) — enough for the LOHS path at `targetSdk 28`. It does **not** declare
`NEARBY_WIFI_DEVICES`; that becomes necessary if we adopt the P2P path and/or raise `targetSdk` to 33+.

## Lifecycle

The native daemon state is decoupled from the app's GC. A P2P host must call
`WifiP2pManager.removeGroup(channel, actionListener)` when the service ends or the app is backgrounded,
or the group keeps beaconing ("ghost" AP) and can wedge the radio. Confirm teardown via the action listener.

## Recommendation

- **Keep LOHS + QR handoff** as the default. Setting a fixed/predictable LOHS SSID/password is not achievable
  for a regular app on stock Android — this note is the backing for that "no."
- **If a stable, host-controlled local AP becomes a requirement**, prototype the Wi-Fi Direct AGO path
  (`createGroup` → `requestGroupInfo` → display creds), and — before committing — device-test the persistent-group
  stability and legacy-client join across our target OEMs. On API 29+ we can also pre-set the credentials outright.
- A **managed/kiosk (Device Owner)** deployment is the only route to a system-controlled hotspot, and is a
  different class of project.

## Verified vs. to-verify

- **Verified (primary sources + our code):** LOHS custom config is `@SystemApi` / system-permission-gated;
  tethering config needs `TETHER_PRIVILEGED`; reflection blocked since Android 7; `createGroup` config overload
  is API 29; `getPassphrase()` is owner-only; gateway 192.168.49.1; the app is LOHS-only and reads random creds.
- **To verify on device:** persistent-group SSID/passphrase stability across our target OEMs and Android
  versions; legacy-client join behavior on the specific client devices we care about.

## Sources

- [Use a local-only Wi-Fi hotspot — Android Developers](https://developer.android.com/develop/connectivity/wifi/localonlyhotspot)
- [WifiManager.LocalOnlyHotspotCallback — Android Developers](https://developer.android.com/reference/android/net/wifi/WifiManager.LocalOnlyHotspotCallback)
- [WifiP2pManager — Android Developers](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager)
- [WifiP2pGroup — Android Developers](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pGroup)
- [WifiP2pConfig.Builder (API 29) — Android Developers](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig.Builder)
- [Wi-Fi Direct (P2P) overview — Android Developers](https://developer.android.com/develop/connectivity/wifi/wifip2p)
- [Wi-Fi hotspot (Soft AP) — Android Open Source Project](https://source.android.com/docs/core/connect/wifi-softap)
- [Tethering — Android Open Source Project](https://source.android.com/docs/core/ota/modular-system/tethering)
- [WifiServiceImpl.java — AOSP (AP-config permission check)](https://android.googlesource.com/platform/frameworks/opt/net/wifi/+/a8d5e40/service/java/com/android/server/wifi/WifiServiceImpl.java)
- [Configuring Android's LocalOnlyHotspot (SSID/BSSID) — Mike Dawson, Medium (secondary)](https://medium.com/@mike_21858/configuring-androids-localonlyhotspot-5ghz-defining-ssid-bssid-and-more-ef4e4975e7b4)
