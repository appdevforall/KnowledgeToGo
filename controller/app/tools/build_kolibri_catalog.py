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
import gzip
import json
import os
import sqlite3
import sys
import tempfile
import urllib.request

STUDIO = "https://studio.learningequality.org"
ENDPOINT = "/api/public/v2/channel/"
# Published channel content DB — the same artifact Kolibri's remoteimport pulls; it
# holds the whole tree + file sizes, so we read it instead of crawling the public
# contentnode_tree API (which 400s on many channels and only returns shallow data).
DB_URL = STUDIO + "/content/databases/{}.sqlite3"
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

DEFAULT_TREE_OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "assets",
    "kolibri_tree.jsonl")


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


# ---- Tree dump / sizing (ADFA-5094) ---------------------------------------
# The channel LIST above is tiny; the offline-BROWSE feature needs the TOPIC
# TREE too. Rather than crawl the public contentnode_tree API (which 400s on many
# channels and returns only shallow data), download each channel's published
# content DB — the same .sqlite3 Kolibri's remoteimport pulls — and read the whole
# tree straight from content_contentnode + content_file, exactly as the box's
# buildLocalSubtree does locally. One download per channel, complete and reliable.
# Two variants let us weigh bundle size against coverage:
#   full   - every node (topics + resource leaves), lean fields incl. own bytes.
#   topics - only the folders; each carries its subtree resource count + bytes plus
#            the count/bytes of its DIRECT loose resources (dcount/dbytes), so a
#            mixed level reads "N resources here" without listing leaves. An order
#            of magnitude smaller; the live source fills the leaf titles online.
# `--measure` builds both once and prints raw + gzip sizes and counts without
# writing, and cross-checks the walked resource count against the catalog's
# total_resource_count so a failed/short channel is visible rather than silent.

def download_channel_db(channel_id):
    """Fetch a channel's published content DB to a temp file. Caller deletes it."""
    req = urllib.request.Request(DB_URL.format(channel_id),
                                 headers={"User-Agent": UA})
    fd, path = tempfile.mkstemp(prefix="k2go_", suffix=".sqlite3")
    with os.fdopen(fd, "wb") as out, urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        while True:
            chunk = r.read(1 << 16)
            if not chunk:
                break
            out.write(chunk)
    return path


def walk_channel_db(db_path):
    """Flat rows for one channel from its content DB: {id,parent,title,kind,is_leaf,
    size,count,subbytes}. size = own content bytes; count/subbytes = subtree totals."""
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    try:
        nodes = con.execute(
            "SELECT id, parent_id, title, kind, rght FROM content_contentnode").fetchall()
        own = {}
        for r in con.execute(
                "SELECT f.contentnode_id AS nid, COALESCE(SUM(lf.file_size), 0) AS b "
                "FROM content_file f JOIN content_localfile lf ON lf.id = f.local_file_id "
                "WHERE f.thumbnail = 0 AND f.supplementary = 0 "
                "GROUP BY f.contentnode_id"):
            own[r["nid"]] = int(r["b"] or 0)
    finally:
        con.close()

    rows = []
    for n in nodes:
        is_leaf = (n["kind"] != "topic")
        rows.append({
            "id": str(n["id"]), "parent": str(n["parent_id"] or ""),
            "title": str(n["title"] or ""), "kind": str(n["kind"] or ""),
            "is_leaf": is_leaf, "size": own.get(n["id"], 0),
            "rght": n["rght"] if n["rght"] is not None else 0,
            "count": 0, "subbytes": 0,
        })

    # Subtree totals bottom-up: a node's rght exceeds all its descendants', so
    # ascending-rght order processes children before parents (one MPTT tree/channel).
    sub_c = {r["id"]: (1 if r["is_leaf"] else 0) for r in rows}
    sub_b = {r["id"]: r["size"] for r in rows}
    for r in sorted(rows, key=lambda x: x["rght"]):
        p = r["parent"]
        if p in sub_c:
            sub_c[p] += sub_c[r["id"]]
            sub_b[p] += sub_b[r["id"]]
    for r in rows:
        r["count"] = sub_c[r["id"]]
        r["subbytes"] = sub_b[r["id"]]
    return rows


def project_rows(rows, variant):
    """Project the flat node rows to the fields a variant keeps (lean = small bundle).

    topics keeps only the folders, but folds each folder's DIRECT loose-resource
    children into an aggregate (dcount / dbytes) so a mixed level can show
    "N resources here - X bytes" without listing every leaf. The individual leaf
    titles come from the live source (Studio / box) when online — the bundle is
    the offline floor; the fallback fills the gaps.
    """
    if variant == "full":
        return [{"id": r["id"], "parent": r["parent"], "title": r["title"],
                 "kind": r["kind"], "leaf": 1 if r["is_leaf"] else 0, "size": r["size"]}
                for r in rows]
    # topics: aggregate the direct leaf children onto each parent folder
    direct = {}
    for r in rows:
        if r["is_leaf"]:
            d = direct.setdefault(r["parent"], [0, 0])
            d[0] += 1
            d[1] += r["size"]
    out = []
    for r in rows:
        if r["is_leaf"] and r["kind"] != "topic":
            continue
        dc, db = direct.get(r["id"], (0, 0))
        out.append({"id": r["id"], "parent": r["parent"], "title": r["title"],
                    "kind": r["kind"], "count": r["count"], "bytes": r["subbytes"],
                    "dcount": dc, "dbytes": db})
    return out


def serialize(rows):
    body = "\n".join(json.dumps(r, ensure_ascii=False, sort_keys=True) for r in rows)
    return (body + "\n").encode("utf-8")


def human(n):
    x = float(n)
    for u in ("B", "KB", "MB", "GB"):
        if x < 1024:
            return "{:.1f} {}".format(x, u)
        x /= 1024.0
    return "{:.1f} TB".format(x)


def build_all_trees(channels, limit):
    chans = channels[:limit] if limit else channels
    rows = []
    for i, c in enumerate(chans, 1):
        print("  channel {}/{}: {}".format(i, len(chans), c.get("name") or c["id"]))
        path = None
        try:
            path = download_channel_db(c["id"])
            rows.extend(walk_channel_db(path))
        except Exception as e:                               # noqa: BLE001
            print("  ! channel {} failed: {}".format(c["id"], e), file=sys.stderr)
        finally:
            if path and os.path.exists(path):
                os.remove(path)
    return rows


def report_variant(rows, variant):
    proj = project_rows(rows, variant)
    blob = serialize(proj)
    gz = gzip.compress(blob, 9)
    print("   {:7s}: {:>8,} nodes | raw {:>10} | gzip {:>10}".format(
        variant, len(proj), human(len(blob)), human(len(gz))))
    return proj


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--from-file", dest="from_file",
                    help="parse a saved channel-list JSON response instead of fetching")
    ap.add_argument("--out", default=None,
                    help="output path (defaults: catalog->assets/kolibri_catalog.jsonl, "
                         "tree->assets/kolibri_tree.jsonl)")
    ap.add_argument("--tree", choices=["topics", "full"],
                    help="emit a topic tree (that variant) instead of the channel list")
    ap.add_argument("--measure", action="store_true",
                    help="walk the trees and print raw+gzip sizes for BOTH variants; writes nothing")
    ap.add_argument("--limit", type=int, default=0,
                    help="only the first N channels — a quick sample before the full run")
    args = ap.parse_args()

    # The channel list is needed in every mode (it carries the roots + the
    # total_resource_count used for the cross-check).
    if args.from_file:
        print(">> [kolibri] reading channel list {}".format(args.from_file))
        raw = load_dump(args.from_file)
        source = "file:" + os.path.basename(args.from_file)
    else:
        print(">> [kolibri] fetching channel list {}{}".format(STUDIO, ENDPOINT))
        raw = fetch_all()
        source = STUDIO + ENDPOINT

    trimmed = [t for t in (trim(r) for r in raw) if t]
    channels = dedupe(trimmed)
    if not channels:
        print(">> [kolibri] no usable channels; keeping the existing asset", file=sys.stderr)
        return 1

    # ---- catalog mode (unchanged default behaviour) ----
    if not args.tree and not args.measure:
        out = args.out or os.path.normpath(DEFAULT_OUT)
        size = write(channels, out, source)
        print(">> [kolibri catalog] {} channels -> {} ({:.0f} KB)".format(
            len(channels), out, size / 1024.0))
        return 0

    # ---- tree modes (ADFA-5094) ----
    scope = channels[:args.limit] if args.limit else channels
    print(">> [kolibri tree] reading published content DBs for {} channel(s){}".format(
        len(scope), " (LIMIT)" if args.limit else ""))
    rows = build_all_trees(channels, args.limit)

    walked_res = sum(1 for r in rows if r["is_leaf"])
    catalog_res = sum(c.get("total_resource_count") or 0 for c in scope)
    flag = ""
    if catalog_res and abs(walked_res - catalog_res) > catalog_res * 0.05:
        flag = "   <-- MISMATCH: a channel DB failed to download/parse"
    print(">> walked {:,} nodes, {:,} resource leaves; catalog reports {:,} resources{}".format(
        len(rows), walked_res, catalog_res, flag))

    if args.measure:
        print(">> sizes (nothing written):")
        report_variant(rows, "topics")
        report_variant(rows, "full")
        return 0

    proj = report_variant(rows, args.tree)
    out = args.out or os.path.normpath(DEFAULT_TREE_OUT)
    tmp = out + ".tmp"
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(tmp, "wb") as f:
        f.write(serialize(proj))
    os.replace(tmp, out)
    print(">> [kolibri tree:{}] {} nodes -> {} ({})".format(
        args.tree, len(proj), out, human(os.path.getsize(out))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
