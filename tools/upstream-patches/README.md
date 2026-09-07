# Upstream patch staging (`tools/upstream-patches/`)

A staging area for changes we have proposed (or will propose) to the upstream
`iiab/iiab` project but may require test on custom rootfs

## Why this exists

Our fixes must land upstream to be sustainable, but we need to test them to validate they work.
So rootfs builds can be achieved meanwhile, upstream can pass checks on their workflows:

- carry a small set of pending changes and apply them systematically at install time;
- stay **idempotent**, not applying a patch a second time;
- prune cleanly, when upstream validates a change, then its patch file gets remove on maintenance;
  nothing else changes.

Policy: **upstream-first.** A patch here is a temporary carry, not a fork. Each patch
records the upstream PR/issue it corresponds to and its status, so maintenance is a
matter of reading that header and deleting what has merged.

## Layout

```
tools/upstream-patches/
  README.md                     # this file (design + conventions)
  apply-upstream-patches.sh     # idempotent applier (run at install time)
  patches/                      # unified-diff patches, applied with -p1 over /opt/iiab/iiab
    NNNN-<area>-<slug>.patch
  overlays/                     # optional: whole-file replacements (mirrors the repo tree)
```

## How it applies (idempotency)

`apply-upstream-patches.sh` walks `patches/*.patch` in numeric order and, for each:

1. tries a **reverse** dry-run (`patch -p1 -R --dry-run`). If it succeeds, the change is
   already in the tree (upstream merged it, or a previous run applied it) **skip**;
2. otherwise tries a **forward** dry-run. If it applies cleanly **apply**;
3. otherwise the patch no longer fits the current `/opt/iiab/iiab` (context drift or a
   partial state) **fail loudly** (exit 1). We never bake a half-patched tree.

This is the same reverse-dry-run test the IIAB `kolibri` role already uses for its own
proot patch, so the behaviour is familiar and battle-tested.

## Patch conventions

- **Generate relative to the repo root** so `-p1` lands on `/opt/iiab/iiab`
- **Filename:** `NNNN-<area>-<slug>.patch` -- `NNNN` a zero-padded order (`0001`, `0002`...),
  `<area>` the role/subsystem (e.g. `kolibri`, `nginx`, `proot_services`), `<slug>` a short
  kebab description. One logical change per patch (mirrors one upstream PR).
- **Header metadata** (comment lines before the diff; `patch` ignores them). Keep it sober:
  ```
  Upstream-PR:     https://github.com/iiab/iiab/pull/XXXX   (or "not yet submitted")
  Upstream-Status: open | merged-pending-release | superseded
  Applies-to:      roles/kolibri/... (path within /opt/iiab/iiab)
  Summary:         one line on what and why
  ```
- **English only** for the patch content and headers (repo convention).

### Whole-file overlays (optional)

Some carries are clearer as a full-file replacement than a diff -- for example replacing a
file that is itself a `.patch` (so a diff-of-a-diff is awkward). Put the replacement under
`overlays/` mirroring the path within the repo root; it is copied only when it differs
(idempotent). Prefer real patches for code; reserve overlays for whole-file assets.

## Where it hooks into the build

`/opt/iiab/iiab` is created by `install.txt`, which the repo's `iiab-android` installer
fetches and runs (`iiab-android` -> `install.txt` -> clone `iiab/iiab` -> Ansible). The applier
must run **after the checkout exists at the intended commit and before the Ansible roles
execute**, because at least one carry (the Kolibri patch) is a *file the kolibri role reads
during the run*.

## Maintenance

When an upstream PR merges and ships in the pinned `iiab/iiab` commit, its patch becomes a
no-op (reverse-dry-run skip). At the next maintenance pass, read each patch's
`Upstream-Status`, delete the ones marked merged, and bump the pinned commit. Keep the set
small.

## Notable non-patches

Sometimes the right carry is **no patch at all** -- recorded here so a deliberate absence is
not mistaken for an oversight.

- **Maps base-map download (K2GO-394) -- no patch, on purpose.** Upstream's maps role
  downloads the base-map pmtiles in-proot through `roles/maps/tasks/download_large_file.yml`.
  On the K2Go device path, dash-node (the in-server durable job engine) pre-downloads them --
  app-driven, resilient, resumable -- into the maps serve dir BEFORE the runrole, so the role's
  native `creates: dest_path` skips those downloads and it only post-processes. The role reads
  exactly as upstream ships it. We deliberately do NOT patch it: an earlier `is_proot`
  gate + assert broke the CI rootfs bake, where `is_proot` is `True` for the Android tiers too
  (`vars/local_vars_android_*.yml`) but no dash-node runs -- so the bake must download the base
  maps itself, which the stock role does. `is_proot` cannot tell "device with dash-node" from
  "CI bake without it"; `creates:` needs no such flag and is correct in both. Rationale:
  `controller/docs/ADR-K2GO-394-maps-download-via-dashnode.md`. (The search tarball is the one
  map file NOT delegated -- dash-node does not extract archives -- so it still downloads and
  extracts in-proot as upstream does.)
