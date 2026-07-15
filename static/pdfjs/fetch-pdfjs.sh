#!/usr/bin/env bash
# Fetch a pinned Mozilla pdf.js prebuilt distribution into ./dist for deployment.
# pdf.js is pure JS (Apache-2.0) -> no 32/64-bit concern; the same dist serves every ABI.
# Run at rootfs-build time on a networked host; the extracted dist is then deployed to
# /library/www/html/pdfjs (see the iiab-android setup script).
set -euo pipefail

# Pinned pdf.js version. pdf.js ships a stable dist ~monthly and uses semver, so a MAJOR
# bump can break the viewer API. Keep this pinned and bump it DELIBERATELY — primarily when
# a pdf.js security advisory lands (it has had RCE CVEs), not on every release. Also confirm
# the pin runs on the oldest Android System WebView we target (app minSdk = 24 / Android 7;
# recent pdf.js majors need a modern Chromium WebView). Use the newest STABLE that is still
# compatible — never a nightly/beta.
# TODO(Luis): confirm the version to ship (marker below is a placeholder, not verified).
PDFJS_VERSION="${PDFJS_VERSION:-4.10.38}"
# Optional integrity check: set PDFJS_SHA256 to the expected zip checksum to enforce it.
PDFJS_SHA256="${PDFJS_SHA256:-}"

HERE="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="${HERE}/dist"
ZIP_URL="https://github.com/mozilla/pdf.js/releases/download/v${PDFJS_VERSION}/pdfjs-${PDFJS_VERSION}-dist.zip"
TMP_ZIP="$(mktemp -t pdfjs-XXXXXX.zip)"
trap 'rm -f "$TMP_ZIP"' EXIT

echo "Fetching pdf.js v${PDFJS_VERSION} ..."
curl -fSL "$ZIP_URL" -o "$TMP_ZIP"

if [ -n "$PDFJS_SHA256" ]; then
  echo "${PDFJS_SHA256}  ${TMP_ZIP}" | sha256sum -c -
fi

rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"
unzip -q "$TMP_ZIP" -d "$DIST_DIR"
# Sanity: the viewer the app points to must exist.
test -f "${DIST_DIR}/web/viewer.html" || { echo "ERROR: web/viewer.html missing from dist"; exit 1; }
echo "pdf.js dist ready at ${DIST_DIR} (web/viewer.html present)."
