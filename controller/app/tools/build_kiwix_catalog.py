#!/usr/bin/env python3
# ============================================================================
# ADFA-4849 — Build the offline Kiwix ZIM catalog -> src/main/assets/kiwix_catalog.csv
#
# Self-contained: fetches every category directory listing from download.kiwix.org
# (with a dotsrc mirror fallback) using a realistic User-Agent, parses each
# <creator>_<lang>_<flavour>_<YYYY-MM>.zim + size, and writes the FINAL CSV directly
# (no manual post-processing). Deduped to the newest date per (category,creator,lang,
# flavour). Language is detected by ISO code (2-letter preferred, then 3-letter),
# never positionally — the kiwix filename grammar is irregular.
#
# Usage:
#   python3 build_kiwix_catalog.py                 # fetch live -> CSV
#   python3 build_kiwix_catalog.py --from-file F   # parse a saved dump (===CATEGORY markers), .txt or .gz
#
# Optional: `pip install pycountry` for the full ISO language set; otherwise an
# embedded set is used (covers the current catalog).
#
# Gradle runs this only on release builds (assembleRelease/bundleRelease); run it
# manually any time with `./gradlew refreshKiwixCatalog`.
# ============================================================================
import argparse, csv, gzip, os, re, sys, urllib.request

CATS = ["devdocs","freecodecamp","gutenberg","ifixit","libretexts","maps","mooc",
        "other","phet","psiram","stack_exchange","ted","videos","vikidia","wikibooks",
        "wikinews","wikipedia","wikiquote","wikisource","wikiversity","wikivoyage",
        "wiktionary","zimit"]
PRIMARY = "https://download.kiwix.org/zim"
MIRROR  = "https://mirrors.dotsrc.org/kiwix/zim"
UA = "Mozilla/5.0 (K2Go Downloader)"

# Flavour/quality keywords that are NOT languages (some collide with obscure ISO-639-3 codes).
BLACKLIST = {"all","nopic","maxi","mini","novid","nodet","full"}
LEGACY = {"iw":"he","in":"id","ji":"yi"}

# Wiki-family projects use STRICT naming: <project>_<wikicode>_<flavour>_<date>. Their language
# is always the token right after the project, and it can be a Wikimedia code that is NOT ISO
# (nah, roa, eml, roa-tara, be-tarask, ...). For these we trust position 1 instead of ISO.
WIKI_FAMILY = {"wikipedia","wiktionary","wikibooks","wikiquote","wikisource",
               "wikiversity","wikivoyage","wikinews","vikidia"}

try:
    import pycountry
    ISO1 = {l.alpha_2.lower() for l in pycountry.languages if hasattr(l, "alpha_2")}
    ISO3 = {l.alpha_3.lower() for l in pycountry.languages if hasattr(l, "alpha_3")}
except Exception:
    # Embedded fallback (ISO-639-1 + common kiwix 3-letter codes) — enough for the current catalog.
    ISO1 = set("ab aa af ak am an ar as av ay az ba be bg bh bi bm bn bo br bs ca ce ch co cr cs cu "
               "cv cy da de dv dz ee el en eo es et eu fa ff fi fj fo fr fy ga gd gl gn gu gv ha he "
               "hi ho hr ht hu hy hz ia id ie ig ii ik io is it iu ja jv ka kg ki kj kk kl km kn ko "
               "kr ks ku kv kw ky la lb lg li ln lo lt lu lv mg mh mi mk ml mn mr ms mt my na nb nd "
               "ne ng nl nn no nr nv ny oc oj om or os pa pi pl ps pt qu rm rn ro ru rw sa sc sd se "
               "sg sh si sk sl sm sn so sq sr ss st su sv sw ta te tg th ti tk tl tn to tr ts tt tw "
               "ty ug uk ur uz ve vi vo wa wo xh yi yo za zh zu".split())
    ISO3 = set("ceb nds bcl hak nan wuu yue sco war min bpy pms lmo vec scn nap srn ang arc grc gsw "
               "ceb kbd sah nso tum lij vls fur frr hsb dsb szl zea kab bar mai bho new pnb ckb azb "
               "diq lad ext mhr mrj myv koi udm vep krc tyv xmf gom san glk mzn pcm hif ilo pag pam "
               "bug bjn ace ban nia tet mad tcy dty smn olo lug run".split())

def norm(tok):
    t = tok.lower()
    return LEGACY.get(t, t)

def base(tok):
    return norm(tok).split("-")[0]

def is1(tok):
    b = base(tok)
    return len(b) == 2 and b in ISO1 and norm(tok) not in BLACKLIST

def is3(tok):
    b = base(tok)
    return len(b) == 3 and b in ISO3 and norm(tok) not in BLACKLIST

def code_of(tok):
    """Return the normalized language code if tok is one (2-letter preferred), else None."""
    if is1(tok) or is3(tok): return norm(tok)
    return None

def parse_name(fn, category=""):
    """filename (no path) -> (creator, lang, flavour, date) ; lang '' if none.

    Wiki-family projects: the token after the project is the language (a Wikimedia code, maybe
    non-ISO) — trust position 1. Everyone else: names are usually creator_LANG_flavour_date, but
    some are creator_title_..._LANG, so take position 1 if it's an ISO code, otherwise scan from
    the END for a trailing code (avoids title words that happen to be ISO codes, e.g. French
    'de')."""
    stem = fn[:-4] if fn.lower().endswith(".zim") else fn
    toks = stem.split("_")
    date = ""
    if re.fullmatch(r"\d{4}-\d{2}", toks[-1]):
        date = toks[-1]; toks = toks[:-1]
    creator = toks[0] if toks else stem
    mids = toks[1:]
    lang, idx = "", -1

    if category in WIKI_FAMILY and mids and norm(mids[0]) not in BLACKLIST \
            and not re.fullmatch(r"\d{4}-\d{2}", mids[0]):
        lang, idx = norm(mids[0]), 0                 # trust the strict wiki grammar
    else:
        if mids:
            c0 = code_of(mids[0])
            if c0: lang, idx = c0, 0
        if not lang:
            for i in range(len(mids) - 1, -1, -1):
                c = code_of(mids[i])
                if c: lang, idx = c, i; break

    flav = [m for j, m in enumerate(mids) if j != idx] if idx >= 0 else mids
    flavour = "_".join(flav) if flav else "all"
    return creator, lang, flavour, date

SIZE = {"K":1024,"M":1024**2,"G":1024**3,"T":1024**4,"B":1,"":1}
LINE = re.compile(r'href="([^"]+\.zim)"[^>]*>[^<]*</a>\s+(\d{4})-(\d{2})-\d{2}\s+\d{2}:\d{2}\s+([0-9.]+)([KMGTB]?)', re.I)

def parse_listing(html, category, out):
    for m in LINE.finditer(html):
        fn = m.group(1).split("/")[-1]
        date = f"{m.group(2)}-{m.group(3)}"
        bytes_ = int(round(float(m.group(4)) * SIZE[(m.group(5) or "").upper()]))
        creator, lang, flavour, d2 = parse_name(fn, category)
        if d2: date = d2
        out.append([category, creator, lang, flavour, bytes_, date, fn])

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")

def from_dump(path):
    raw = gzip.open(path, "rt", encoding="utf-8", errors="replace").read() if path.endswith(".gz") \
          else open(path, encoding="utf-8", errors="replace").read()
    rows = []
    for block in re.split(r"^===CATEGORY\t", raw, flags=re.M)[1:]:
        header, _, body = block.partition("===\n")
        cat = header.split("\t")[0]
        parse_listing(body, cat, rows)
    return rows

def from_live():
    rows = []
    for c in CATS:
        html = ""
        for baseurl in (PRIMARY, MIRROR):
            try:
                html = fetch(f"{baseurl}/{c}/")
                if re.search(r'href="[^"]+\.zim"', html): break
            except Exception as e:
                sys.stderr.write(f"  {c}: {baseurl} failed ({e})\n")
        n = len(re.findall(r'href="[^"]+\.zim"', html))
        sys.stderr.write(f"{c:16} {n}\n")
        parse_listing(html, c, rows)
    return rows

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from-file")
    ap.add_argument("--out")
    args = ap.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    out_path = args.out or os.path.join(here, "..", "src", "main", "assets", "kiwix_catalog.csv")

    rows = from_dump(args.from_file) if args.from_file else from_live()
    if not rows:
        sys.stderr.write("no rows parsed; leaving existing CSV untouched\n")
        return 1

    best = {}
    for r in rows:
        k = (r[0], r[1], r[2], r[3])
        if k not in best or r[5] > best[k][5]:
            best[k] = r
    dedup = sorted(best.values())

    with open(out_path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["category","creator","lang","flavour","bytes","date","file"])
        w.writerows(dedup)
    sys.stderr.write(f"wrote {out_path}: {len(dedup)} items (from {len(rows)} files)\n")
    return 0

if __name__ == "__main__":
    sys.exit(main())
