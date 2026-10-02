# ADR: Add-ons offline gallery as a static-content role

Status: Accepted
Ticket: K2GO-99
Date: 2026-10-01

## Context

Code on the Go (COGO) add-ons are plugins packaged as `.cgp` files. They are distributed as a
static gallery on Cloudflare R2 at `https://addons.appdevforall.org`, produced by the
`appdevforall/addons` "garden" (the `tools/addons` Python tool and the `publish-addons.yml`
workflow). The garden builds each `.cgp`, generates a catalog, and uploads a complete static site.

A K2Go box is offline. People on the box need to browse, filter, read, and download the add-ons
without internet, and (later) install them in COGO. So the box must host a local copy of that
gallery.

The published site is a client-rendered static site:

- `index.html` plus content-hashed shell assets under `assets/` (`styles.<hash>.css`,
  `app.<hash>.js`, `adfa-logo.<hash>.svg`).
- `v1/catalog.json` (and `v1/catalog.schema.json`).
- Per add-on: `dl/<slug>.cgp`, `p/<slug>.html`, `p/<slug>.png`, `p/<slug>-night.png`,
  `src/<slug>-src.tar.gz`.

`app.js` reads `v1/catalog.json` at runtime and renders the cards, descriptions, and filtering on
the client. So a faithful mirror of these files is a fully navigable, filterable gallery, not a bare
list of links.

This is the first of a family of content repo roles (add-ons now, maven-offline next). The goal is
cheap, localized additions that reuse existing patterns and do not grow a god class.

## Decision

Add a new static-distribution Ansible role, shaped on `roles/code`, that mirrors the published
add-ons gallery from R2 into the rootfs and serves it at `http://box:8085/code-addons/`. Refresh a
deployed box through dash-node (the live-content pattern), not through runrole.

### 1. Evergreen, reference-driven mirror (no fixed file list)

The mirror follows references from two stable anchors, so it never carries a hardcoded list of
files:

- `v1/catalog.json` enumerates every per-add-on file for every add-on: `download`, `iconUrl`,
  `iconDarkUrl`, `pageUrl`, `sourceTarball`, each with `sha256` and `size`. This is the part that
  grows (21 add-ons today, more tomorrow); it lists itself in full.
- `index.html` names its own content-hashed shell assets in its `link`/`script`/`img` tags. Parse
  it, follow the references.

New add-ons and changed or added shell assets are picked up automatically. The only hard-coded names
are the two anchors, which are the site's permanent contract: the catalog is versioned under `v1/`,
so a breaking change to its shape becomes `v2/`, a deliberate signaled change, not silent drift. A
file that is reachable from neither anchor is a file the browser never requests either, so it is not
part of the functioning site.

### 2. One transform only: the catalog base

The shell and the per-add-on pages use relative asset paths (`index.html` uses `assets/...`;
`p/<slug>.html` uses `../assets/...`), so they serve unchanged at the `/code-addons/` sub-path. The
only rewrite is the catalog base: replace `https://addons.appdevforall.org` with `/code-addons` in
`catalog.json`. `app.js` resolves catalog URLs against `location.origin`, so the catalog base must be
a root-absolute path (`/code-addons`) that carries the mount. It then renders as
`http://<box-ip>:8085/code-addons/...` on any box, with no baked IP.

The external marketing links and the Google Fonts stylesheet stay absolute; offline they degrade
without affecting the add-on gallery (the font falls back to the system stack; the nav links do not
open).

### 3. Integrity from the catalog

Each `.cgp` is verified with the `sha256` the catalog already carries (Ansible `get_url`
`checksum:`), so no custom verification is written.

### 4. Install is the role; update is dash-node

- Install (bake time): the role populates the mirror during the rootfs build, where CI has internet.
  A fresh box ships with the add-ons.
- Update (deployed box): dash-node re-runs the same reference crawl in-server, durable and resumable,
  the ZIM / ADFA-4849 live-content pattern. It is surfaced as an "Update add-ons" action, like
  Forgejo's "Update repos". No runrole is needed: the content is pure static files that dash-node can
  replace live. The role (Ansible) and the dash-node job share the algorithm, documented here, not
  code, the same split Forgejo uses.

### 5. Arch-portable by construction

The content is pure static files: no binary, no architecture-specific anything. There is no
per-architecture branch. The only platform branch is the nginx reload (`systemd reload` vs
`pdsm restart nginx`), inherited from `roles/code`. The role runs identically on proot, systemd,
ARM64, and AMD64.

### 6. Incremental refresh and the up-to-date convention (K2GO-441)

The deployed-box update does not re-download the gallery every time. `mirror_addons.py` takes
`--reuse-from` the live served tree and transfers only what changed:

- Short-circuit: it fetches `v1/catalog.json` and, when the base-rewritten published catalog equals
  the served one, reports `result: up-to-date` and downloads nothing. The wrapper keeps the live
  gallery (no swap).
- Reuse: `catalog.json` is the single source of truth, so what changed is read from it, with no local
  re-hash and no extra requests. The `.cgp` and the source tarball carry a catalog sha256, so an
  unchanged one (its published sha equals the served catalog's sha) is copied from the served tree.
  The source tarball is `git ls-files` of the add-on, so it CONTAINS the icon and the page source: an
  add-on's icon is reused when its tarball sha is unchanged, and its page when the tarball sha AND
  index.html are both unchanged (the page also carries the shell's hashed-asset chrome). Shell assets
  have content-hashed names, so a name already in the served tree is identical and is reused.

Cross-cutting convention: every K2Go update mechanism reports when there is nothing to do ("already
up to date"), not only when it changed something. Forgejo's refresh does this per repo
(`refresh up-to-date`, surfaced as "All repositories are already up to date"); the add-ons refresh
does it for the whole gallery. A new updater follows the same pattern.

The mirror script still ships in the rootfs (the role tree), so a change to the mirror algorithm
reaches a deployed box through a new bake or a reinstall, not through the "Update add-ons" action
(which only re-runs the script already on the box). Letting dash-node update the update scripts in
place is a separate, deliberate change (K2GO-440).

### Filesystem and serving

- Mirror into `{{ content_base }}/www/code-addons` (`/library/www/code-addons`), preserving the
  published layout: `index.html`, `assets/`, `v1/`, `p/`, `dl/`, `src/`.
- nginx: a static `code-addons-nginx.conf` location block, copied like `code-nginx.conf`, served at
  `/code-addons/` on the IIAB nginx (port 8085 under proot).
- Record disk usage and `addons_installed` in `iiab.ini` / `iiab_state.yml`, like every role.

### How the role is added (upstream-patches)

The role ships as a new `tools/upstream-patches` patch, the same mechanism as
`0003-forgejo-add-role-4505.patch`: it adds `roles/<addons>/*` plus the variable wiring
(`default_vars.yml`, `local_vars_android_{small,medium,large}.yml`, `local_vars_large.yml`), the
`0-init` validations, and the `6-generic-apps` include. It ships `install`/`enabled` False by
default; the K2Go bake flips them per tier.

### Load-bearing guards (what must not regress)

- One source per fact. The catalog is the single source of the add-on set; no second list is kept.
  The serve path `/code-addons` is defined once and reused by the role variable, the catalog base
  rewrite, and the nginx location.
- The mirror is reference-driven, never a hardcoded enumeration, so a structure change cannot
  silently drop a file.
- Update lifecycle. dash-node writes the new mirror to a temporary directory and swaps it in, so a
  refresh interrupted mid-download never serves a half-mirror. The dash-node change names the owner
  of that swap.

## Consequences

- Hosting the add-ons gallery offline is a small static-content role plus a dash-node refresh job,
  reusing `roles/code` and the live-content pattern. No new framework.
- The gallery stays evergreen: each mirror pulls the current shell and the current catalog, coherent
  with each other.
- The next content role (maven-offline) is this role with a different base and anchors.

## Alternatives considered

- Clone the add-ons repo and prune to what is needed. Rejected. The repo holds the gallery source
  and the plugin source, not the built `.cgp` or `catalog.json` (those exist only on R2), so a clone
  still needs R2 for the content and mixes two sources that can drift. It also drags submodules
  (`llama.cpp`) and Gradle. The reference-driven R2 mirror is cleaner and coherent.
- A hardcoded file list in the role. Rejected. It drifts silently when the site structure changes (a
  new asset, a renamed file).
- Map `addons.appdevforall.org` to the box (dnsmasq plus nginx `server_name`) and serve the catalog
  unchanged. Viable and needs zero rewrite, but the chosen design uses an explicit local path
  (`/code-addons`) and does not shadow the public hostname. Kept as a fallback if COGO hardcodes its
  base.
- Update via `runrole --reinstall` (like Forgejo). Unnecessary here. The content is static, so
  dash-node replaces files live without Ansible.

## Open items

- COGO in-app install. Whether the COGO Plugin Manager can be pointed at
  `http://box:8085/code-addons/v1/catalog.json` to install directly, or people download the `.cgp`
  from the gallery and sideload it. The gallery works either way; this decides whether a COGO setting
  is also wired. Needs a look at the CodeOnTheGo app.
- Device smoke. Confirm the nginx root/alias and that `app.js` `fetch("v1/catalog.json")` resolves
  under the `/code-addons/` mount on a real device.
- Page-body images. If an authored per-add-on page body references an image that the garden does not
  publish, it is already broken upstream; out of scope here.
