# Workflows

GitHub runs only the `*.yml` files in this folder. Retired workflows live in
`../deprecated-workflows/` (review for deletion before 1.3.0).

Name pattern: `Area: N. action`, and the file name follows it (`area-N-action.yml`). A number
means a chain: run the steps in order, or one step calls the next. `Test+` marks a standalone check.

| Name in Actions | File | When it runs | What it changes |
|---|---|---|---|
| App+Play: 1. send candidate to Play Console | `app-play-1-candidate.yml` | Manual, from `main` | Sends the Play AAB to Google review, tags `vX.Y.Z-rc.N`, archives the AAB to R2 |
| App+Play: 2. publish release | `app-play-2-release.yml` | Manual, after Google approves the candidate | Final AAB to the Play beta track, final tag, GitHub Release, website APKs on R2, OTA `update.json` |
| App: Firebase distribution | `app-firebase.yml` | Each push to `main` or to a branch with a ticket key | Tester build on Firebase |
| Bins: 1. build static (PRoot) | `bins-1-build.yml` | Weekly (Sunday) and manual | New `binaries-*` release; then calls step 2 |
| Bins: 2. archive + retention | `bins-2-archive.yml` | Called by step 1, or manual (dry run option) | Archives binaries to R2, prunes old releases |
| Content: publish catalogs | `content-catalogs.yml` | Weekly (Monday) and manual | Content catalogs on R2 |
| Deps: mirror upstream to R2 | `deps-upstream-mirror.yml` | Weekly (Sunday) and manual | Backup copy of upstream build deps on R2 |
| RootFS: 1. pre-build tests | `rootfs-1-prebuild-tests.yml` | Each PR to `main`; also called by step 2 | Nothing (checks only) |
| RootFS: 2. bake + upload | `rootfs-2-bake.yml` | Manual (`test`, `dev`, `publish` modes) | `test`: nothing. `dev`: `dev_latest_*` on R2. `publish`: stable `latest_*` (production) |
| RootFS: 3. promote | `rootfs-3-promote.yml` | Manual (dry run by default) | Copies `dev_latest_*` (or a dated image) to the stable `latest_*` |
| Test+App: CI checks | `test-app-ci.yml` | Each push or PR to `main`/`development` (not docs-only) | Nothing (lint, unit tests, debug build) |
| Test+Bins: bump check | `test-bins-bump.yml` | PR that changes the binaries pin | Nothing (pin and manifest must change together) |
| Test+Dash: typecheck | `test-dash-typecheck.yml` | Push or PR that touches `static/dashboard/` | Nothing (typecheck and unit tests) |
