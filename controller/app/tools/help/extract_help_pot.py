#!/usr/bin/env python3
# ============================================================================
# Name        : extract_help_pot.py
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : Regenerate strings.pot from the English source (source_en.csv +
#               structure.csv). Run on demand when English content changes;
#               NOT part of the normal build (it rewrites the template).
# Usage       : python3 extract_help_pot.py <i18n_dir>
# Requires    : polib
# ============================================================================
import csv, os, sys
try:
    import polib
except ImportError:
    sys.exit("extract_help_pot.py: polib is required (pip install polib)")

def main(d):
    en = {}
    with open(os.path.join(d, "source_en.csv"), newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            en[(r["tag"], r["field"])] = r["text"]
    pot = polib.POFile(); pot.metadata = {"Content-Type": "text/plain; charset=UTF-8"}
    for (tag, field), text in en.items():
        pot.append(polib.POEntry(msgctxt=f"{tag}.{field}", msgid=text, msgstr=""))
    pot.save(os.path.join(d, "strings.pot"))
    print("Wrote strings.pot (%d entries)" % len(en))

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: extract_help_pot.py <i18n_dir>"); sys.exit(2)
    main(sys.argv[1])
