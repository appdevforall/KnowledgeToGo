# ADR-K2GO-394 -- Base-map downloads run through dash-node, not the maps role

## Status

Accepted (2026-09).

## Context

The maps SETUP downloads large whole-world base-map pmtiles (vector, satellite,
terrain -- gigabytes at high zoom). Upstream's maps role downloads them in-proot
through `roles/maps/tasks/download_large_file.yml` (an `aria2c` over a `.meta4`
metalink).

On Android the role runs in a proot with a mobile radio. An in-proot `aria2c`
that the app drove over JSON-RPC wedged at zero connections on a full network
drop and never recovered (K2GO-394): it did not exit, so nothing could retry it,
and the SETUP hung. We need the base-map download to be resilient (survive a
radio drop) and controllable (pause / resume / live progress) -- the same
properties ZIM and Kolibri downloads already have.

The box already has an engine for exactly this: **dash-node**, the in-server
durable job engine (ADR-4832, ADR-4893). It downloads with `aria2 --continue`
plus an outer reconnect loop, reports "Reconnecting n/5", and survives a client
disconnect. ZIM, Books and Kolibri downloads all run through it (LIVE-REST class,
ADR-5061); the app POSTs and polls, the box owns the download.

## Decision

**Route the base-map download through dash-node, ahead of the maps runrole, and
do NOT patch the maps role.**

1. **dash-node** gains a `basemaps` job (see the dash-node CHANGELOG, 1.3.2 /
   1.3.3): given a bare pmtiles file name it composes the mirror URL and
   downloads into the maps serve dir (`/library/www/maps`) with the proven kiwix
   reconnect mechanism.
2. **The app** orchestrates it, the same way it drives ZIM (`RestContentClient`):
   for the maps module it resolves the selected layers to file names from the
   catalog, POSTs `{ids:[...]}` to dash-node, shows the live download bar, and
   only when the download finishes does it run the maps runrole. The download and
   the runrole are two sequential phases, not one.
3. **Ansible (the maps role)** stays STOCK. Because dash-node has already placed
   each pmtiles at its `dest_path`, the role's native `creates: dest_path` (and
   the `.meta4` size-probe's own existence check) SKIP those downloads. The role
   only post-processes (symlinks, `maps-config.js`). Nothing in the role changes.

So three parts share the work -- ansible post-processes, dash-node downloads, the
app orchestrates -- mirroring the live-download pattern the rest of the app uses.

## Why the role is NOT patched

An earlier version patched `download_large_file.yml`: it gated the download
`when: not is_proot` and asserted dash-node pre-placement `when: is_proot`. This
**broke the CI rootfs bake**.

`is_proot` is `True` for the Android tiers (`vars/local_vars_android_*.yml`), and
the bake builds those tiers. But the bake has **no dash-node and no app** -- it
builds the rootfs image and is meant to download the base maps itself. With the
patch, the bake skipped the downloads and the `is_proot` assert failed:

```
TASK [maps : Fail if dash-node did not place maps.black-component.js on proot]
fatal: assertion 'proot_basemap.stat.exists' failed
```

`is_proot` does not distinguish "a live device with dash-node" from "a CI bake
without it". Both are proot. So no `is_proot` condition -- inline or in a separate
task file -- is correct here.

The native `creates:` skip needs no condition and is right in both contexts:

- **Device:** the app pre-places the selected pmtiles through dash-node, so
  `creates:` skips them; the role post-processes.
- **Bake:** nothing is pre-placed, so the role downloads everything itself over
  the CI runner's stable network, exactly as upstream intends.

Patching an upstream role only to say "we do not run this step here" would also
be a carry with no upstream value -- the opposite of the upstream-first policy in
`tools/upstream-patches/README.md`.

## The search tarball stays in-proot

The maps role also downloads the static-search database through the same task
with `expand_archive=true` (a `.tar.gz` it extracts). dash-node downloads files,
not archives -- it does not extract -- so search is **not** delegated. It
downloads AND extracts in-proot as upstream does. It is small (~16 MB), so the
in-proot download's exposure to a radio drop is short; a drop there fails the
role and the install's existing Retry re-runs it.

## Consequences

- The maps role reads exactly as upstream ships it. This divergence -- that on
  the K2Go device path the base maps arrive from dash-node, not from the role --
  is invisible in the role itself, so it is recorded here and pointed to from
  `tools/upstream-patches/README.md` (which is where a maintainer looks and finds
  no maps patch).
- Anything the app does NOT delegate (the search tarball, the small map JS
  components, or a selected layer the catalog cannot resolve) downloads in-proot
  with the stock role behavior. The big, selected pmtiles -- the ones worth many
  gigabytes and the reason this ticket exists -- are the ones delegated, so the
  wedge-prone case is covered.
- The three-part split (ansible + dash-node + app) is more moving parts than a
  lone role, but it is the same pattern ZIM/Books/Kolibri already use, so it is
  not new machinery -- just a new content type on it.

## References

- ADR-4832 (live content channel / single proot dash-node core)
- ADR-4893 (download execution and user control)
- ADR-5061 (LIVE-REST vs STOPPED-proot operation model)
- dash-node CHANGELOG: 1.3.2 (basemaps runner), 1.3.3 (file-id + URL composition)
- `tools/upstream-patches/README.md` (why there is no maps download patch)
- Jira: K2GO-394
