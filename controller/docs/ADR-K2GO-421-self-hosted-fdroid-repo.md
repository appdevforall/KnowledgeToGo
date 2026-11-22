# ADR-K2GO-421 : Self-hosted F-Droid repository

- Status: Accepted (decisions of the maintainer, 2026-11-21)
- Date: 2026-09-24 (draft), 2026-11-21 (decisions and implementation)
- Ticket: K2GO-421 (relates to K2GO-399, "Acceptable to F-Droid?")
- Author: AppDevForAll

## Decision in one line

Publish a fdroidserver-generated F-Droid repository of the `fdroid` flavor on the Cloudflare R2
bucket `k2go-apk-repo` under the prefix `fdroid/`, signed with the app signing key, as an update
channel that we control while the official f-droid.org inclusion (K2GO-399) goes on in parallel.

## Context (facts, verified 2026-11-21)

- The release job of `app-play-2-release.yml` builds `standard` and `fdroid` and uploads both
  APKs to the root of `k2go-apk-repo` (`K2Go-<tag>-release.apk`, `K2Go-<tag>-fdroid-release.apk`).
  Public host: `https://k2go-download.appdevforall.org`.
- The `fdroid` flavor has no Firebase and no in-app OTA updater. Crash reports (GlitchTip) start
  only after the user turns them on (`CrashReportConsent`, default off, K2GO-579).
- The `standard` APK and the `fdroid` APK have the same signer (apksigner, v1.2.0):
  `3a11e9f345e8b4ef79ccb8c819ca0d5392dd8f3febe26abb089c5e2e022fefb5`. A website install and a
  repository install can update each other. A Play install cannot: Google signs it with another key.
- An F-Droid repository is static files. The client reads `repo/entry.jar`, then the index that
  entry.jar names with its SHA-256 (`index-v2.json`, or `index-v1.jar` for old clients), then
  APKs and icons at fixed paths. It never asks for a directory listing. So R2 is sufficient.
- R2 has no directory listing and no implicit `index.html`. Only a person in a browser needs one.
  A generated landing page closes that gap, the same as the add-ons gallery
  (`appdevforall/plugin-examples`, `tools/addons`).
- Without the previous index, `fdroid update` sets the "added" date of every old version to the
  date of the run (tested with fdroidserver 2.4.2).
- `static/` is not a public web root (box nginx, LAN only). It is not used here.

## Decisions

- D1. URL. Bucket `k2go-apk-repo`, prefix `fdroid/`:
  repository `https://k2go-download.appdevforall.org/fdroid/repo`, landing page
  `https://k2go-download.appdevforall.org/fdroid/index.html`. Manual test runs write
  `fdroid-staging/`, a separate repository with its own URL.
  The QR code of fdroidserver holds the repository address (`.../fdroid/repo?fingerprint=...`).
  The F-Droid app reads it, but a phone camera opens it in a browser, and R2 has no object at that
  key. So the landing page is also uploaded at the key `fdroid/repo` (no slash). R2 keys are flat:
  that key and the `fdroid/repo/...` keys exist side by side, and the F-Droid app never asks for it.
  The "Add to F-Droid" button and the QR code on the landing page use
  `https://fdroid.link/#<repo address>?fingerprint=<fingerprint>`. The F-Droid app claims
  `fdroid.link` as a verified App Link, so Android opens the app directly. Without the app, the
  browser shows fdroid.link. The repo address is in the fragment: the browser does not send it to
  fdroid.link. (The app claims `fdroidrepos://` for HTTPS repos, but no longer `fdroidrepo://` for
  HTTP repos, so a scheme link fails in a local HTTP test.)
- D2. Flavor. The repository ships the `fdroid` flavor. The F-Droid client does the updates, and
  this flavor has no in-app updater, so two updaters never compete. (The draft said `standard`.)
- D3. One key. The repository index is signed with the app signing key: the same keystore and the
  same GitHub secrets (`KEYSTORE_BASE64`, `KEY_ALIAS`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`). The
  repository fingerprint is then the fingerprint of the app certificate: one fingerprint to check.
  - Consequence: a change of this key changes the repository fingerprint. Each user must then
    remove the repository and add it again with the new fingerprint. The config change is one line.
  - The run checks that the release APK is signed by the key that signs the index, so an APK
    signed by another key is never published.
- D4. R2 holds the state. CI keeps nothing between runs. Each run pulls `fdroid/repo/` and
  `fdroid/archive/` (indexes and APKs), adds the release APK, runs `fdroid update`, and uploads.
  The localized images (`repo/<app id>/`) are not pulled: they come from `metadata/` on each run.
- D5. Upload order. APKs and images, then the indexes, then `entry.jar`, then the landing page.
  Deletions come last. The same rule as `update.json` (ADFA-4984): a published file never names a
  file that is not in R2 yet. Before the upload, the run also checks that each file that the new
  index names is in the upload.
- D6. History. `archive_older: 3`: the three newest versions in `repo/`, older ones in `archive/`
  (also published).
- D7. Cache. `Cache-Control: public, max-age=60` on indexes, `entry.jar` and pages;
  one year, immutable, on APKs (the name carries the version) and hashed icons.
- D7b. Look. The landing page uses the look of the add-ons site (`addons.appdevforall.org`,
  also static files on R2): the same `styles.css`, logo, header and footer, copied from
  `appdevforall/plugin-examples` `site/` into `tools/fdroid-repo/site/`. As on the add-ons site,
  the page links its CSS and SVG under `assets/` with a content hash in the name, because the zone
  can keep these file types in a browser for hours. The app icon on the page is an SVG that each
  run makes from the adaptive icon of the app (`res/drawable/ic_launcher_*.xml`), so the icon has
  one source and stays sharp on a phone. The F-Droid index keeps the PNG icon.
- D8. Metadata. `tools/fdroid-repo/metadata/org.appdevforall.k2go.yml`: license
  `GPL-3.0-or-later`, source `https://github.com/appdevforall/KnowledgeToGo`, no anti-feature (no analytics, crash reports are opt-in).
- D9. Workflow. `app-fdroid-repo.yml`: called by `app-play-2-release.yml` after the release job
  (live `fdroid/`), and manual (`staging` on by default) for a tag whose APK is on R2.
  Release candidates never go to the repository. `fdroid update` runs in `debian:trixie` with
  fdroidserver from apt. One run at a time (concurrency group `fdroid-repo`).

## Before the first live publish

- Run the workflow by hand with `staging` on, add the staging repository in the F-Droid app on a
  phone, install, and check the fingerprint.
- Cloudflare: the zone can keep `.jar` files in its cache longer than `Cache-Control` says (the
  add-ons work found that the Browser Cache TTL of the zone wins for file types that Cloudflare
  caches by default). Add a cache rule for `/fdroid/repo/entry.jar`, `/fdroid/repo/index*` and the
  same paths under `/fdroid/archive/` that respects the origin headers. Check `cf-cache-status`
  and `age` after the first publish.
- Optional: a URL rewrite so that `/fdroid/` serves `/fdroid/index.html`.

## Out of scope

- Official f-droid.org submission and reproducible builds (K2GO-399). Note: an f-droid.org source
  build needs the prebuilt native binaries that Gradle fetches from a GitHub Release
  (`syncNativeArtifacts`). That is a concern for the official path, not for this repository.
- Removal or change of the OTA updater of the `standard` flavor.

## References

- Tool and local test: `tools/fdroid-repo/README.md`.
- Workflows: `.github/workflows/app-fdroid-repo.yml`, `.github/workflows/app-play-2-release.yml`.
- Pattern for a static site on R2: `appdevforall/plugin-examples`, `tools/addons`,
  `.github/workflows/publish-addons.yml`.
- F-Droid umbrella: K2GO-399.
