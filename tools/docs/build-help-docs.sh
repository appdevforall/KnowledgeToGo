#!/usr/bin/env bash
# =============================================================================
# build-help-docs.sh  (K2GO-406)
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
#
# Turn the Help&Manual "webhelp" export into the static manual bundle we ship.
#   Input  : static/k2go-docs/K2Go-webhelp*.zip  (the H&M export dropped in by the docs team;
#            the newest K2Go-webhelp*/K2Go-help* match under static/ is auto-located, so a
#            changed filename tail still works; or pass an explicit path as $1)
#   Output : static/k2go-docs/content/      (the manual; index.html at the root of content/)
#
# LAYOUT -- the manual lives in its OWN subdir (content/), kept SEPARATE from the module
# infra that sits at static/k2go-docs/ root (README.md, k2go-docs-nginx.conf). So a copy of
# the manual (into the APK assets, or into the box web root) takes content/ only and never
# drags the README or the nginx config into the documentation bundle.
#
# WHY THIS EXISTS: the H&M export ships ASP.NET server-side page variants (*.aspx) and a
# redundant oldfavicon.ico that a static WebView never loads. Stripping them in one
# committed, reviewable script keeps the cleanup DETERMINISTIC: every new zip is processed
# the same way, no per-drop guesswork. Re-running is idempotent (content/ is rebuilt from
# scratch each time). The static WebView uses the .htm/.html variants; H&M's own JS selects
# those when it is not served by ASP.NET.
#
# Usage:
#   tools/docs/build-help-docs.sh                     # uses static/help/K2Go-help.zip
#   tools/docs/build-help-docs.sh path/to/other.zip   # explicit zip
# After running, review the diff and commit static/k2go-docs/content/.
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT="$REPO/static/k2go-docs/content"      # manual only; infra stays at static/k2go-docs/ root

# H&M cruft a static WebView never uses: ASP.NET server pages + a redundant favicon.
CRUFT_GLOBS=( "*.aspx" "oldfavicon.ico" )

log(){ printf '\033[1;36m[help-docs]\033[0m %s\n' "$*" >&2; }
die(){ printf '\033[1;31m[help-docs] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# Resolve the source zip: an explicit arg ($1), else the newest K2Go-webhelp*.zip (fallback
# K2Go-help*.zip) dropped anywhere under static/ -- a changed filename tail still matches.
ZIP="${1:-}"
if [[ -z "$ZIP" ]]; then
  ZIP="$(find "$REPO/static" -maxdepth 3 -type f \( -iname 'K2Go-webhelp*.zip' -o -iname 'K2Go-help*.zip' \) -printf '%T@\t%p\n' 2>/dev/null | sort -rn | head -1 | cut -f2-)"
fi
[[ -n "$ZIP" && -f "$ZIP" ]] || die "no help zip found (K2Go-webhelp*.zip or K2Go-help*.zip) under static/"
log "Source zip: ${ZIP#"$REPO/"}"

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
log "Unzipping $(basename "$ZIP") ..."
unzip -o -q "$ZIP" -d "$TMP"

# Locate the webhelp root (the dir that holds index.html) -- tolerate any wrapper name.
if [[ -f "$TMP/index.html" ]]; then
  SRC="$TMP"
else
  SRC="$(dirname "$(find "$TMP" -maxdepth 2 -name index.html | head -1)")"
fi
[[ -n "$SRC" && -f "$SRC/index.html" ]] || die "no index.html found inside the zip"

log "Stripping cruft: ${CRUFT_GLOBS[*]}"
for g in "${CRUFT_GLOBS[@]}"; do
  find "$SRC" -type f -name "$g" -delete
done

log "Rebuilding $OUT (manual only; infra at the parent dir is untouched) ..."
rm -rf "$OUT"
mkdir -p "$OUT"
cp -r "$SRC/." "$OUT/"

files="$(find "$OUT" -type f | wc -l | tr -d ' ')"
aspx="$(find "$OUT" -name '*.aspx' | wc -l | tr -d ' ')"
[[ -f "$OUT/index.html" ]] || die "index.html missing from the built content/"
[[ "$aspx" == "0" ]] || die "cruft strip failed: .aspx files remain"
log "Done: ${files} files in static/k2go-docs/content/ (index.html at its root); .aspx = 0."
