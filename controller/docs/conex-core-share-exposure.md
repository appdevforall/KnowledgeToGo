# conex-core Share: scoped off-loopback exposure (K2GO-376)

The security crux of granular wireless Share. Authority for the gate itself:
`ADR-content-exchange.md` "Pillar Share". This doc covers only the EXPOSURE: how a paired
peer reaches the sender's `/v1/share` gate, and nothing else of conex-core, off the
loopback interface.

## The problem

Share lets one box serve selected content to a peer over the local network. The receiver
PULLS from the sender's live conex-core `/v1/share/<session>/...` gate. For that pull to
work, the box must expose the gate off loopback. It must expose ONLY that gate. The rest
of the conex-core API (`/v1/content`, `/v1/export`, `/v1/import`, `/v1/storage`,
`/v1/apps`, and session creation `POST /v1/share`) must stay unreachable off-box.

conex-core binds `127.0.0.1:9500`. Nothing else of it is reachable off-box.

## Decision: the role's own nginx conf, on the main server

The `conex_core` role installs its own nginx conf, `{{ nginx_conf_dir }}/conex-core-nginx.conf`
(`/etc/nginx/conf.d`), exactly like any other IIAB role (for example `code_addons`). The
file holds `location` blocks that the box's main server includes. It proxies ONLY
`{{ conex_core_url }}/v1/share/<session>/...` (default `/conex-core/v1/share/...`) to the
loopback conex-core, which serves it as `/v1/share/...`.

Why this shape:
- conex-core is shared by IIAB and K2Go, so its exposure must work the same on both. The
  main server already exists on every box (`:80` on IIAB, `:8085` on K2Go) and already
  includes `conf.d`. No extra port, no change to the global `nginx.conf`, no
  platform-specific directory.
- The isolation comes from what is proxied, not from a separate server. Only the share
  prefix is proxied; the rest of the `{{ conex_core_url }}/` namespace is an explicit 404.
- The proxy target is conex-core directly (`127.0.0.1:9500`), not the K2Go dash-node relay
  (a blanket `/v1` forwarder). The single scoping point stays in one nginx file.

Why plain HTTP: the link is the closed local network and the token is the auth. A
self-signed cert that offline peers cannot verify adds no trust. The defense is two
independent layers: the nginx path scope, and the gate token enforced in conex-core.

## The scope (what the conf does)

Template: `roles/conex_core/templates/conex-core-nginx.conf.j2`. Rules, all under the main
server:

- `location ^~ {{ conex_core_url }}/ { return 404; }` : deny by default. Any path in the
  namespace other than the share surface is 404, including every other `/v1/*` endpoint.
- `location = {{ conex_core_url }}/v1/share { return 404; }` : block session creation
  (bare form). Without it, nginx answers the bare prefix with an automatic 301 to the
  slash form.
- Inside the share prefix, `if ($uri ~ "^{{ conex_core_url }}/v1/share/+$") { return 404; }` :
  block session creation (slash forms). conex-core strips trailing slashes before it routes,
  so `.../v1/share/` and `.../v1/share//` would reach CREATE, which mints a token for
  ARBITRARY items. The match runs on the decoded URI, so it holds even if `merge_slashes`
  is off in the http block (the role cannot set that directive from a location). The
  receiver never creates a session; the sender does, in-box over loopback.
- `location ^~ {{ conex_core_url }}/v1/share/ { proxy_pass http://127.0.0.1:9500/v1/share/; }` :
  the receiver surface only: `GET .../manifest`, `GET .../fetch/<ref>`, `POST .../complete`
  and `DELETE /v1/share/<session>`. Every one is token-gated inside conex-core (401 no
  token, 403 mismatch, 404 unknown session; `compare_digest`). `^~` stops a regex location
  from overriding it.

The streaming settings (`proxy_buffering off`, `proxy_http_version 1.1`,
`proxy_read_timeout 3600s`) let a multi-GB item stream through without buffering to disk
and without a mid-transfer timeout on a slow link.

The `X-Share-Token` header passes through nginx unchanged (it is hyphenated, so the
underscore-in-header drop does not apply). The token travels in the header, never the
URL, so it stays out of nginx access logs.

Idle safety: with no active session, every `/v1/share/<anything>` request is 404 (unknown
session) and the create path is blocked, so the always-installed gate exposes no content
when nobody is sharing. The session id is `token_hex(16)` (128-bit) and the token is
`token_urlsafe(32)` (256-bit): both unguessable. The real reachability window is the
hotspot being up, which the sender app owns.

The switch `conex_core_share_expose` (default on) installs or removes the conf. False keeps
conex-core loopback-only; in-box content exchange still works.

## The app side

The receiver composes its URLs from the invite in the QR: `http://<senderIp>:<port>` plus
the manifest path. The sender puts the gate-prefixed path in the invite
(`ShareTransport.SHARE_PATH_PREFIX` + the core's `/v1/share/<id>/manifest`); the fetch and
complete URLs are derived from it (`ShareUrls`). The port is the box's main server
(`ShareTransport.SHARE_PORT` = `BoxEndpoints.PORT`, `8085` on K2Go).
`SHARE_PATH_PREFIX` must match the role variable `conex_core_url`.

## Prerequisite: the main server listens off loopback

A peer reaches the gate through the box's main server, so that server must listen on all
interfaces (`0.0.0.0`), not only `127.0.0.1`. This is also required for a peer to reach
the box at all, independent of Share. Verified on the OnePlus 7T (2026-11-01):
`0.0.0.0:8085 LISTEN`. To check a box:

```
netstat -tln | grep LISTEN | grep ':8085 '
```

## Migration from the earlier design

An earlier role version used a separate `server` block on `:8095` in
`/etc/nginx/k2go-http.d/`, which needed an `include` in the global `nginx.conf` that the
rootfs never added, so the listener stayed inert. The role now removes that directory on
every apply and on uninstall.

## How to verify the scoped exposure

Through the main server on a box (K2Go `:8085` shown; IIAB uses `:80`). From a host, over
`adb forward` to a phone:

```
adb -s <serial> forward tcp:8085 tcp:8085
```

Positive (the receiver surface is reachable): an unknown session returns a conex-core JSON
404, which proves the proxy reaches conex-core through the scoped path:

```
curl -i http://127.0.0.1:8085/conex-core/v1/share/deadbeef/manifest -H "X-Share-Token: x"
# expect 404 {"error":"unknown session"} (from conex-core: proxied through)
```

For a full positive, create a real session in-box over loopback (the sender's path), then
fetch its manifest through the gate with the real token:

```
# in-box (loopback), as the sender does:
curl -s -XPOST http://127.0.0.1:9500/v1/share \
     -d '{"items":[{"app":"kiwix","localRef":"<uuid>"}]}'
# -> {"sessionId":"...","token":"...","manifestUrl":"/v1/share/<id>/manifest"}

# off-box surface (through the gate), with the token in the header:
curl -i "http://127.0.0.1:8085/conex-core/v1/share/<id>/manifest" -H "X-Share-Token: <token>"
# expect 200 with the manifest
```

Negative (nothing else is reachable). Each MUST return a plain nginx 404 (an HTML body,
NOT a conex-core JSON error), which proves the request never reached conex-core:

```
curl -i http://127.0.0.1:8085/conex-core/v1/content                    # other API
curl -i http://127.0.0.1:8085/conex-core/v1/apps                       # other API
curl -i http://127.0.0.1:8085/conex-core/                              # namespace root
curl -i -XPOST http://127.0.0.1:8085/conex-core/v1/share               # create (bare)
curl -i -XPOST http://127.0.0.1:8085/conex-core/v1/share/              # create (slash)
curl -i -XPOST http://127.0.0.1:8085/conex-core/v1/share//             # create (double slash)
curl -i --path-as-is -XPOST http://127.0.0.1:8085/conex-core/v1/share%2f   # create (encoded slash)
curl -i --path-as-is -XPOST http://127.0.0.1:8085/conex-core/v1/share/%2e%2e   # climb
curl -i --path-as-is http://127.0.0.1:8085/conex-core/v1/share/x/%2e%2e/%2e%2e/content   # climb to another API
```

Use `curl --path-as-is` so curl does not normalize the encoded cases before the request
leaves the host. The tell is the body: a conex-core JSON error on any negative case means
the scope leaked. The create cases matter most: a JSON token response there is a full gate
bypass.

This matrix passed against the rendered template in a stock nginx 1.27 with
`merge_slashes off` (the worst case): the four receiver endpoints reached the core as
`/v1/share/...`, and every negative case returned a plain nginx 404.

Remove the forward when done (`adb -s <serial> forward --remove tcp:8085`).

## Scope kept (what this change does NOT do)

- Does not expose the whole API: only `/v1/share/<session>/...`.
- Does not bind conex-core to 0.0.0.0: conex-core stays on `127.0.0.1:9500`.
- Does not touch the global `nginx.conf` or the dash-node relay (`/k2go-api`).
- Does not add TLS (plain HTTP, token is the auth: see the decision above).
