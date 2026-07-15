# pdf.js viewers (ADFA-4708) — in-WebView PDF viewing

The Android System WebView has no built-in PDF viewer, so a PDF served by the box would
otherwise be ignored (or downloaded). This module serves Mozilla **pdf.js** from the local
nginx so the portal WebView can render PDFs inline and offline.

## Why multiple builds
We never modify the device ROM, so the System WebView is whatever the device ships/updates
to. A modern WebView runs the latest pdf.js; a frozen/old one does not. So we ship more than
one pinned build and let the **app pick per device**, driven by `manifest.json`:

- **v6** — needs Chromium/WebView ≥ 118 (latest; receives upstream security fixes).
- **v4** — needs Chromium/WebView ≥ 88 (best-effort for old/frozen devices; **tentative**).

Devices below the lowest build simply fall back to downloading the PDF — nothing breaks.

## Layout (produced by fetch-pdfjs.sh, deployed to /library/www/html/pdfjs)
```
/pdfjs/manifest.json          # {schema, builds:[{id,viewerPath,minWebViewMajor,version,tentative}]}
/pdfjs/6/web/viewer.html      # pdf.js v6
/pdfjs/4/web/viewer.html      # pdf.js v4
```
The app reads the manifest and routes a PDF to the best build:
`/pdfjs/<id>/web/viewer.html?file=<encoded pdf url>`.

## Assets (dist)
The prebuilt distributions are fetched at rootfs-build time, not vendored in git:

    ./fetch-pdfjs.sh   # writes ./dist/<id>/ + ./dist/manifest.json

Pin versions in the `VERSIONS` table at the top of `fetch-pdfjs.sh`. pdf.js is pure JS
(Apache-2.0) → one set of files serves ARM 32- and 64-bit.

## Removing a build (e.g. drop v4 by policy: maintained software only)
The app is **data-driven** off the manifest — there is no version-specific app code. To
remove a build cleanly (full teardown, no stale files or dead code):
1. Delete its line from `VERSIONS` in `fetch-pdfjs.sh`.
2. Rebuild. The installer wipes `/library/www/html/pdfjs` before redeploying, so the removed
   build disappears, the manifest no longer lists it, and the app stops routing to it.
Devices that relied on it fall back to downloading the PDF.

## Update cadence
pdf.js ships a stable dist ~monthly and uses semver (a **major** bump can break the viewer
API). Keep versions **pinned** and bump deliberately — primarily on a pdf.js **security
advisory**, not on every release.
