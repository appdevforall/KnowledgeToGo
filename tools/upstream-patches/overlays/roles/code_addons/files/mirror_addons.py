#!/usr/bin/env python3
"""Mirror the Code on the Go add-ons gallery for offline serving.

Download the published static site (shell, catalog, and per-add-on files) from a
source base (default: the public R2 site) into a local directory, then rewrite the
catalog base to the local serve path. The download set is discovered by following
references from two stable anchors, so it never carries a hardcoded file list:

  - index.html names its own shell assets in its link, script, and img tags.
  - v1/catalog.json lists every per-add-on file; the .cgp and the source tarball
    each carry a sha256.

Add-on binaries are verified against the sha256 the catalog carries. The only
transform is the catalog base rewrite; the shell and the per-add-on pages use
relative asset paths, so they serve unchanged under the serve path.

Incremental refresh (K2GO-441). catalog.json is the single source of truth, so
with --reuse-from <served tree> the mirror never re-transfers an unchanged file
and never re-hashes a local one. What changed is read straight from the catalog
the garden publishes (see the appdevforall/addons garden, tools/addons):

  - .cgp and source tarball carry a catalog sha256: reuse the served copy when the
    published sha equals the served catalog's sha; otherwise download and verify.
  - The source tarball is `git ls-files` of the add-on, so it CONTAINS the icon
    (src/main/assets/icon_*.png) and the page source. So an add-on's icon is reused
    when its tarball sha is unchanged, and its page when the tarball sha AND
    index.html are both unchanged (the page also carries the shell's hashed-asset
    chrome).
  - Shell assets have content-hashed names, so a name that already exists in the
    served tree is identical: reuse it; a new hash means a new file to download.
  - The catalog carries `generated` (a build timestamp), so a byte-identical
    catalog means the exact same build: a short-circuit then reports up-to-date
    and downloads nothing.

This is the shared mechanism for the add-ons offline gallery (K2GO-99). The
Ansible role calls it at bake time (no --reuse-from, a full mirror); dash-node
re-runs it in-server with --reuse-from for the online update. See
controller/docs/ADR-addons-offline-gallery.md.
"""

import argparse
import hashlib
import json
import shutil
import sys
import re
import urllib.request
import urllib.error
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin, urlsplit

USER_AGENT = "k2go-addons-mirror/1"
TIMEOUT = 60
RETRIES = 3

# Fixed anchors, relative to the source base. Everything else is discovered.
INDEX = "index.html"
CATALOG = "v1/catalog.json"
CATALOG_SCHEMA = "v1/catalog.schema.json"

# Tags whose href/src names a static asset of the site (not a nav link).
ASSET_TAGS = {"link": "href", "script": "src", "img": "src"}


class AssetRefs(HTMLParser):
    """Collect href/src values from asset tags (link, script, img)."""

    def __init__(self):
        super().__init__()
        self.refs = []

    def handle_starttag(self, tag, attrs):
        attr = ASSET_TAGS.get(tag)
        if not attr:
            return
        for name, value in attrs:
            if name == attr and value:
                self.refs.append(value)


def is_local_ref(ref):
    """True for a same-site relative asset path.

    A real site asset is relative with no scheme and no leading slash
    (for example "assets/app.<hash>.js" or "../assets/styles.<hash>.css").
    Absolute URLs (fonts, analytics) and root-absolute paths (the Cloudflare
    "/cdn-cgi/..." injections) are not part of the authored site and are left out.
    """
    if not ref:
        return False
    if urlsplit(ref).scheme:
        return False
    if ref.startswith("/"):
        return False
    return True


# Cloudflare rewrites the served HTML in ways that are not part of the authored
# site and must not ship on the box:
#   - the analytics beacon script (the box does not track anyone),
#   - the email-decode helper script (a dead "/cdn-cgi/..." reference offline),
#   - an inline JS-challenge / bot-detection script that loads
#     "/cdn-cgi/challenge-platform/...",
#   - the footer mailto, replaced by an obfuscated "/cdn-cgi/l/email-protection"
#     link, and any visible email replaced by a data-cfemail hex blob.
# Remove every Cloudflare script block (inline or external) and restore the real
# emails (Cloudflare encodes an address with a one-byte XOR, so it decodes with
# no network call). The Google Fonts link is left in: it fails gracefully offline
# and works when the box is online.
SCRIPT_BLOCK = re.compile(r'<script\b.*?</script>', re.I | re.S)
CF_EMAIL_EL = re.compile(
    r'<(a|span)\b[^>]*\bdata-cfemail="([0-9a-fA-F]+)"[^>]*>.*?</\1>', re.I | re.S)
CF_EMAIL_HREF = re.compile(r'/cdn-cgi/l/email-protection(?:#([0-9a-fA-F]+))?')


def _cf_decode(hex_blob):
    """Decode a Cloudflare-obfuscated email, or None if it does not decode."""
    if not hex_blob:
        return None
    try:
        key = int(hex_blob[:2], 16)
        text = "".join(chr(int(hex_blob[i:i + 2], 16) ^ key)
                       for i in range(2, len(hex_blob), 2))
    except ValueError:
        return None
    return text if "@" in text else None


def clean_html(data):
    text = data.decode("utf-8", "replace")

    # Drop every Cloudflare script block; keep the site's own scripts.
    def drop_script(m):
        block = m.group(0)
        return "" if ("cdn-cgi" in block or "cloudflareinsights" in block) else block

    text = SCRIPT_BLOCK.sub(drop_script, text)

    # Restore an obfuscated element (visible email), then any leftover href.
    def element(m):
        email = _cf_decode(m.group(2))
        if not email:
            return m.group(0)
        if m.group(1).lower() == "a":
            return f'<a href="mailto:{email}">{email}</a>'
        return email

    def href(m):
        email = _cf_decode(m.group(1))
        return "mailto:" + email if email else m.group(0)

    text = CF_EMAIL_EL.sub(element, text)
    text = CF_EMAIL_HREF.sub(href, text)
    return text.encode("utf-8")


def fetch(url):
    last = None
    for attempt in range(RETRIES):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                return r.read()
        except (urllib.error.URLError, urllib.error.HTTPError, OSError) as e:
            last = e
    raise RuntimeError(f"fetch failed after {RETRIES} tries: {url}: {last}")


def head(url):
    """Return (status, content_length) without downloading the body."""
    req = urllib.request.Request(url, method="HEAD",
                                 headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            length = r.headers.get("Content-Length")
            return r.status, int(length) if length is not None else None
    except urllib.error.HTTPError as e:
        return e.code, None


def asset_refs(html_bytes, page_url):
    """Absolute URLs of the local assets referenced by one HTML page."""
    parser = AssetRefs()
    parser.feed(html_bytes.decode("utf-8", "replace"))
    out = []
    for ref in parser.refs:
        if is_local_ref(ref):
            out.append(urljoin(page_url, ref))
    return out


def rel_to_base(url, source_base):
    """Path of url under source_base, or None if it is not under it."""
    if not url.startswith(source_base + "/"):
        return None
    return url[len(source_base) + 1:]


def sha256_of(data):
    h = hashlib.sha256()
    h.update(data)
    return h.hexdigest()


def served_catalog_shas(reuse_from):
    """{slug: {"cgp": sha, "tar": sha}} from the served catalog, or an empty map.

    The served catalog is the prior mirror's v1/catalog.json. The base rewrite does
    not touch the sha256 values, so the served shas compare directly to the
    published ones: catalog against catalog, never a local file re-hash (K2GO-441).
    """
    if reuse_from is None:
        return {}
    try:
        cat = json.loads((reuse_from / CATALOG).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    out = {}
    for addon in cat.get("addons", []):
        slug = addon.get("slug")
        if slug:
            out[slug] = {
                "cgp": addon.get("download", {}).get("sha256"),
                "tar": addon.get("sourceTarball", {}).get("sha256"),
            }
    return out


def build_plan(source_base, catalog, served, index_unchanged, index_bytes):
    """The paths to mirror, their expected sha/size, and which may be reused.

    Driven only by the catalog (every per-add-on file) and index.html (its own
    shell assets). reuse_ok[rel] is True when the catalog proves the served copy is
    current, so the loop copies it instead of downloading.
    """
    index_url = f"{source_base}/{INDEX}"
    paths = {INDEX, CATALOG, CATALOG_SCHEMA}
    sha = {}            # site-relative path -> expected sha256 (hashed binaries)
    size = {}           # site-relative path -> expected size (hashed binaries)
    reuse_ok = {INDEX: index_unchanged, CATALOG: False, CATALOG_SCHEMA: False}

    for addon in catalog["addons"]:
        s = served.get(addon.get("slug"))
        cgp = addon["download"]
        tar = addon["sourceTarball"]
        # Require a real sha on both sides: two absent (None) shas must not compare
        # equal and wrongly reuse icon/page on no evidence (the catalog always emits
        # them, so this only guards a malformed entry).
        cgp_unchanged = (s is not None and cgp.get("sha256") is not None
                         and s.get("cgp") == cgp.get("sha256"))
        tar_unchanged = (s is not None and tar.get("sha256") is not None
                         and s.get("tar") == tar.get("sha256"))

        cgp_rel = rel_to_base(cgp["url"], source_base)
        tar_rel = rel_to_base(tar["url"], source_base)
        icon_rel = rel_to_base(addon["iconUrl"], source_base)
        dark_rel = rel_to_base(addon["iconDarkUrl"], source_base)
        page_rel = rel_to_base(addon["pageUrl"], source_base)

        if cgp_rel and cgp.get("sha256") and cgp.get("size") is not None:
            paths.add(cgp_rel); sha[cgp_rel] = cgp["sha256"]
            size[cgp_rel] = cgp["size"]; reuse_ok[cgp_rel] = cgp_unchanged
        if tar_rel and tar.get("sha256") and tar.get("size") is not None:
            paths.add(tar_rel); sha[tar_rel] = tar["sha256"]
            size[tar_rel] = tar["size"]; reuse_ok[tar_rel] = tar_unchanged
        # Icon + page live inside the source tarball (git ls-files of the add-on),
        # so the tarball sha covers them; the page also carries the shell chrome.
        for r in (icon_rel, dark_rel):
            if r:
                paths.add(r); reuse_ok[r] = tar_unchanged
        if page_rel:
            paths.add(page_rel); reuse_ok[page_rel] = tar_unchanged and index_unchanged

    # Shell assets, from index.html's own tags. Their names carry a content hash,
    # so one that already exists in the served tree is identical: reuse it. Only index.html
    # is crawled: the garden publishes just the shared hashed chrome plus each add-on's png
    # and html, so the per-add-on pages carry no local asset that index.html does not already
    # name; crawling them would mean fetching every page, which defeats the page reuse.
    for ref in asset_refs(index_bytes, index_url):
        rel = rel_to_base(ref, source_base)
        if rel:
            paths.add(rel); reuse_ok.setdefault(rel, True)

    return paths, sha, size, reuse_ok


def mirror(source_base, serve_base, out, reuse_from=None, limit_large=0,
           verbose=True):
    source_base = source_base.rstrip("/")
    serve_base = serve_base.rstrip("/")
    out = Path(out)
    reuse_from = Path(reuse_from) if reuse_from else None

    catalog_bytes = fetch(f"{source_base}/{CATALOG}")
    rewritten_catalog = catalog_bytes.decode("utf-8").replace(source_base, serve_base)

    # K2GO-441: same-build short-circuit. The catalog carries `generated` (a build
    # timestamp), so a byte-identical catalog means the exact same published build:
    # nothing changed, so there is nothing to download and nothing to swap. The
    # wrapper reads "result: up-to-date" and keeps the live gallery.
    if reuse_from is not None:
        try:
            if (reuse_from / CATALOG).read_text(encoding="utf-8") == rewritten_catalog:
                print("result: up-to-date")
                print("done: 0 downloaded, 0 reused, 0 failed")
                return True
        except OSError:
            pass   # no served catalog yet: fall through to a full mirror.

    out.mkdir(parents=True, exist_ok=True)
    catalog = json.loads(catalog_bytes)
    served = served_catalog_shas(reuse_from)

    # index.html drives the shell plan and the page-chrome reuse. The served copy
    # is already Cloudflare-cleaned, so compare it to the cleaned published bytes.
    index_bytes = fetch(f"{source_base}/{INDEX}")
    cleaned_index = clean_html(index_bytes)
    index_unchanged = False
    if reuse_from is not None:
        try:
            index_unchanged = (reuse_from / INDEX).read_bytes() == cleaned_index
        except OSError:
            index_unchanged = False

    paths, sha, size, reuse_ok = build_plan(
        source_base, catalog, served, index_unchanged, index_bytes)
    if verbose:
        print(f"plan: {len(paths)} files, {len(sha)} verified binaries, "
              f"{len(catalog['addons'])} add-ons")

    downloaded = reused = headed = failed = 0

    for rel in sorted(paths):
        url = f"{source_base}/{rel}"
        dest = out / rel
        dest.parent.mkdir(parents=True, exist_ok=True)

        # Anchors fetched in hand: the catalog (rewritten at the end) and index.html.
        if rel == CATALOG:
            dest.write_bytes(catalog_bytes)
            downloaded += 1
            continue
        if rel == INDEX:
            if reuse_ok.get(INDEX) and reuse_from is not None and (reuse_from / INDEX).is_file():
                shutil.copyfile(reuse_from / INDEX, dest)
                reused += 1
            else:
                dest.write_bytes(cleaned_index)
                downloaded += 1
            continue

        # Reuse what the catalog proves is current, straight from the served tree.
        if reuse_ok.get(rel) and reuse_from is not None and (reuse_from / rel).is_file():
            shutil.copyfile(reuse_from / rel, dest)
            reused += 1
            continue

        # Test only: HEAD a big binary instead of pulling its body.
        if limit_large and size.get(rel) and size[rel] > limit_large:
            st, length = head(url)
            ok = st == 200 and (length == size[rel] or length is None)
            headed += 1
            if verbose:
                mark = "ok" if ok else f"MISMATCH(status={st},len={length})"
                print(f"  HEAD {rel} ({size[rel]} B): {mark}")
            if not ok:
                failed += 1
            continue

        try:
            data = fetch(url)
        except RuntimeError as e:
            failed += 1
            print(f"  FAIL {rel}: {e}", file=sys.stderr)
            continue
        if rel in sha:
            got = sha256_of(data)
            if got != sha[rel]:
                failed += 1
                print(f"  SHA MISMATCH {rel}: got {got[:12]} "
                      f"want {sha[rel][:12]}", file=sys.stderr)
                continue
            if len(data) != size[rel]:
                failed += 1
                print(f"  SIZE MISMATCH {rel}: got {len(data)} "
                      f"want {size[rel]}", file=sys.stderr)
                continue
        if rel.endswith(".html"):
            data = clean_html(data)
        dest.write_bytes(data)
        downloaded += 1
        if verbose and (downloaded % 10 == 0 or rel in sha):
            tag = " (verified)" if rel in sha else ""
            print(f"  GET  {rel} ({len(data)} B){tag}")

    # The one transform: point the catalog at the local serve base.
    catalog_file = out / CATALOG
    if catalog_file.exists():
        n = catalog_file.read_text(encoding="utf-8").count(source_base)
        catalog_file.write_text(rewritten_catalog, encoding="utf-8")
        if verbose:
            print(f"rewrote catalog base: {source_base} -> {serve_base} "
                  f"({n} occurrences)")

    tail = f", {headed} head-checked" if headed else ""
    print(f"done: {downloaded} downloaded, {reused} reused{tail}, {failed} failed")
    return failed == 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="Mirror the add-ons gallery offline.")
    ap.add_argument("--source-base", default="https://addons.appdevforall.org",
                    help="site to mirror from")
    ap.add_argument("--serve-base", default="/code-addons",
                    help="local serve path the catalog is rewritten to")
    ap.add_argument("--out", required=True, help="output mirror directory")
    ap.add_argument("--reuse-from", default=None,
                    help="the currently served tree; reuse unchanged files from it "
                         "instead of re-downloading (K2GO-441)")
    ap.add_argument("--limit-large", type=int, default=0,
                    help="test only: HEAD files larger than this many bytes "
                         "instead of downloading them (0 = download all)")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)
    ok = mirror(args.source_base, args.serve_base, args.out,
                reuse_from=args.reuse_from, limit_large=args.limit_large,
                verbose=not args.quiet)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
