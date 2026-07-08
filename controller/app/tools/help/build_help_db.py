#!/usr/bin/env python3
# ============================================================================
# Name        : build_help_db.py
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : Compile the read-only tooltip help database (help.db) from
#               localized .po sources. English is the source (msgid); each
#               <lang>.po supplies msgstr. Missing translations fall back to
#               English. One DB, `lang` column. Pure Python (no polib).
# Usage       : python3 build_help_db.py <i18n_dir> <out/help.db> [langs_csv]
# ============================================================================
import csv, os, sqlite3, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pohelper

DEFAULT_LANGS = ["en", "es", "fr", "hi", "pt", "ru", "de", "ja", "ar"]

def load_structure(d):
    s = {}
    with open(os.path.join(d, "structure.csv"), newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            uris = [u for u in (r["links"] or "").split(";") if u]
            s[r["tag"]] = (r["category"], r["detail"].strip().lower() == "yes", uris)
    return s

def load_source_en(d):
    en = {}
    with open(os.path.join(d, "source_en.csv"), newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            en[(r["tag"], r["field"])] = r["text"]
    return en

def load_po(path):
    m = {}
    if os.path.exists(path):
        for e in pohelper.parse_po(path):
            if e.get("msgstr") and not e.get("fuzzy"):
                m[e["msgctxt"]] = e["msgstr"]
    return m

def build(i18n_dir, out, langs):
    struct = load_structure(i18n_dir)
    en = load_source_en(i18n_dir)
    pos = {l: load_po(os.path.join(i18n_dir, "%s.po" % l)) for l in langs if l != "en"}

    def text(lang, tag, field):
        if lang == "en":
            return en.get((tag, field), "")
        return pos.get(lang, {}).get("%s.%s" % (tag, field)) or en.get((tag, field), "")

    if os.path.exists(out):
        os.remove(out)
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    con = sqlite3.connect(out); cur = con.cursor()
    cur.executescript("""
        CREATE TABLE TooltipCategories (id INTEGER PRIMARY KEY, category TEXT NOT NULL UNIQUE);
        CREATE TABLE Tooltips (
            id INTEGER PRIMARY KEY, categoryId INTEGER NOT NULL, tag TEXT NOT NULL,
            lang TEXT NOT NULL, summary TEXT NOT NULL, detail TEXT,
            UNIQUE(categoryId, tag, lang));
        CREATE TABLE TooltipButtons (
            id INTEGER PRIMARY KEY, tooltipId INTEGER NOT NULL, buttonNumberId INTEGER NOT NULL,
            description TEXT, uri TEXT NOT NULL);
    """)
    cat_ids = {}
    def cat_id(c):
        if c not in cat_ids:
            cur.execute("INSERT INTO TooltipCategories(category) VALUES(?)", (c,))
            cat_ids[c] = cur.lastrowid
        return cat_ids[c]

    for tag, (category, has_detail, uris) in struct.items():
        cid = cat_id(category)
        for lang in langs:
            summary = text(lang, tag, "summary")
            detail = text(lang, tag, "detail") if has_detail else ""
            cur.execute("INSERT INTO Tooltips(categoryId,tag,lang,summary,detail) VALUES(?,?,?,?,?)",
                        (cid, tag, lang, summary, detail))
            tid = cur.lastrowid
            for i, uri in enumerate(uris, start=1):
                cur.execute("INSERT INTO TooltipButtons(tooltipId,buttonNumberId,description,uri) VALUES(?,?,?,?)",
                            (tid, i, text(lang, tag, "link%d" % i), uri))
    con.commit(); con.close()
    print("Wrote %s (%d tags x %d langs)" % (out, len(struct), len(langs)))

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("usage: build_help_db.py <i18n_dir> <out/help.db> [langs_csv]"); sys.exit(2)
    langs = sys.argv[3].split(",") if len(sys.argv) > 3 else DEFAULT_LANGS
    build(sys.argv[1], sys.argv[2], langs)
