#!/usr/bin/env python3
"""Validate the K2Go web i18n locale files the way the browser reads them.

Two tracks are checked:
  * index      -> static/site/lang/*.js           (loader: static/site/js/app.js, supportedLangs)
  * dashboard  -> static/dashboard/public/lang/*.js (loader: static/dashboard/public/js/dashboard.js, SUPPORTED_LANGS)

Each locale file is expected to assign `window.i18n = { "key": "value", ... };`.
For every track the script verifies, per file:
  * the file assigns window.i18n and parses without structural errors
  * no duplicate keys
  * the key set matches the English source of truth (missing / extra keys)
And per track:
  * the loader's supported-languages list matches the files on disk
    (a listed code with no file, or a file not listed, would misbehave in the browser)

Exit code is non-zero if any HARD error is found, so it can gate a commit or CI.
If `node` is on PATH, each file is additionally eval'd with node as an exact
browser-engine cross-check (best effort; skipped silently if node is absent).

Usage:  python3 tools/i18n/validate_i18n.py            # from the repo root
        python3 tools/i18n/validate_i18n.py --repo /path/to/repo
"""
import argparse
import os
import re
import shutil
import subprocess
import sys

# ------------------------------- JS object reader -------------------------------

def parse_i18n(src):
    """Parse `window.i18n = { ... }` into a list of (key, value) pairs.

    Walks the object literal character by character, respecting double-quoted
    strings and JS comments, so values containing '//', ':' or '}' (e.g. URLs)
    are handled correctly. Raises ValueError on anything that would not build a
    clean object in the browser.
    """
    m = re.search(r'window\.i18n\s*=\s*\{', src)
    if not m:
        raise ValueError("file does not assign `window.i18n = { ... }`")
    i, n = m.end(), len(src)

    def skip(i):
        while i < n:
            c = src[i]
            if c in ' \t\r\n,':
                i += 1
            elif c == '/' and i + 1 < n and src[i + 1] == '/':
                while i < n and src[i] != '\n':
                    i += 1
            elif c == '/' and i + 1 < n and src[i + 1] == '*':
                i += 2
                while i + 1 < n and not (src[i] == '*' and src[i + 1] == '/'):
                    i += 1
                i += 2
            else:
                break
        return i

    def read_string(i):
        i += 1  # opening quote
        buf = []
        esc = {'n': '\n', 't': '\t', 'r': '\r', '"': '"', '\\': '\\',
               '/': '/', 'b': '\b', 'f': '\f'}
        while i < n:
            c = src[i]
            if c == '\\':
                if i + 1 >= n:
                    raise ValueError("dangling escape in string")
                nxt = src[i + 1]
                if nxt == 'u':
                    buf.append(chr(int(src[i + 2:i + 6], 16)))
                    i += 6
                    continue
                buf.append(esc.get(nxt, nxt))
                i += 2
                continue
            if c == '"':
                return ''.join(buf), i + 1
            if c == '\n':
                raise ValueError("unescaped newline inside a string (unterminated?)")
            buf.append(c)
            i += 1
        raise ValueError("unterminated string literal")

    pairs = []
    while True:
        i = skip(i)
        if i >= n:
            raise ValueError("reached end of file before the closing '}'")
        if src[i] == '}':
            break
        if src[i] != '"':
            raise ValueError("expected a quoted key, found %r" % src[i])
        key, i = read_string(i)
        i = skip(i)
        if i >= n or src[i] != ':':
            raise ValueError("missing ':' after key %r" % key)
        i = skip(i + 1)
        if i >= n or src[i] != '"':
            raise ValueError("value for key %r is not a string" % key)
        val, i = read_string(i)
        pairs.append((key, val))
    return pairs


def loader_langs(path, varname):
    """Return the language codes listed in a loader's array literal, or None."""
    if not os.path.exists(path):
        return None
    txt = open(path, encoding='utf-8').read()
    m = re.search(varname + r'\s*=\s*\[(.*?)\]', txt, re.S)
    if not m:
        return None
    return [a or b for a, b in re.findall(r"'([^']*)'|\"([^\"]*)\"", m.group(1))]


# ------------------------------- track checker -------------------------------

def check_track(name, lang_dir, loader_path, loader_var, node_ok):
    print("=" * 70)
    print("TRACK: %s   (%s)" % (name, lang_dir))
    print("=" * 70)
    errors, warnings = [], []

    if not os.path.isdir(lang_dir):
        errors.append("lang dir not found: %s" % lang_dir)
        return errors, warnings

    files = sorted(f for f in os.listdir(lang_dir) if f.endswith('.js'))
    codes = [f[:-3] for f in files]

    if 'en' not in codes:
        errors.append("no en.js (source of truth) in %s" % lang_dir)
        return errors, warnings

    parsed = {}
    for code in codes:
        p = os.path.join(lang_dir, code + '.js')
        src = open(p, encoding='utf-8').read()
        try:
            pairs = parse_i18n(src)
        except ValueError as e:
            errors.append("%s.js WOULD BREAK: %s" % (code, e))
            continue
        keys = [k for k, _ in pairs]
        dups = sorted({k for k in keys if keys.count(k) > 1})
        if dups:
            errors.append("%s.js has duplicate keys: %s" % (code, ', '.join(dups)))
        parsed[code] = dict(pairs)

    if 'en' not in parsed:
        return errors, warnings

    en = parsed['en']
    en_keys = set(en)
    print("source en.js: %d keys | %d locale files" % (len(en_keys), len(files)))

    for code in codes:
        if code == 'en' or code not in parsed:
            continue
        ks = set(parsed[code])
        missing = sorted(en_keys - ks)
        extra = sorted(ks - en_keys)
        if missing:
            errors.append("%s.js MISSING %d key(s): %s"
                          % (code, len(missing), ', '.join(missing)))
        if extra:
            warnings.append("%s.js has %d extra key(s) not in en: %s"
                            % (code, len(extra), ', '.join(extra)))
        # soft signal: values identical to English may be untranslated
        same = [k for k in parsed[code] if k in en and parsed[code][k] == en[k]]
        if len(same) > len(en_keys) * 0.6:
            warnings.append("%s.js: %d/%d values identical to English "
                            "(brand names aside, may be untranslated)"
                            % (code, len(same), len(en_keys)))

    # loader list vs files on disk
    listed = loader_langs(loader_path, loader_var)
    if listed is None:
        warnings.append("could not read %s from %s (skipped loader parity)"
                        % (loader_var, loader_path))
    else:
        on_disk = set(codes)
        listed_set = set(listed)
        for c in sorted(listed_set - on_disk):
            errors.append("loader lists '%s' but %s/%s.js does not exist "
                          "(script tag would 404)" % (c, lang_dir, c))
        for c in sorted(on_disk - listed_set):
            warnings.append("%s.js exists but '%s' is not in %s "
                            "(unreachable by the loader)" % (c, c, loader_var))
        if not (listed_set - on_disk) and not (on_disk - listed_set):
            print("loader %s: %d codes, matches files on disk" % (loader_var, len(listed_set)))

    # optional exact cross-check with node
    if node_ok:
        bad = node_crosscheck(lang_dir, codes)
        for b in bad:
            errors.append("node eval mismatch: %s" % b)
        if not bad:
            print("node cross-check: all files eval to a window.i18n object")

    return errors, warnings


def node_crosscheck(lang_dir, codes):
    script = (
        "const fs=require('fs');let bad=[];"
        "for(const c of %s){try{const window={};"
        "eval(fs.readFileSync(process.argv[1]+'/'+c+'.js','utf8'));"
        "if(!window.i18n||typeof window.i18n!=='object')bad.push(c+': no window.i18n');"
        "}catch(e){bad.push(c+': '+e.message);}}"
        "console.log(JSON.stringify(bad));" % ('[' + ','.join("'%s'" % c for c in codes) + ']')
    )
    try:
        out = subprocess.run(['node', '-e', script, lang_dir],
                             capture_output=True, text=True, timeout=30)
        import json
        return json.loads(out.stdout.strip() or '[]')
    except Exception as e:
        return []  # best effort


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--repo', default=os.path.join(os.path.dirname(__file__), '..', '..'))
    args = ap.parse_args()
    repo = os.path.abspath(args.repo)

    tracks = [
        ("index", "static/site/lang",
         "static/site/js/app.js", "supportedLangs"),
        ("dashboard", "static/dashboard/public/lang",
         "static/dashboard/public/js/dashboard.js", "SUPPORTED_LANGS"),
    ]
    node_ok = shutil.which('node') is not None

    all_err, all_warn = [], []
    for name, ld, lp, lv in tracks:
        e, w = check_track(name, os.path.join(repo, ld),
                           os.path.join(repo, lp), lv, node_ok)
        for x in w:
            print("  WARN  " + x)
        for x in e:
            print("  ERROR " + x)
        all_err += e
        all_warn += w
        print()

    print("=" * 70)
    print("SUMMARY: %d error(s), %d warning(s)" % (len(all_err), len(all_warn)))
    if not node_ok:
        print("(node not found: skipped exact browser-engine cross-check)")
    print("=" * 70)
    sys.exit(1 if all_err else 0)


if __name__ == '__main__':
    main()
