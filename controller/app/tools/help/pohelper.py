#!/usr/bin/env python3
# ============================================================================
# Name        : pohelper.py
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : Minimal, dependency-free gettext .po reader/writer for the
#               tooltip help pipeline. Pure Python stdlib (no polib), so the
#               build never needs an external package.
# ============================================================================

def _unquote(s):
    s = s.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        s = s[1:-1]
    return (s.replace('\\\\', '\x00')
             .replace('\\n', '\n')
             .replace('\\t', '\t')
             .replace('\\"', '"')
             .replace('\x00', '\\'))

def _quote(s):
    s = (s or "").replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
    return '"%s"' % s

def parse_po(path):
    """Return a list of entries {msgctxt, msgid, msgstr, fuzzy}. Handles
    multi-line continuation strings. The header entry is skipped."""
    entries = []
    cur = None
    field = None
    fuzzy = False

    def flush():
        nonlocal cur
        if cur and cur.get("msgctxt") and cur.get("msgid"):
            entries.append(cur)
        cur = None

    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.rstrip("\n")
            if line.strip() == "":
                flush(); field = None; fuzzy = False; continue
            if line.startswith("#"):
                if line.startswith("#,") and "fuzzy" in line:
                    fuzzy = True
                continue
            if line.startswith("msgctxt "):
                flush(); cur = {"fuzzy": fuzzy}
                cur["msgctxt"] = _unquote(line[8:]); field = "msgctxt"
            elif line.startswith("msgid "):
                if cur is None: cur = {"fuzzy": fuzzy}
                cur["msgid"] = _unquote(line[6:]); field = "msgid"
            elif line.startswith("msgstr "):
                if cur is None: cur = {"fuzzy": fuzzy}
                cur["msgstr"] = _unquote(line[7:]); field = "msgstr"
            elif line.startswith('"'):
                if cur is not None and field:
                    cur[field] = cur.get(field, "") + _unquote(line)
    flush()
    return entries

def write_po(path, entries, language=None, template=False, machine=False):
    """entries: list of (msgctxt, msgid, msgstr)."""
    out = []
    if machine:
        out.append("# NOTE: Machine translation (AI) \u2014 pending human review; not final.")
    out += ['msgid ""', 'msgstr ""', '"Content-Type: text/plain; charset=UTF-8\\n"']
    if language:
        out.append('"Language: %s\\n"' % language)
    if machine:
        out.append('"X-Translated-By: machine\\n"')
    out.append("")
    for ctx, msgid, msgstr in entries:
        out.append("msgctxt %s" % _quote(ctx))
        out.append("msgid %s" % _quote(msgid))
        out.append("msgstr %s" % _quote("" if template else (msgstr or "")))
        out.append("")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")
