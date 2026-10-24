# cx-core Share: scoped off-loopback exposure (K2GO-376, step 2)

Status: DESIGN + role change (this branch). The security crux of granular wireless
Share. Authority for the gate itself: `ADR-content-exchange.md` "Pillar Share". This
doc covers only the EXPOSURE: how a paired peer reaches the sender's `/v1/share` gate,
and nothing else of cx-core, off the loopback interface.

## The problem

Share lets one K2Go box serve selected content to a peer over a hotspot. The receiver
PULLS from the sender's live cx-core `/v1/share/<session>/...` gate (step 1, merged).
For that pull to work, the box must expose the gate off loopback. It must expose ONLY
that gate. The rest of the cx-core API (`/v1/content`, `/v1/export`, `/v1/import`,
`/v1/storage`, `/v1/apps`, and session creation `POST /v1/share`) must stay unreachable
off-box.

Today nothing of cx-core is reachable off-box: cx-core binds `127.0.0.1:9500`, the
dash-node relay is in-box, and the nginx `/k2go-api` location on `:8085` is
allow-loopback / deny-all.

## Decision: a dedicated port (Option B)

A dedicated nginx listener on `0.0.0.0:8095` (plain HTTP) proxies ONLY
`/v1/share/<session>/...` to the loopback cx-core. The shared `:8085` server and its
`/k2go-api` deny-all are untouched; cx-core stays bound to loopback.

Why a dedicated port, not a widened location on `:8085`:
- A listener that only knows how to reach `/v1/share/<session>/...` cannot leak another
  endpoint through a location-ordering or regex slip. The blast radius is one path
  prefix, on one port, in one file.
- The proxy target is cx-core directly (`127.0.0.1:9500`), not the dash-node relay.
  The relay is a blanket `/v1` forwarder (`cx-proxy.ts`); routing the exposed port
  through it would add a hop and a second path-unscoped component for no gain. Direct
  to cx-core keeps the single scoping point in one nginx file.

Why plain HTTP: the link is the closed local hotspot and the token is the auth. A
self-signed cert that the offline peers cannot verify adds no trust. The defense is
two independent layers: the nginx path scope, and the gate token enforced in cx-core.

## The scope is airtight (what the server block does)

The template `roles/cx_core/templates/cx-core-share-nginx.conf.j2` builds the server
block. It is regex-free and denies by default:

- `location / { return 404; }` : deny by default. Any path other than the share
  surface is 404, including every other `/v1/*` endpoint.
- `location = /v1/share/ { return 404; }` : block session creation. This is the one
  real trap. cx-core's router does `path.rstrip("/")` before it splits segments
  (`router.py:177`), so `POST /v1/share/` collapses to `POST /v1/share` = CREATE, which
  mints a token for ARBITRARY items. The receiver never creates a session (the sender
  does, in-box over loopback), so the create path is blocked here. Bare `POST /v1/share`
  (no trailing slash) does not match the prefix below and falls to the deny-by-default
  404.
- `merge_slashes on;` : so `/v1/share//` (and more slashes) collapse to `/v1/share/`
  and hit the create guard, not the prefix rule. This is the nginx default; it is set
  explicitly so the guard holds even if the rootfs http block turned it off.
- `location ^~ /v1/share/ { proxy_pass http://127.0.0.1:9500; ... }` : the receiver
  surface only, which is `GET .../manifest`, `GET .../fetch/<ref>`, `POST .../complete`
  and `DELETE /v1/share/<session>`. Every one is token-gated inside cx-core (401 no
  token, 403 mismatch, 404 unknown session; `compare_digest`). `^~` stops a future
  regex location from overriding it.

The streaming settings (`proxy_buffering off`, `proxy_http_version 1.1`,
`proxy_read_timeout 3600s`) let a multi-GB item stream through without buffering to disk
and without a mid-transfer timeout on a slow hotspot link.

The `X-Share-Token` header passes through nginx unchanged (it is hyphenated, so the
underscore-in-header drop does not apply). The token travels in the header, never the
URL, so it stays out of nginx access logs.

Idle safety: with no active session, every `/v1/share/<anything>` request is 404
(unknown session) and the create path is blocked, so an always-listening `:8095` port
exposes no content when nobody is sharing. The session id is `token_hex(16)` (128-bit)
and the token is `token_urlsafe(32)` (256-bit): both unguessable. The real reachability
window is the hotspot being up, which the sender app owns ("do not leave it on").

## What the rootfs must provide (OUT OF THIS REPO)

The main nginx config: the `http` block, the `:8085` server block, its `listen`
address, and the includes, lives in the rootfs (built by the IIAB bake), not in this
repo. The `*-nginx.conf` files here are LOCATION snippets that IIAB includes INSIDE the
`:8085` server block (that is why they are bare `location` blocks). A `server` block
cannot go in that include set. So the rootfs owns two items:

1. Include the Share server block at the http context. Add one line to the rootfs
   nginx config, inside `http { ... }` and OUTSIDE any `server { }` block:

   ```
   include /etc/nginx/k2go-http.d/*.conf;
   ```

   The role writes the server block to `/etc/nginx/k2go-http.d/cx-core-share.conf` and
   creates that directory. If the rootfs does not add this include, the file sits inert
   and `:8095` never listens: no accidental exposure (fail-closed). The glob tolerates
   an empty or absent directory, so the include line is safe to add before the role has
   run.

2. Confirm the main content server listens off loopback:
   `listen 0.0.0.0:8085` (not `127.0.0.1:8085`). This is the open checklist item in
   `device-to-device-connectivity.md` section 6 (and section 10). It is required for a
   peer to reach the box at all, independent of Share. VERIFY on the box:

   ```
   grep -RnE "listen[[:space:]]+.*8085" /etc/nginx/
   ```

   If it shows `127.0.0.1:8085`, no peer can reach the box; the rootfs nginx must bind
   `0.0.0.0:8085`. (The `:8095` Share block in this role already binds `0.0.0.0`.)

## Port decision: 8095 is free

Checked against the IIAB port map (iiab wiki "IIAB Networking" and the nginx role
README) and this repo's ports:

- IIAB 80xx cluster: 8000 jupyterhub, 8006-8008 kalite (deprecated), 8009 kolibri,
  8010 calibre-serve, 8080 calibre-serve / idmgr, 8081 cham (proposed), 8083
  calibre-web, 8089 sugarizer, 8090 apache2 shim (deprecated).
- K2Go additions: 8085 nginx portal, 8114 tier-3 help docs, 9500 cx-core (loopback),
  8083 calibre-web (cx-core reads it), 8009 kolibri.
- AzuraCast (the wide range to avoid) sits at 10000-10499 plus 12080/12443, far from
  8095. Icecast is not used by IIAB.

8095 is unassigned in every source. It sits clear above the dense 808x content cluster
(8080/8081/8083) and clear of the deprecated 8089/8090. The alternate candidate 8086 is
also free, but it sits inside the 808x cluster, so 8095 is the cleaner pick. Ports are
site-configurable in IIAB (`default_vars.yml`), so `cx_core_share_port` is a role
variable: if a site has remapped something onto 8095, change the one variable.

## How to verify the scoped exposure on the A16

Device: Samsung A16 (RF8Y80CE2DA). The box is the Debian proot on the phone; cx-core is
reachable in-box on `127.0.0.1:9500`. After deploying the role (so
`/etc/nginx/k2go-http.d/cx-core-share.conf` exists) AND the rootfs http-level include is
in place AND nginx is reloaded, reach `:8095` from the host over `adb forward`:

```
adb -s RF8Y80CE2DA forward tcp:8095 tcp:8095
```

Positive (the receiver surface is reachable): an unknown session returns 404 from the
gate, which proves the proxy reaches cx-core through the scoped path, not an nginx 404:

```
curl -i http://127.0.0.1:8095/v1/share/deadbeef/manifest -H "X-Share-Token: x"
# expect 404 {"error":"unknown session"} (from cx-core: proxied through)
```

For a full positive, create a real session in-box over loopback (the sender's path),
then fetch its manifest through `:8095` with the real token:

```
# in-box (loopback), as the sender does:
curl -s -XPOST http://127.0.0.1:9500/v1/share \
     -d '{"items":[{"app":"kiwix","localRef":"<uuid>"}]}'
# -> {"sessionId":"...","token":"...","manifestUrl":"/v1/share/<id>/manifest"}

# off-box surface (through :8095), with the token in the header:
curl -i "http://127.0.0.1:8095/v1/share/<id>/manifest" -H "X-Share-Token: <token>"
# expect 200 with the manifest
```

Negative (nothing else is reachable). Each of these MUST return a plain nginx 404 (an
HTML 404 body or empty, NOT a cx-core JSON error), which proves the request never
reached cx-core:

```
curl -i http://127.0.0.1:8095/v1/content                 # other API: 404
curl -i http://127.0.0.1:8095/v1/apps                    # other API: 404
curl -i http://127.0.0.1:8095/v1/storage                 # other API: 404
curl -i -XPOST http://127.0.0.1:8095/v1/share            # create (bare): 404
curl -i -XPOST http://127.0.0.1:8095/v1/share/           # create (trailing slash): 404
curl -i -XPOST http://127.0.0.1:8095/v1/share//          # create (double slash): 404
curl -i http://127.0.0.1:8095/                           # root: 404
```

The tell is the body: a cx-core JSON error (`{"error":...}`) on any of the negative
cases means the scope leaked (the request reached cx-core). A bare nginx 404 means the
scope held. The create cases are the ones that matter most: a JSON token response there
is a full gate bypass.

Remove the `adb forward` when done (`adb -s RF8Y80CE2DA forward --remove tcp:8095`).

## Scope kept (what this change does NOT do)

- Does not expose the whole API: only `/v1/share/<session>/...`.
- Does not bind cx-core to 0.0.0.0: cx-core stays on `127.0.0.1:9500`.
- Does not widen the `:8085` `/k2go-api` deny-all, and does not touch the `:8085`
  server block.
- Does not add TLS (plain HTTP, token is the auth: see the decision above).
