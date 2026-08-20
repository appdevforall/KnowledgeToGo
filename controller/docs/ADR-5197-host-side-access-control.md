# ADR-5197 — Host-side access control lives in the K2Go server, not the Wi-Fi

Status: Proposed (ADFA-5197; derived work, hangs off Epic ADFA-1028). **Scope: model +
guidance, no refactor.** Complements the design note `device-to-device-connectivity.md`,
which covers the client/discovery half; this record fixes the host half — being findable,
seeing who connects, and refusing someone — and, above all, *at which layer* that is even
possible.

## Context

Connect lets a nearby device browse this device's library. The device is **always the
host** of its own library, so the host controls are not an optional mode — they are the
other half of the same screen. A host reasonably wants to:

- be **discoverable or not**, like Bluetooth visibility;
- **see who is connected** and what they are doing;
- **refuse a specific device** even when it holds the shared passphrase;
- **cap** how many devices browse at once (a crowded hotspot competes for the same radio
  and the same little server);
- **stop sharing** outright.

The tempting mental model is "administer the Wi-Fi": see the associated stations and kick
one. That model does not hold on Android.

**Verified limitation (Android public API).** `WifiManager.startLocalOnlyHotspot()` returns
an SSID/passphrase and nothing else — no list of associated clients and no method to
disconnect one. The information does exist through `SoftApCallback`, but that path requires
the privileged `NETWORK_SETTINGS` permission (signature/system), which a normal app cannot
hold. An **external AP / router** (Option E in the design note) is owned by neither the app
nor the user, so there is even less to reach. **Conclusion: client management at the Wi-Fi
layer is not available to K2Go — with LocalOnlyHotspot or with a shared router.**

What K2Go *does* own is the server. The library is served by the in-box REST engine
(`nginx :8085 → dash-node :4000`, `/k2go-api`); every browse is an HTTP conversation the
app fully controls. So the admin the host wants is buildable — just one layer up from where
intuition puts it.

## Decision

1. **Discoverability is an mDNS/DNS-SD concern, and it is not a security control.** The
   host toggles whether it advertises its `_http._tcp` service (register / unregister). Off
   = absent from every peer's "Find a device" list. But the server stays reachable by IP and
   by a name someone already saved, so hiding must be presented as *"stop appearing in the
   list"*, never as *"nobody can reach me"*. The UI must not imply privacy it cannot deliver.

2. **Access control lives in the K2Go server — never in the Wi-Fi — and there is one
   enforcement point.** The REST layer is the single place that decides who is served. It
   owns: a **connection ledger** (who is browsing, by source IP, matched to a friendly name
   only when the peer advertises one); a **block / allow-list** per device; a
   **max-concurrent-clients** cap that refuses new connections past the limit; an **access
   mode** — *Anyone with the link* vs *Approve each device*; and a **kill switch** ("Stop
   sharing" = stop the server). None of this is re-derived from Wi-Fi state.

3. **Peer identity has two layers, and only the display layer is free-form.** The name shown
   to others is the DNS-SD **service instance name** (UTF-8, e.g. "Peter device"); the
   `.local` **hostname** is a sanitized LDH id **auto-derived** from it (lower-cased, first
   token, letters/numbers/dashes — "Peter device" → `k2go-peter.local`). A *connecting*
   client is matched to a name only if it also advertises; otherwise the ledger shows its IP
   as "Unknown device".

4. **Every enforcement marker has an owner and a lifetime.** A block or an allow-list entry
   must say who sets it, who clears it, and what happens on process death — a persistent
   block that nobody clears is the "marker nobody removes blocks the flow forever" failure
   mode ADR-5061 already warns about. **Default: session-scoped** (cleared when sharing
   stops), unless a durable list is explicitly justified.

## Options considered — how to enforce a block/limit at the server

| Option | What it is | Verdict |
|--------|-----------|---------|
| **A — App-layer allow/deny + counter in the REST router (chosen primary)** | The router checks the source against a block set and a live client count before serving. | In-process, cheap, unit-testable, identity-aware when paired with the DNS-SD name. The natural home for the UX-level admin. |
| **B — Per-session token / capability link** | The QR/link carries a token; unknown or revoked tokens are refused. | Best fit for *Approve each device* and for revocation ("kick" = revoke the token). Complements A. |
| **C — nginx `deny` / `limit_conn` / `limit_req` by IP** | Reverse-proxy gate at `:8085`. | Good for rate-limiting and crude DoS protection; **coarse for identity**, since hotspot IPs are DHCP-assigned and recycled. Use for load, not for "block this person". |
| **D — fail2ban in the rootfs** | Log-driven IP banning on the box. | A valid *hardening* layer that protects the box, but IP-based (same DHCP caveat) and it does not express per-device UX blocking. Out of scope for the admin UI; note it as defense-in-depth. |
| **E — OS / Wi-Fi kick** | Disconnect a station at L2. | **Rejected — not possible** (see Context). |

## Trade-off analysis

A is the durable home for the host admin because it sits where K2Go already has authority
and full context, and it is testable without an emulator. B adds revocation and per-device
approval and layers cleanly on A. C and D are IP-level tools: useful against load and abuse,
but a hotspot's DHCP churn makes IP a poor identity, so neither can be the primary "block a
device" mechanism. E is off the table. The through-line: **identity-based control needs the
DNS-SD name or a token (A + B); IP-based tools (C, D) are for load and hardening, not
identity.**

## Consequences

- **Honest UX.** The discoverable toggle removes you from the list; it is not a lock. The
  copy says exactly that.
- **The admin is real and lands in one place** — a ledger, a block/allow-list, a cap, an
  access mode, and a stop switch, all in the REST server, all unit-testable.
- **Identity is only as good as advertisement.** A peer that does not advertise shows as an
  IP; blocking it is by IP (coarse) until it identifies itself.
- **A block needs a lifecycle owner** (decision 4); the default is session-scoped.
- **No Wi-Fi admin is promised anywhere**, so no screen is built against a capability the
  platform does not give us.

## Action items

1. [ ] Confirm the ticket (ADFA-5197), hang it off Epic ADFA-1028, cross-link this ADR from
       `device-to-device-connectivity.md`.
2. [ ] REST router: block/allow-list check + max-concurrent-clients counter (Option A),
       with a pure, JVM-tested decision function.
3. [ ] Access mode *Anyone with link* vs *Approve each device*, backed by a per-session
       token (Option B) for approval and revocation.
4. [ ] Connection-ledger endpoint (source IP + matched DNS-SD name) for the "People
       browsing" surface.
5. [ ] Discoverable register/unregister toggle on the host's "Your device" card.
6. [ ] Decide the block-list lifetime (session vs durable) and record the owner/clearer.
