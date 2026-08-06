#!/usr/bin/env python3
# ============================================================================
# ADFA-4954 - Build the offline Kolibri channel catalog
#             -> src/main/assets/kolibri_catalog.jsonl
#
# Why this exists at all: Kolibri Studio's channel endpoint inlines every
# thumbnail as base64 and offers no way to opt out (ValuesViewset fixes the field
# set on the class; passing ?fields= changes nothing - measured byte-identical).
# The full page is 4.1 MB, of which 97% is thumbnails, for 119 KB of content the
# picker actually uses. So the catalog is built here, on a machine with
# bandwidth, and shipped in the APK. See ADR-4954 D1.
#
# Output is JSON Lines, one object per line:
#   line 1  {"catalog":"kolibri","generated":"YYYY-MM-DD","count":N,"source":URL}
#   line 2+ {"id","version","name","description","author","lang_code","lang_name",
#            "total_resource_count","published_size","root"}
#
# JSONL rather than CSV for the reason ADR-4853 gave for the Books catalog:
# channel names and descriptions carry commas, quotes and accents in every script
# Kolibri publishes in, and a naive CSV split corrupts rows.
#
# Usage:
#   python3 build_kolibri_catalog.py                 # fetch live -> JSONL
#   python3 build_kolibri_catalog.py --from-file F   # parse a saved JSON dump
#   python3 build_kolibri_catalog.py --out PATH      # write somewhere else
#
# Gradle runs this only on release builds (assembleRelease/bundleRelease); run it
# by hand any time with `./gradlew refreshKolibriCatalog`. It never fails the
# build: on any error the committed asset is left untouched.
# ============================================================================
import argparse
import datetime
import json
import os
import sys
import urllib.request

STUDIO = "https://studio.learningequality.org"
ENDPOINT = "/api/public/v2/channel/"
PAGE_SIZE = 250          # well under the server's max_page_size of 1000
TIMEOUT = 60
UA = "Mozilla/5.0 (K2Go catalog builder)"

# Everything the picker needs, and nothing else. In particular NOT 'thumbnail'
# (97% of the payload), nor 'categories'/'countries' (Studio's public library
# leaves both empty - verified against /channel/labels/), nor the housekeeping
# fields 'available', 'public', 'library', 'token', 'tagline', 'last_updated',
# 'last_published', 'num_coach_contents', 'included_languages'.
KEEP = [
    "id",
    "version",
    "name",
    "description",
    "author",
    "lang_code",
    "lang_name",
    "total_resource_count",
    "published_size",
    "root",
]

DEFAULT_OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "assets",
    "kolibri_catalog.jsonl")


def fetch_page(page):
    url = "{}{}?public=true&page_size={}&page={}".format(
        STUDIO, ENDPOINT, PAGE_SIZE, page)
    req = urllib.request.Request(url, headers={"User-Agent": UA,
                                               "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return json.loads(r.read().decode("utf-8"))


def fetch_all():
    """Every public channel, following pagination. Returns a list of raw rows."""
    rows, page = [], 1
    while True:
        body = fetch_page(page)
        got = body.get("results") or []
        rows.extend(got)
        total_pages = body.get("total_pages") or 1
        print("  page {}/{}: {} channels".format(page, total_pages, len(got)))
        if page >= total_pages or not got:
            break
        page += 1
    return rows


def load_dump(path):
    """A saved response: either {results:[...]} or a bare list."""
    with open(path, "r", encoding="utf-8") as f:
        body = json.load(f)
    if isinstance(body, list):
        return body
    return body.get("results") or []


def trim(row):
    """Keep the fields we use, coerce the types, drop anything unusable."""
    if not isinstance(row, dict):
        return None
    cid = str(row.get("id") or "").strip().lower()
    if len(cid) != 32:
        return None                      # not a channel id; skip the row
    out = {}
    for k in KEEP:
        v = row.get(k)
        if k in ("version", "total_resource_count"):
            try:
                v = int(v or 0)
            except (TypeError, ValueError):
                v = 0
        elif k == "published_size":
            try:
                v = int(v or 0)
            except (TypeError, ValueError):
                v = 0
        else:
            v = "" if v is None else str(v).strip()
        out[k] = v
    out["id"] = cid
    return out


def dedupe(rows):
    """One entry per channel id, keeping the highest published version."""
    best = {}
    for r in rows:
        cur = best.get(r["id"])
        if cur is None or r["version"] > cur["version"]:
            best[r["id"]] = r
    return list(best.values())


def write(rows, out_path, source):
    rows = sorted(rows, key=lambda r: (r["name"].lower(), r["id"]))
    header = {
        "catalog": "kolibri",
        "generated": datetime.date.today().isoformat(),
        "count": len(rows),
        "source": source,
    }
    tmp = out_path + ".tmp"
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(json.dumps(header, ensure_ascii=False, sort_keys=True) + "\n")
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n")
    os.replace(tmp, out_path)
    return os.path.getsize(out_path)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--from-file", dest="from_file",
                    help="parse a saved JSON response instead of fetching")
    ap.add_argument("--out", default=os.path.normpath(DEFAULT_OUT))
    args = ap.parse_args()

    if args.from_file:
        print(">> [kolibri catalog] reading {}".format(args.from_file))
        raw = load_dump(args.from_file)
        source = "file:" + os.path.basename(args.from_file)
    else:
        print(">> [kolibri catalog] fetching {}{}".format(STUDIO, ENDPOINT))
        raw = fetch_all()
        source = STUDIO + ENDPOINT

    trimmed = [t for t in (trim(r) for r in raw) if t]
    dropped = len(raw) - len(trimmed)
    rows = dedupe(trimmed)

    if not rows:
        print(">> [kolibri catalog] no usable channels; keeping the existing asset",
              file=sys.stderr)
        return 1

    size = write(rows, args.out, source)
    print(">> [kolibri catalog] {} channels -> {} ({:.0f} KB)".format(
        len(rows), args.out, size / 1024.0))
    if dropped:
        print(">> [kolibri catalog] skipped {} unusable row(s)".format(dropped))
    if len(trimmed) != len(rows):
        print(">> [kolibri catalog] collapsed {} duplicate id(s)".format(
            len(trimmed) - len(rows)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
