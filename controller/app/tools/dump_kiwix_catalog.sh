#!/usr/bin/env bash
# ============================================================================
# ADFA-4849 — Dump the full Kiwix ZIM catalog listings for OFFLINE processing.
#
# Run this where download.kiwix.org is reachable (your machine). It saves the RAW
# HTML of every category directory (with a marker line per category that also
# carries the .zim count), gzips it, and that single file is what you send back.
# Claude then parses it offline into the baked catalog asset — no on-device
# scraping, no continuous requests.
#
# Note on redirects: download.kiwix.org (MirrorBrain) redirects FILE downloads to a
# mirror, but serves DIRECTORY LISTINGS itself, so -L just fetches the index here.
# If a category comes back empty (blocked), we fall back to the dotsrc mirror.
#
# Usage:   bash dump_kiwix_catalog.sh
# Sends:   kiwix_listings.txt.gz   (a few MB raw -> ~a few hundred KB gzipped)
# ============================================================================
set -u

PRIMARY="https://download.kiwix.org/zim"
MIRROR="https://mirrors.dotsrc.org/kiwix/zim"
UA="Mozilla/5.0 (K2Go Downloader)"

# The 23 categories (matches your table).
CATS="devdocs freecodecamp gutenberg ifixit libretexts maps mooc other phet psiram \
stack_exchange ted videos vikidia wikibooks wikinews wikipedia wikiquote wikisource \
wikiversity wikivoyage wiktionary zimit"

OUT="kiwix_listings.txt"
: > "$OUT"
total=0

zim_count() { grep -oiE 'href="[^"]+\.zim"' | wc -l | tr -d ' '; }

for c in $CATS; do
  echo ">>> fetching $c ..." >&2
  src="$PRIMARY"
  html="$(curl -sL -A "$UA" "$PRIMARY/$c/")"
  n="$(printf '%s' "$html" | zim_count)"
  if [ "$n" = "0" ]; then
    echo "    primary empty; trying mirror" >&2
    src="$MIRROR"
    html="$(curl -sL -A "$UA" "$MIRROR/$c/")"
    n="$(printf '%s' "$html" | zim_count)"
  fi
  # Marker: tab-separated (category, count, source) so it is trivial to split offline.
  printf '===CATEGORY\t%s\t%s\t%s===\n' "$c" "$n" "$src" >> "$OUT"
  printf '%s\n' "$html" >> "$OUT"
  printf '%-16s %s\n' "$c" "$n" >&2
  total=$((total + n))
  sleep 1   # be polite to the server
done

printf '%-16s %s\n' "TOTAL" "$total" >&2
gzip -f "$OUT"
echo "" >&2
echo "WROTE: $(pwd)/${OUT}.gz  — send this file back to Claude." >&2
