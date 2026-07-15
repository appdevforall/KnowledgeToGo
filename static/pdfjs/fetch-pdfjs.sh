#!/usr/bin/env bash
# Fetch pinned Mozilla pdf.js prebuilt distributions into ./dist and emit a manifest the
# app reads to pick a viewer per device. pdf.js is pure JS (Apache-2.0) -> ABI-agnostic
# (one set of files serves ARM 32 and 64). Run at rootfs-build time on a networked host.
#
# WHY TWO VERSIONS: the Android System WebView is whatever the device has (we never touch
# the ROM). A modern WebView runs the latest pdf.js; a frozen/old one does not. So we ship
# a newer build AND an older build, and the app routes by the device's WebView major:
#   - v6  -> needs Chromium/WebView >= 118  (latest; receives upstream security fixes)
#   - v4  -> needs Chromium/WebView >= 88   (best-effort for old/frozen devices; TENTATIVE)
# The app is data-driven off manifest.json, so REMOVING a build is just deleting its row
# from VERSIONS below: it won't be fetched, won't be in the manifest, and the app ignores
# it — no dead code, no stale files (the installer wipes the web dir before redeploying).
#
# To drop v4 later (e.g. policy: maintained software only): delete its VERSIONS line. Done.
set -euo pipefail

# id : version : minWebViewMajor : [flags]
#  - id           = served subdir and manifest id (/pdfjs/<id>/web/viewer.html)
#  - version      = pinned pdf.js release (CONFIRM before shipping)
#  - minWebViewMajor = minimum Chromium/WebView major that build supports
#  - flags        = optional, comma-separated (e.g. "tentative")
VERSIONS=(
  "6:6.1.200:118:"
  "4:4.10.38:88:tentative"
)

HERE="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="${HERE}/dist"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

manifest_rows=()
for row in "${VERSIONS[@]}"; do
  IFS=':' read -r id version minmajor flags <<< "$row"
  zip_url="https://github.com/mozilla/pdf.js/releases/download/v${version}/pdfjs-${version}-dist.zip"
  tmp_zip="$(mktemp -t "pdfjs-${id}-XXXXXX.zip")"
  echo "Fetching pdf.js v${version} (build '${id}', min WebView ${minmajor}) ..."
  curl -fSL "$zip_url" -o "$tmp_zip"
  mkdir -p "${DIST_DIR}/${id}"
  unzip -q "$tmp_zip" -d "${DIST_DIR}/${id}"
  rm -f "$tmp_zip"
  test -f "${DIST_DIR}/${id}/web/viewer.html" \
    || { echo "ERROR: web/viewer.html missing for build '${id}'"; exit 1; }
  tentative="false"; [ "${flags:-}" = "tentative" ] && tentative="true"
  manifest_rows+=("    {\"id\":\"${id}\",\"viewerPath\":\"/pdfjs/${id}/web/viewer.html\",\"minWebViewMajor\":${minmajor},\"version\":\"${version}\",\"tentative\":${tentative}}")
done

# Emit manifest.json from exactly what was fetched.
{
  echo '{'
  echo '  "schema": "pdfjs-viewers-v1",'
  echo '  "builds": ['
  n=${#manifest_rows[@]}
  for i in "${!manifest_rows[@]}"; do
    sep=","; [ "$i" -eq "$((n-1))" ] && sep=""
    echo "${manifest_rows[$i]}${sep}"
  done
  echo '  ]'
  echo '}'
} > "${DIST_DIR}/manifest.json"

echo "pdf.js dist ready:"
ls -1 "$DIST_DIR"
echo "--- manifest.json ---"
cat "${DIST_DIR}/manifest.json"
