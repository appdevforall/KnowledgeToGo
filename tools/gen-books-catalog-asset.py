#!/usr/bin/env python3
# ============================================================================
# gen-books-catalog-asset.py  (ADFA-4853)
# Author: AppDevForAll
#
# Generate the app's OFFLINE Books catalog asset from the dashboard catalog.db,
# so the wizard can search/select books before the system (and Calibre-Web) exist.
#
# Reads the dashboard catalog (SQLite FTS 'catalog' table) and writes a plain
# (uncompressed) JSON Lines file — one compact record per line:
#     {"id": <gutenberg_id>, "title": "...", "author": "...", "lang": "en", "url": "..."}
# JSONL (not CSV) so commas/quotes/accents in titles/authors never corrupt a row.
# Kept UNCOMPRESSED with a single extension (.jsonl), like assets/kiwix_catalog.csv:
# a ".jsonl.gz" double-extension binary got EOL-normalized by .gitattributes and was
# not packaged into the APK (FileNotFoundException at runtime). Plain text avoids that.
#
# Usage:
#   gen-books-catalog-asset.py [CATALOG_DB] [OUT_JSONL]
# Defaults:
#   CATALOG_DB = /library/dashboard/books/catalog.db   (on the installed system)
#   OUT_JSONL  = <repo>/controller/app/src/main/assets/books_catalog.jsonl
# ============================================================================
import sqlite3
import json
import sys
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = sys.argv[1] if len(sys.argv) > 1 else "/library/dashboard/books/catalog.db"
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    HERE, "..", "controller", "app", "src", "main", "assets", "books_catalog.jsonl")

if not os.path.exists(SRC):
    sys.exit(f"catalog not found: {SRC} (run on a system that has the dashboard catalog, "
             f"or pass the path explicitly)")

db = sqlite3.connect(f"file:{SRC}?mode=ro", uri=True)
try:
    # Ordered by popularity so the app's "Popular" view = the first N rows, no re-sort needed.
    rows = db.execute(
        "SELECT gutenberg_id, title, author, language, download_url "
        "FROM catalog ORDER BY downloads DESC"
    ).fetchall()
finally:
    db.close()

os.makedirs(os.path.dirname(OUT), exist_ok=True)
n = 0
with open(OUT, "w", encoding="utf-8") as f:
    for gid, title, author, lang, url in rows:
        if gid is None or not url:
            continue
        f.write(json.dumps({
            "id": gid,
            "title": title or "",
            "author": author or "",
            "lang": (lang or "").strip(),
            "url": url,
        }, ensure_ascii=False) + "\n")
        n += 1

print(f"wrote {n} books -> {OUT}")
