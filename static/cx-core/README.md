# cx-core: content-exchange core (read side)

An in-box REST service for granular content exchange between boxes (K2Go and IIAB).
It lists, exports, imports and deletes content per item across Kolibri, Kiwix and
Calibre. It is client-agnostic: dash-node (K2Go) or the IIAB Console reach it over its
REST API. See `controller/docs/ADR-content-exchange.md` for the frozen Pillar 0
contracts (identity, manifest, adapter port, core REST API).

The read, export, import and delete sides are built for all three adapters. Export runs
as a liveness-paced long operation (prepare / poll / fetch), so a multi-gigabyte item does
not time out on the client (K2GO-507). Share (K2GO-376) is the network twin: a token-gated
session over a scoped item set that reuses the export op per item (the gate contract only;
pairing and the network carrier are later app slices).

## What is here

- `cx_core/model.py`: app-agnostic content model (identity, dedup, manifest item).
- `cx_core/ports.py`: the inner contract, the app-adapter port (read methods).
- `cx_core/adapters/kiwix.py`: the first adapter, read side (ZIM header UUID + listing).
- `cx_core/registry.py`: loads an adapter only when its app is installed.
- `cx_core/export_ops.py`: the export long-operation registry (op lifecycle + temp sweeps).
- `cx_core/share_ops.py`: the share gate (token + scoped item set + the five closers), K2GO-376.
- `cx_core/router.py`: pure `/v1` route dispatch (unit-testable without a socket).
- `cx_core/server.py` + `__main__.py`: a thin stdlib `http.server` wrapper, localhost only.

No third-party dependency: the core runs on any of AMD64 / ARM64 / ARM32 with just the
system Python 3 (>= 3.7). This keeps it portable to the Pi and to the proot host on a
phone, where a package install is not guaranteed.

## Run

```
cd static/cx-core
python -m cx_core
```

Environment (defaults match the box layout):

- `CX_HOST` (default `127.0.0.1`), `CX_PORT` (default `9500`).
- `CX_ZIMS_DIR` (default `/library/zims/content/`): where the ZIM files live.

## Endpoints

Read:

- `GET /v1/version`: the core build (mirrors dash-node `/system/version`).
- `GET /v1/apps`: installed content apps that have a loaded adapter, plus `version`.
- `GET /v1/content[?app=<app>]`: every item this box has, with identity + size. Filterable.
- `GET /v1/content/{app}/{localRef}`: one item detail.
- `GET /v1/storage`: content-volume disk summary + per-app content totals.

Each item carries two identity handles: `localRef` (the app's own handle for
export / delete) and `dedup` (the cross-box "already have this?" key). For Kiwix both
are read, not computed: `dedup.build` is the ZIM header UUID.

Export (a long operation, K2GO-507): a large item (a Kolibri channel) must stage before
its bytes can stream, which takes minutes, so export is prepare / poll / fetch instead of
one blocking request. The front owns the sequence; the core owns the op and the temp.

- `POST /v1/export/prepare {app, localRef}` -> `{opId, state, totalBytes}`. `state` is
  `staging` (a background thread is staging) or `ready` (nothing to stage). `507` when the
  staging disk cannot hold the item.
- `GET /v1/export/status/{opId}` -> `{state, stagedBytes, totalBytes, error?}`. `stagedBytes`
  is the staging heartbeat (it grows as the export is assembled). `404` when the op is
  unknown (swept), so the client re-prepares.
- `GET /v1/export/fetch/{opId}` -> the chunked octet stream. `409` until the op is ready;
  the stream's close drops the op and removes its temp.
- `DELETE /v1/export/{opId}` -> `{ok: true}`. Client abort; idempotent.

Import / delete:

- `POST /v1/export/plan`, `POST /v1/dedup/check`, `POST /v1/import/validate`,
  `POST /v1/import/item`, `DELETE /v1/content/{app}/{localRef}`.

Share (a gate over the export op, K2GO-376): Share is the network twin of Export. The
receiver pulls selected items from the sender's live cx-core, so the transfer can close
itself (the sender machine is on, unlike Clone). A share session is a token plus a scoped
item set plus one shared lifecycle; each item is a lazily created export fetch, so the
staging, the in_use pin and the on_close cleanup are the export op's. The gate closes on the
first of five closers (TTL / idle, startup sweep, DELETE, implicit all-delivered, and the
sender service end, which is app-side). This slice is loopback only: pairing (QR, hotspot,
WLAN) is a later app slice. The session token travels as the `token` query parameter.

- `POST /v1/share {items: [{app, localRef}]}` -> `{sessionId, token, manifestUrl}`. Opens a
  session scoped to the item set.
- `GET /v1/share/{sessionId}/manifest?token=<token>` -> the bundle manifest over the scope
  (per-item identity, size, upstream hash when present, each with its `itemRef`), plus
  `missing` and `totalBytes`.
- `GET /v1/share/{sessionId}/fetch/{itemRef}?token=<token>` -> the chunked octet stream for
  one item. `409 {state: staging}` while a staging item (Kolibri) stages, so the receiver
  retries; `200` streams once ready.
- `DELETE /v1/share/{sessionId}?token=<token>` -> `{ok: true}`. Closes the session now and
  revokes the token and every child temp. `404` on an unknown or already-closed session.

Token checks return `401` when no token is sent, `403` on a mismatch, `404` on an unknown or
expired session.

## Test

```
cd static/cx-core
python -m unittest discover -s tests -t .
```

The tests cover the pure logic with no device: ZIM header UUID parsing, filename
decomposition, soft-key normalization, the manifest JSON shape, route dispatch, the export
long-operation registry (prepare / status / fetch / delete, the TTL and startup sweeps), the
share gate (create / manifest / fetch / delete, token enforcement, the five closers), and the
streaming over a real socket.

## Not built yet

The Share pairing and network carrier (QR, hotspot, WLAN: the app slices that build on the
`/v1/share` gate above), strict-mode content hashing, the cx-core live self-update (D1d; the
Ansible role baseline is in `tools/upstream-patches/overlays/roles/cx_core`), and the
dash-node <-> core listing consolidation (D1b).

The registry resolves installed apps once at startup (a snapshot). Reacting to a module
being installed or removed at runtime is a deferred design item: it must consume the
install system's single source of truth (ModuleRegistry / install events), not re-derive
installed-state per request. A second owner of "what is installed" is the design trap this
avoids.
