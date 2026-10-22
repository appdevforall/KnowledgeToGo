# cx-core: content-exchange core (read side)

An in-box REST service for granular content exchange between boxes (K2Go and IIAB).
It lists, exports, imports and deletes content per item across Kolibri, Kiwix and
Calibre. It is client-agnostic: dash-node (K2Go) or the IIAB Console reach it over its
REST API. See `controller/docs/ADR-content-exchange.md` for the frozen Pillar 0
contracts (identity, manifest, adapter port, core REST API).

This directory is the first slice: the READ side of Pillar 1, with the Kiwix adapter.

## What is here

- `cx_core/model.py`: app-agnostic content model (identity, dedup, manifest item).
- `cx_core/ports.py`: the inner contract, the app-adapter port (read methods).
- `cx_core/adapters/kiwix.py`: the first adapter, read side (ZIM header UUID + listing).
- `cx_core/registry.py`: loads an adapter only when its app is installed.
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

## Endpoints (read side)

- `GET /v1/apps`: installed content apps that have a loaded adapter.
- `GET /v1/content[?app=<app>]`: every item this box has, with identity + size. Filterable.
- `GET /v1/content/{app}/{localRef}`: one item detail.

Each item carries two identity handles: `localRef` (the app's own handle for
export / delete) and `dedup` (the cross-box "already have this?" key). For Kiwix both
are read, not computed: `dedup.build` is the ZIM header UUID.

## Test

```
cd static/cx-core
python -m unittest discover -s tests -t .
```

The tests cover the pure logic with no device: ZIM header UUID parsing, filename
decomposition, soft-key normalization, the manifest JSON shape, and route dispatch.

## Not in this slice

Export, import, delete, the dedup-check endpoint, the Kolibri and Calibre adapters, the
offline-disk and network carriers, strict-mode hashing, and the manager UI. The adapter
boundary is kept clean so Kolibri and Calibre slot in next.

Deployment (D1d: an Ansible-role baseline plus a dash-node-style live self-update) is
not built here; this slice only has to be runnable. Live on-device verification needs a
box and is a follow-up.

The registry resolves installed apps once at startup (a snapshot). Reacting to a module
being installed or removed at runtime is a deferred design item: it must consume the
install system's single source of truth (ModuleRegistry / install events), not re-derive
installed-state per request. A second owner of "what is installed" is the design trap this
avoids.
