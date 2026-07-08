#!/usr/bin/env python3
# ============================================================================
# Name        : smoke_help.py
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : Smoke test for the tooltip help localizations. Complements
#               validate_help.py (which checks completeness) with integrity
#               checks: <b> markup balance vs. English, placeholder parity,
#               encoding, empty strings, an untranslated-string heuristic, and
#               a build + TooltipManager-style query round-trip incl. English
#               fallback. Pure Python stdlib. Exit 1 on any FAIL.
# Usage       : python3 smoke_help.py <i18n_dir> [--langs es,fr,...]
# ============================================================================
import argparse, csv, os, re, sqlite3, sys, tempfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pohelper, build_help_db

PH = re.compile(r'%(\d+\$)?[sd]')            # %s %d %1$s ...
BRAND_OK = {"PPK", "ADB", "QR", "DNS", "K2Go", "Kiwix", "Wi-Fi", "LED", "SIM"}

def counts(s):
    return (s.count("<b>"), s.count("</b>"))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("i18n_dir")
    ap.add_argument("--langs", default="")
    a = ap.parse_args()
    d = a.i18n_dir

    en = {}
    for r in csv.DictReader(open(os.path.join(d, "source_en.csv"), encoding="utf-8")):
        en["%s.%s" % (r["tag"], r["field"])] = r["text"]
    struct = build_help_db.load_structure(d)
    expected = set()
    for tag, (_c, has_detail, uris) in struct.items():
        expected.add("%s.summary" % tag)
        if has_detail: expected.add("%s.detail" % tag)
        for i,_ in enumerate(uris, 1): expected.add("%s.link%d" % (tag, i))

    langs = [l for l in a.langs.split(",") if l] or \
            sorted(f[:-3] for f in os.listdir(d) if f.endswith(".po"))
    fails, warns = [], []

    for lang in langs:
        p = os.path.join(d, "%s.po" % lang)
        if not os.path.exists(p):
            fails.append("%s: missing %s.po" % (lang, lang)); continue
        m = {e["msgctxt"]: e["msgstr"] for e in pohelper.parse_po(p)
             if e.get("msgstr") and not e.get("fuzzy")}
        for ctx in sorted(expected):
            src = en.get(ctx, "")
            tr = m.get(ctx)
            if not tr:
                fails.append("%s: %s empty/missing" % (lang, ctx)); continue
            if "�" in tr:
                fails.append("%s: %s has replacement char (bad encoding)" % (lang, ctx))
            if counts(src) != counts(tr):
                fails.append("%s: %s <b> markup mismatch en%s vs %s" % (lang, ctx, counts(src), counts(tr)))
            if sorted(PH.findall(src)) != sorted(PH.findall(tr)):
                fails.append("%s: %s placeholder mismatch" % (lang, ctx))
            if tr.strip() == src.strip() and not any(b in src for b in BRAND_OK) and len(src) > 12:
                warns.append("%s: %s identical to English (maybe untranslated)" % (lang, ctx))

    # round-trip: build DB and run the TooltipManager query per lang
    tmp = tempfile.mkdtemp()
    db = os.path.join(tmp, "help.db")
    build_help_db.build(d, db, ["en"] + langs)
    con = sqlite3.connect(db); cur = con.cursor()
    Q = ("SELECT T.summary FROM Tooltips T, TooltipCategories TC "
         "WHERE T.categoryId = TC.id AND T.tag = ? COLLATE NOCASE "
         "AND TC.category = ? COLLATE NOCASE AND T.lang = ?")
    sample_tag = next(iter(struct)); cat = struct[sample_tag][0]
    for lang in langs:
        row = cur.execute(Q, (sample_tag, cat, lang)).fetchone()
        if not row or not row[0]:
            fails.append("%s: DB round-trip returned no summary for %s" % (lang, sample_tag))
    # English fallback: a lang not built must fall back to en at runtime (lang 'en' always present)
    if not cur.execute(Q, (sample_tag, cat, "en")).fetchone():
        fails.append("en: source row missing (fallback broken)")
    con.close()

    print("[smoke] langs: %d | tags: %d | expected ctx/lang: %d" % (len(langs), len(struct), len(expected)))
    for w in warns: print("  WARN " + w, file=sys.stderr)
    if fails:
        print("[smoke] FAIL (%d):" % len(fails), file=sys.stderr)
        for f in fails: print("  - " + f, file=sys.stderr)
        sys.exit(1)
    print("[smoke] OK — %d languages passed integrity + round-trip (%d warnings)." % (len(langs), len(warns)))

if __name__ == "__main__":
    main()
