# ADR: Code on the Go build assets, served offline

Status: Accepted
Ticket: K2GO-437
Date: 2026-10-02

## Context

Code on the Go (COGO) builds apps on the device. It needs a build environment: Gradle, the
Gradle API, the Android SDK, the terminal bootstrap, an offline Maven repository, a
documentation database, and project templates. The shipped release APK bundles this
environment and extracts it at first run, so a release COGO already builds with no internet.

For a future, slimmer COGO that downloads the build environment at first run, a fully offline
box must host that environment on the local network. This ticket is the BOX side: mirror the
assets and serve them, so the files are available at `http://box:8085/code-assets/`. The
COGO consumer change (a runtime downloader with a configurable base URL) is separate and not
in this scope: COGO has no runtime asset URL today (verified in CodeOnTheGo `app/build.gradle.kts`,
where the `appdevforall.org/dev-assets` URLs are build-time only).

The assets live at `https://appdevforall.org/dev-assets/{debug,release}/...`, each file with a
sibling `<name>.md5`. There is no catalog or directory index; the set is a flat list of files.

## Decision

Add a static-distribution role `code_assets`, shaped on `code_addons` (K2GO-99): mirror the
published files into the rootfs and serve them at `http://box:8085/code-assets/`. Refresh a
deployed box through dash-node (the live-content pattern), not through runrole.

### 1. Serve the RELEASE set only

The shipped COGO is the release build, so a device consuming these offline needs the release
set: the brotli `.br` files (~0.8 GiB). The debug set (uncompressed, ~1.5 GiB) is for developer
builds of COGO and is intentionally not mirrored. Adding it later is a manifest edit; the mirror
already handles it.

### 2. Manifest-driven mirror (no catalog on the source)

Unlike the add-ons gallery, the build-assets site has no `catalog.json` and no directory index.
So the download set is a committed manifest, `files/code_assets_manifest.json`: the path, title,
and description of every asset. This is the single source of truth: `mirror_code_assets.py`
reads it to know what to download, and renders the browse page from the title and description
of each entry. The manifest is kept in step with COGO's `app/build.gradle.kts` `releaseAssets`.

### 3. Integrity and incremental refresh from the per-file `.md5`

Each file carries a sibling `.md5`. The mirror downloads a file, verifies it against that md5,
and writes the md5 next to it. On refresh (`--reuse-from` the live tree), it compares the
published md5 against the served md5: a file whose md5 is unchanged is copied from the served
tree, never re-downloaded and never re-hashed. When every file reuses and the generated page is
unchanged, a short-circuit reports `result: up-to-date` and builds no staging, so the wrapper
keeps the live tree. This mirrors the K2GO-441 add-ons pattern, with md5 in place of the catalog
sha256.

### 4. The browse page is generated, with an icon

The mirror generates a self-contained `index.html` (inline CSS, an inline favicon and header
logo, one card per asset with its description, size, a Download link, and a Copy-link button
that uses `location.origin` so the pasted URL is the box's own address, with no baked IP). The
page adapts to light and dark. The icon closes the gap that the served add-ons gallery still
has (K2GO-99 follow-up).

### 5. Source move to R2

The assets move from `appdevforall.org/dev-assets` to Cloudflare R2 soon. The source base is a
single role variable (`code_assets_source_base`) and the mirror's `--source-base` default; when
R2 lands, only that default changes, not the manifest or the code.

### 6. Ships as an overlay, baked on Full via runrole

The role is carried as a whole-file overlay under `tools/upstream-patches/overlays/roles/code_assets/`,
not a unified-diff patch: the files are all new, so an overlay is deterministic and avoids a
fragile context diff. Because an overlay cannot add the `6-generic-apps` include that
`code_addons` uses (that file is already patched by `code_addons`, and patches run before
overlays), the role has no auto-include: it is run on demand via `runrole code_assets`. The role
tree is present on every tier (the overlay is not tier-gated), so on-demand install works
anywhere; `iiab-android` enables and mirrors it at bake for Full only (the release set is ~0.8 GiB).

### 7. Install is the role; update is dash-node

- Install (bake time, Full): `iiab-android` sets `code_assets_install: True` and runs
  `runrole code_assets`, which mirrors the release set and installs the nginx conf.
- Update (deployed box): dash-node re-runs `mirror_code_assets.py --reuse-from` in-server
  (`POST /code-assets/refresh`), the same incremental mechanism. Surfaced as an "Update build
  assets" action, like add-ons. The role and the dash-node job share the script, not code.

### Filesystem and serving

- Mirror into `{{ content_base }}/www/code-assets` (`/library/www/code-assets`).
- nginx: a static `code-assets-nginx.conf` location served at `/code-assets/`. The `.br` files
  (and other archive types) are served as `application/octet-stream` so the client downloads
  them instead of rendering them; the `.md5` sidecars are `text/plain`.

## Consequences

- Hosting the COGO build environment offline is a small static-content role plus a dash-node
  refresh job, reusing `code_addons` and the live-content pattern. No new framework.
- The box side is complete and correct on its own; it waits on no COGO change. Wiring COGO to
  fetch from the box (a runtime downloader + a configurable or pasteable base URL) is a separate
  CodeOnTheGo change, tracked separately.

## Alternatives considered

- Ship the role as a unified-diff patch like `code_addons`. Rejected: the `default_vars` and
  `6-generic-apps` context hunks fought the generator, and an overlay of new files is simpler and
  deterministic. The one thing a patch would add (the `6-generic-apps` include) is replaced by
  `runrole` at bake.
- Mirror both debug and release. Rejected for now: ~2.2 GiB, and the debug set serves developer
  builds, not the offline end-user scenario. It is a manifest edit away if needed.
- Map `appdevforall.org` to the box and serve unchanged. Rejected: the chosen design uses an
  explicit local path and bakes no host into the files.

## Open items

- COGO consumer: a runtime downloader plus a configurable or pasteable asset base URL, so COGO
  fetches from `http://box:8085/code-assets/`. Needs a change in CodeOnTheGo. Out of scope here.
- The multicast or box-discovery mechanism COGO would use to find the box automatically. Future.
