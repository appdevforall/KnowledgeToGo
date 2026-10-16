# ADR: AP-IP resolution: one producer, one terminal

Status: Accepted (K2GO-471)

## Context

When a user shares the library (Connect or Clone), the app shows a QR code that
carries the server URL `http://<AP-IPv4>:8085`. That IPv4 is the device's address
on the local access point it just raised. The app must resolve it before it can
draw the QR.

One fact: "the IPv4 that serves the dashboard on the local AP". The CONSUMER side
was already unified: Connect and Clone both read `LocalHotspotManager.State.hotspotIp`
(K2GO-375). The PRODUCER side was not. Two hotspot mechanisms each resolved the
address their own way:

- Wi-Fi Direct (K2GO-127): reads `NetworkInterface.getByName(group.getInterface())`,
  polls about 10 s, and has a terminal (it fails over to LocalOnlyHotspot).
- LocalOnlyHotspot (fallback): scans every interface with a name/site-local
  heuristic (`NetworkInterfaces.discover()`), polls about 2 min, and had NO
  terminal: on exhaustion it returned silently, leaving `State` at ON with a null
  IP forever, so the UI stayed on "Finding hotspot address..." with no failure and
  no retry.

A third producer existed historically: a hardcoded `192.168.49.1` guess, removed in
K2GO-375 because that address does not exist on some devices (OnePlus / LineageOS).

This is the "N states compensating for a missing owner" failure mode: one fact,
several unaligned producers, different timeouts, different heuristics, different
terminals. It is latent on every device and becomes visible on low-end / Android Go
hardware (K2GO-465), where slow interface bring-up blows the poll budgets and the
Android 11+ restrictions on `java.net` interface enumeration can hide the AP address
from the app entirely.

## Decision

The AP IPv4 has ONE producer and ONE terminal.

- One decision. `hotspot/domain/ApAddress.pick(candidates, hint, wifiIp)` is the
  single rule for "which candidate IPv4 is the AP". It is pure (no `android.*`) and
  unit-tested. Every candidate source funnels through it. Device-specific leaf cases
  (K2GO-465) are added here as new candidates and new test rows, never as a second
  resolver.
- One producer. `hotspot/data/ApAddressResolver` gathers candidates from the
  framework-authoritative source first (`ConnectivityManager` + `LinkProperties`,
  which is not subject to the raw `java.net` enumeration limits) and then from the
  `java.net` scan, and hands them all to `ApAddress`. Both the Wi-Fi Direct path and
  the LocalOnlyHotspot path call this one resolver.
- One terminal. Every IP poll ends in a definite state. If the address does not
  resolve within budget, the manager publishes FAILED
  (`REASON_ADDRESS_UNRESOLVED`); the UI shows a plain message and a retry. No path
  leaves the user on an endless placeholder.
- No silent guess. The dynamic calculation stays (it is correct). A fixed address
  such as `192.168.49.1` is never a silent default; if ever used it is a last
  resort, logged, and reachability-checked.
- Capability and degradation are explicit. "Wi-Fi Direct unsupported" and "address
  unresolved" are first-class outcomes with defined next steps
  (Wi-Fi Direct -> LocalOnlyHotspot -> shared Wi-Fi -> terminal).

## Consequences

- The primary and the fallback paths are both correct on their own. If the device
  ever falls back, the fallback can no longer hang: it resolves through the same
  producer and ends in the same terminal.
- The "which IP" heuristic lives in one tested place, so tuning it for a specific
  device is a test row plus a small edit, not a new code path.
- The framework-authoritative source makes resolution work where the raw
  `java.net` scan is restricted (Android 11+, Android Go), which is the most likely
  agnostic fix for the low-end failure in K2GO-465.
- This ADR is device-agnostic on purpose. Device-specific fixes are a follow-up
  keyed off K2GO-465, once the device farm provides `ip -4 addr` and logcat for the
  failing phone.
- This closes the hotspot-IP concern that K2GO-375, K2GO-127, K2GO-439 and K2GO-470
  each touched in turn. The first landed piece is the single producer and terminal
  (K2GO-471).
