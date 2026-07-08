#!/usr/bin/env python3
# ============================================================================
# Name        : extract_help_pot.py
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : Regenerate strings.pot from the English source (source_en.csv).
#               Run on demand when English content changes. Pure Python.
# Usage       : python3 extract_help_pot.py <i18n_dir>
# ============================================================================
import csv, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pohelper

def main(d):
    entries = []
    with open(os.path.join(d, "source_en.csv"), newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            entries.append(("%s.%s" % (r["tag"], r["field"]), r["text"], ""))
    pohelper.write_po(os.path.join(d, "strings.pot"), entries, template=True)
    print("Wrote strings.pot (%d entries)" % len(entries))

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: extract_help_pot.py <i18n_dir>"); sys.exit(2)
    main(sys.argv[1])
