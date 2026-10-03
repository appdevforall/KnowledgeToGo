# ADR: Content-updater self-update without a rootfs rebake (K2GO-440)

Status: Proposed

## Context

The box runs three content updaters, driven by dash-node over REST (localhost):
Forgejo repos refresh, Code on the Go add-ons refresh, and Code on the Go
build-assets refresh. Each is a detached wrapper in `tools/` that calls the
script doing the real work.

dash-node already self-updates. `POST /system/dashboard/rebuild` runs
`tools/rebuild-dashboard.sh`, which does `git fetch` + `git reset --hard
origin/<branch>` on the whole on-device clone at `/opt/iiab-android`, then
blue-green rebuilds `static/dashboard`. The reset refreshes the ENTIRE clone
working tree, not only the dashboard.

Today the three updaters are not consistent:

- Forgejo refresh sources its orchestration from
  `/opt/iiab-android/static/forgejo/orchestration`: inside the self-updating
  clone. A change ships through self-update, no rebake.
- The add-ons and build-assets roles exist ONLY in Knowledge to Go, as overlays
  in our repo (`tools/upstream-patches/overlays/roles/<role>/`). They are NOT
  IIAB upstream roles, and they are never fetched from IIAB: that is the whole
  point of these two roles. At rootfs-build (bake) time our overlay-apply copies
  each role into the on-box ansible roles directory,
  `/opt/iiab/iiab/roles/<role>/` (just the location where `runrole` looks,
  alongside IIAB's own roles): the content there is ours. The add-ons and
  build-assets wrappers read the mirror from that copied-in location, which the
  overlay-apply refreshes ONLY at bake. The authoritative source,
  `tools/upstream-patches/overlays/roles/<role>/files/mirror_*.py`, also sits in
  the self-updating clone, but the wrapper does not use that copy.

So a two-line fix to an add-ons or build-assets mirror forces a fleet rebake
today. Not because the role comes from upstream (it does not): purely because the
overlay is copied into the ansible roles directory only at bake. Forgejo already
avoids this by reading from the clone.

## Decision

Point the add-ons and build-assets refresh wrappers at the mirror copy in the
self-updating clone, with a fallback to the baked copy. This mirrors what the
Forgejo wrapper already does with its orchestration. A change to a mirror then
ships through dash-node's existing self-update (`git reset`) and the live
refresh uses it at once: no rebake, and no dash-node version bump (the wrapper
and the mirror are not `static/dashboard`; the same `git reset` carries them).

Single source of truth: our repo. The overlay roles (add-ons, build-assets) and
the Forgejo patch live only in our tree. We do not take the role from IIAB
upstream, and updating these needs no upstream change. There is therefore NO
second source to arbitrate: the only duality is "fresh clone copy" vs "stale
baked copy", resolved by a fixed preference (clone first, baked fallback). We do
NOT build version arbitration between repositories ("the newer of A vs B wins").
That complexity is explicitly rejected.

## Scope (minimal)

- `tools/code-addons-refresh.sh` and `tools/code-assets-refresh.sh`: resolve
  `MIRROR` as the clone copy when present, else the baked copy.
- No change to the role `install.yml`. Bake and on-demand `runrole` still run the
  overlay's copy in the on-box ansible roles directory (placed there at bake);
  the no-rebake benefit targets the LIVE refresh, which is where minor changes
  are consumed. A first install right after a self-update uses that copied-in
  mirror once; the next refresh uses the clone copy.
- No version or identifier per script, and no ahead/behind reporting: not needed
  for the benefit (YAGNI). If a box ever needs to report which updater version
  it runs, add it then.

## Forward-compatibility: rolling box vs pinned APK (boundary plus follow-up)

dash-node and the rootfs now update independently of the APK (self-update, plus
this change). The APK is pinned per install. This creates brain (box) / body
(APK) version skew: a newer box can run updater logic an older APK was not built
to drive.

Boundary this ADR sets, so K2GO-440 does not make the skew worse:

- A self-update to an updater MUST preserve the REST contract and the
  status/JSON shape that shipped APKs parse: the endpoint paths, the
  `done: N downloaded, R reused, K failed` and `result: up-to-date` log lines,
  and the status fields. An additive, contract-preserving change ships freely
  via self-update.
- A change that BREAKS that contract is gated by the mechanism that already
  exists: bump the dash-node version and raise the per-module minimum in
  `DashNodeRequirement` (the app already checks it and degrades gracefully). A
  contract break is therefore never silent.

The general policy for a rolling box against a stale APK (capability
negotiation, a box-declared minimum APK, an "update your app" prompt) is larger
than K2GO-440 and is deferred to its own ticket to analyze. This ADR only fixes
the boundary above so current changes stay safe.

## Consequences

- add-ons and build-assets updaters become fixable without a rebake, like
  Forgejo.
- The install and bake path is unchanged (baked copy), so a fresh install is
  unaffected.
- The repo is the single source: no upstream dependency, no cross-repo
  arbitration.
- The brain/body skew is bounded (contract stability plus the existing version
  gate); the general policy is a separate follow-up ticket.
