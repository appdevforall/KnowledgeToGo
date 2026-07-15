#!/usr/bin/env bash
# Fetch a pinned Mozilla pdf.js prebuilt distribution into ./dist for deployment.
# pdf.js is pure JS (Apache-2.0) -> no 32/64-bit concern; the same dist serves every ABI.
# Run at rootfs-build time on a networked host; the extracted dist is then deployed to
# /library/www/html/pdfjs (see the iiab-android setup script).
set -euo pipefail

# Pin -> CONFIRM this is the version we want before release.
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
