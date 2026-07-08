#!/usr/bin/env python3
# ============================================================================
# Name        : validate_help.py
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : Fail the build if required tooltip translations are missing.
#               Homologous to Android's MissingTranslation, but under our
#               control: --allow-missing downgrades failure to a warning
#               (for local debugging). CI/release must run strict (default).
# Usage       : python3 validate_help.py <i18n_dir> --required es,fr,hi,pt,ru [--allow-missing true]
# Requires    : polib
# ============================================================================
import argparse, csv, os, sys
try:
    import polib
except ImportError:
    sys.exit("validate_help.py: polib is required (pip install polib)")

def expected_ctxs(d):
    exp = set()
    with open(os.path.join(d, "structure.csv"), newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            tag = r["tag"]
            exp.add(f"{tag}.summary")
            if r["detail"].strip().lower() == "yes":
                exp.add(f"{tag}.detail")
            for i, u in enumerate([x for x in (r["links"] or "").split(";") if x], start=1):
                exp.add(f"{tag}.link{i}")
    return exp

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("i18n_dir")
    ap.add_argument("--required", default="es,fr,hi,pt,ru")
    ap.add_argument("--allow-missing", default="false")
    a = ap.parse_args()
    allow = str(a.allow_missing).strip().lower() in ("1", "true", "yes")
    exp = expected_ctxs(a.i18n_dir)
    problems = []
    for lang in [l for l in a.required.split(",") if l]:
        path = os.path.join(a.i18n_dir, f"{lang}.po")
        if not os.path.exists(path):
            problems.append("%s: missing %s.po" % (lang, lang)); continue
        have = {e.msgctxt for e in polib.pofile(path) if e.msgstr and "fuzzy" not in e.flags}
        for ctx in sorted(exp - have):
            problems.append("%s: untranslated '%s'" % (lang, ctx))
    if problems:
        head = "WARNING (allowed)" if allow else "ERROR"
        print("[help l10n] %s: %d missing translation(s):" % (head, len(problems)), file=sys.stderr)
        for p in problems:
            print("  - " + p, file=sys.stderr)
        if not allow:
            print("[help l10n] Fix translations or pass -PhelpAllowMissing=true for a local debug build.", file=sys.stderr)
            sys.exit(1)
    else:
        print("[help l10n] OK: all required translations present.")

if __name__ == "__main__":
    main()
