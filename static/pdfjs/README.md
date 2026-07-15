# pdf.js viewer (ADFA-4708) — in-WebView PDF viewing

The Android System WebView has no built-in PDF viewer, so a PDF served by the box would
otherwise be ignored (or downloaded). This module serves Mozilla **pdf.js** from the local
nginx so the portal WebView can render PDFs inline and offline.

## How it works
- Served at `http://localhost:8085/pdfjs/` (see `pdfjs-nginx.conf`).
- The app routes an internal-host PDF to `…/pdfjs/web/viewer.html?file=<encoded pdf url>`
  (see `PdfPolicy` / `PdfViewerUrl` in the controller app). Same origin as the box's PDFs,
  so there is no CORS / mixed-content problem and it works with no internet.
- pdf.js is pure JavaScript (Apache-2.0) — the same dist serves ARM 32- and 64-bit.

## Assets (dist)
The prebuilt pdf.js distribution is fetched at rootfs-build time, not vendored in git:

    PDFJS_VERSION=4.10.38 ./fetch-pdfjs.sh   # writes ./dist (web/ + build/)

Pin `PDFJS_VERSION` (and optionally `PDFJS_SHA256`) to the release we ship. The extracted
`dist/` is deployed to `/library/www/html/pdfjs/` so `/pdfjs/web/viewer.html` resolves.

## Build / rootfs
The `iiab-android` setup script installs `pdfjs-nginx.conf` into the :8085 server and
deploys the dist to the web root (same mechanism as the other static modules). All editions.
