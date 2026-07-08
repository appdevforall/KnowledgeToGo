#!/usr/bin/env python3
# ============================================================================
# Name        : build_help_db.py
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : Build the prebuilt read-only help database (help.db) for the
#               K2Go three-tier help system from CSV sources. Schema mirrors
#               Code On the Go (Tooltips / TooltipCategories / TooltipButtons)
#               so content tooling stays compatible.
#
# Usage       : python3 build_help_db.py tooltips.csv links.csv out/help.db
# ============================================================================
import csv
import os
import sqlite3
import sys


def build(tooltips_csv, links_csv, out_db):
    if os.path.exists(out_db):
        os.remove(out_db)
    os.makedirs(os.path.dirname(out_db) or ".", exist_ok=True)

    con = sqlite3.connect(out_db)
    cur = con.cursor()
    cur.executescript(
        """
        CREATE TABLE TooltipCategories (
            id       INTEGER PRIMARY KEY,
            category TEXT NOT NULL UNIQUE
        );
        CREATE TABLE Tooltips (
            id         INTEGER PRIMARY KEY,
            categoryId INTEGER NOT NULL REFERENCES TooltipCategories(id),
            tag        TEXT NOT NULL,
            summary    TEXT NOT NULL,
            detail     TEXT,
            UNIQUE(categoryId, tag)
        );
        CREATE TABLE TooltipButtons (
            id             INTEGER PRIMARY KEY,
            tooltipId      INTEGER NOT NULL REFERENCES Tooltips(id),
            buttonNumberId INTEGER NOT NULL,
            description    TEXT NOT NULL,
            uri            TEXT NOT NULL
        );
        """
    )

    cat_ids = {}

    def cat_id(name):
        if name not in cat_ids:
            cur.execute("INSERT INTO TooltipCategories(category) VALUES (?)", (name,))
            cat_ids[name] = cur.lastrowid
        return cat_ids[name]

    tip_ids = {}  # (category, tag) -> tooltip id
    with open(tooltips_csv, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            cid = cat_id(row["category"].strip())
            cur.execute(
                "INSERT INTO Tooltips(categoryId, tag, summary, detail) VALUES (?,?,?,?)",
                (cid, row["tag"].strip(), row["summary"], row.get("detail") or ""),
            )
            tip_ids[(row["category"].strip(), row["tag"].strip())] = cur.lastrowid

    if links_csv and os.path.exists(links_csv):
        counters = {}
        with open(links_csv, newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                key = (row["category"].strip(), row["tag"].strip())
                tid = tip_ids.get(key)
                if tid is None:
                    print("WARN: link for unknown tooltip %s" % (key,), file=sys.stderr)
                    continue
                n = counters.get(tid, 0) + 1
                counters[tid] = n
                cur.execute(
                    "INSERT INTO TooltipButtons(tooltipId, buttonNumberId, description, uri) "
                    "VALUES (?,?,?,?)",
                    (tid, n, row["label"], row["uri"]),
                )

    con.commit()
    con.close()
    print("Wrote %s (%d tooltips)" % (out_db, len(tip_ids)))


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("usage: build_help_db.py <tooltips.csv> <links.csv> <out/help.db>")
        sys.exit(2)
    build(sys.argv[1], sys.argv[2], sys.argv[3])
