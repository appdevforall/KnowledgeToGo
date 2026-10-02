#!/usr/bin/env python3
"""Mirror the Code on the Go build assets for offline serving (K2GO-437).

Download the Code on the Go build environment (Gradle, the Gradle API, the
offline Maven repository, the Android SDK, the terminal bootstrap, the
documentation database, and the project templates) from a source base into a
local directory, and generate a static browse page. NGINX serves the result at
http://box/code-assets so a device on the local network can read the files with
no internet.

Unlike the add-ons gallery (K2GO-99), the build-assets site has no catalog: it
is a flat set of files, each with a sibling "<name>.md5". So the download set
comes from a committed manifest (code_assets_manifest.json: the path, title, and
description of every asset), and integrity plus change detection use that ".md5".

Incremental refresh. With --reuse-from <served tree> the mirror never
re-downloads an unchanged file and never re-hashes a local one: it compares the
published ".md5" against the ".md5" already served. A file whose published md5
equals the served md5 is copied from the served tree; any other file is
downloaded and verified against its published md5. When every file reuses and
the generated page is unchanged, a short-circuit reports up-to-date and builds
no staging, so the wrapper keeps the live tree.

Source move. The source base is the --source-base argument (the role passes
code_assets_source_base). The assets live on appdevforall.org/dev-assets today
and move to Cloudflare R2 soon; only that default changes then, not this script.

See controller/docs/ADR-code-assets-offline-build-env.md.
"""

import argparse
import hashlib
import html
import json
import shutil
import sys
import urllib.request
import urllib.error
import urllib.parse
from pathlib import Path

USER_AGENT = "k2go-code-assets-mirror/1"
TIMEOUT = 120
RETRIES = 3

INDEX = "index.html"
MANIFEST_DEFAULT = Path(__file__).resolve().parent / "code_assets_manifest.json"

# Category display order on the browse page. A category the manifest adds later
# that is not listed here still renders, after these, in first-seen order.
CATEGORY_ORDER = [
    "Gradle", "Android SDK", "Terminal bootstrap",
    "Maven repository", "Documentation", "Templates",
]

# Teal brand glyph (a package of build parts), used as the page favicon and the
# header logo, so the LAN-served site is not iconless (K2GO-437). currentColor is
# set by CSS for the header; the favicon carries an explicit teal.
LOGO_SVG = (
    '<svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" '
    'aria-hidden="true">'
    '<path d="M12 2 3 7v10l9 5 9-5V7l-9-5Z" stroke="{color}" stroke-width="1.6" '
    'stroke-linejoin="round"/>'
    '<path d="M3 7l9 5 9-5M12 12v10" stroke="{color}" stroke-width="1.6" '
    'stroke-linejoin="round"/>'
    '<path d="M9.5 9.2 8 10.7l1.5 1.5M14.5 9.2 16 10.7l-1.5 1.5" stroke="{color}" '
    'stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>'
    '</svg>'
)


def fetch(url):
    last = None
    for _ in range(RETRIES):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                return r.read()
        except (urllib.error.URLError, urllib.error.HTTPError, OSError) as e:
            last = e
    raise RuntimeError(f"fetch failed after {RETRIES} tries: {url}: {last}")


def md5_of(data):
    h = hashlib.md5()
    h.update(data)
    return h.hexdigest()


def parse_md5(text):
    """The hash from an md5 file. The file is '<hash>' or '<hash>  <name>'."""
    for token in text.split():
        t = token.strip().lower()
        if len(t) == 32 and all(c in "0123456789abcdef" for c in t):
            return t
    return None


def published_md5(source_base, path):
    """The md5 the source publishes for <path>, or None if it is unreadable."""
    try:
        return parse_md5(fetch(f"{source_base}/{path}.md5").decode("utf-8", "replace"))
    except RuntimeError:
        return None


def served_md5(reuse_from, path):
    """The md5 of the served copy of <path>, read from its mirrored .md5 sidecar."""
    if reuse_from is None:
        return None
    try:
        return parse_md5((reuse_from / f"{path}.md5").read_text(encoding="utf-8"))
    except OSError:
        return None


def load_manifest(manifest_path):
    data = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    assets = data.get("assets", [])
    for a in assets:
        if not a.get("path"):
            raise ValueError("manifest entry without a 'path'")
    return assets


def human_size(n):
    if n is None:
        return ""
    units = ["B", "KiB", "MiB", "GiB"]
    size = float(n)
    for u in units:
        if size < 1024 or u == units[-1]:
            return f"{size:.0f} {u}" if u == "B" else f"{size:.1f} {u}"
        size /= 1024
    return f"{n} B"


def _ordered_categories(assets):
    seen = [a.get("category") or "Other" for a in assets]
    extra = [c for c in dict.fromkeys(seen) if c not in CATEGORY_ORDER]
    return [c for c in CATEGORY_ORDER if c in seen] + extra


def build_index(assets, sizes, serve_base):
    """The self-contained browse page, deterministic for the same inputs."""
    teal = "#0f6e63"
    favicon = ("data:image/svg+xml;utf8," +
               urllib.parse.quote(LOGO_SVG.format(color=teal)))
    header_logo = LOGO_SVG.format(color="currentColor")

    def card(a):
        path = a["path"]
        title = html.escape(a.get("title") or path)
        desc = html.escape(a.get("description") or "")
        badge = ""
        if a.get("abi") == "v8":
            badge = '<span class="badge">64-bit</span>'
        elif a.get("abi") == "v7":
            badge = '<span class="badge">32-bit</span>'
        size = human_size(sizes.get(path))
        size_html = f'<span class="size">{size}</span>' if size else ""
        href = html.escape(path)
        return (
            '      <li class="asset">\n'
            f'        <div class="asset-head"><span class="asset-title">{title}</span>'
            f'{badge}</div>\n'
            f'        <p class="asset-desc">{desc}</p>\n'
            '        <div class="asset-foot">\n'
            f'          <a class="dl" href="{href}" download>Download</a>\n'
            f'          <button class="copy" data-path="{href}" type="button">Copy link</button>\n'
            f'          {size_html}\n'
            '        </div>\n'
            '      </li>\n'
        )

    ordered = [a for cat in _ordered_categories(assets)
               for a in assets if (a.get("category") or "Other") == cat]
    items = "".join(card(a) for a in ordered)

    css = """
:root{color-scheme:light dark;
  --bg:#f4f1ea;--card:#ffffff;--ink:#1b1b1b;--muted:#5a5a52;--line:#e2ddd2;
  --accent:#0f6e63;--badge:#eef2f0;--badge-ink:#2a5c55;}
@media (prefers-color-scheme:dark){:root{
  --bg:#14130f;--card:#1e1d19;--ink:#ececec;--muted:#a8a69c;--line:#2d2b25;
  --accent:#5fd0c2;--badge:#23302d;--badge-ink:#9fe0d6;}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);
  font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;line-height:1.5}
.wrap{max-width:900px;margin:0 auto;padding:16px}
header.top{display:flex;gap:14px;align-items:flex-start;padding:8px 0 16px}
header.top .logo{width:40px;height:40px;color:var(--accent);flex:0 0 auto}
header.top .logo svg{width:40px;height:40px}
header.top h1{margin:0;font-size:1.5rem}
header.top p{margin:4px 0 0;color:var(--muted);max-width:60ch}
ul.assets{list-style:none;margin:0;padding:0;display:grid;gap:12px;
  grid-template-columns:repeat(auto-fill,minmax(260px,1fr))}
.asset{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px;
  display:flex;flex-direction:column}
.asset-head{display:flex;flex-wrap:wrap;align-items:center;gap:8px}
.asset-title{font-weight:600}
.badge{font-size:.72rem;padding:2px 8px;border-radius:999px;background:var(--badge);
  color:var(--badge-ink);white-space:nowrap}
.asset-desc{margin:8px 0 12px;color:var(--muted);font-size:.9rem}
.asset-foot{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-top:auto}
a.dl{background:var(--accent);color:#fff;text-decoration:none;font-weight:600;
  padding:6px 14px;border-radius:999px;font-size:.88rem}
button.copy{background:transparent;border:1px solid var(--line);color:var(--ink);
  padding:6px 12px;border-radius:999px;font-size:.84rem;cursor:pointer}
.size{margin-left:auto;color:var(--muted);font-size:.82rem;
  font-variant-numeric:tabular-nums}
footer{color:var(--muted);font-size:.8rem;margin:24px 0 8px}
""".strip()

    # Copy-link uses the live origin so the pasted URL is the box's own address,
    # with no IP baked into the page. Download links are plain relative hrefs and
    # work with no script.
    script = (
        "document.querySelectorAll('button.copy').forEach(function(b){"
        "b.addEventListener('click',function(){"
        "var u=location.origin+'" + serve_base + "/'+b.dataset.path;"
        "navigator.clipboard&&navigator.clipboard.writeText(u);"
        "var t=b.textContent;b.textContent='Copied';"
        "setTimeout(function(){b.textContent=t;},1200);});});"
    )

    doc = (
        '<!doctype html>\n<html lang="en">\n<head>\n'
        '<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
        '<title>Code on the Go build assets</title>\n'
        f'<link rel="icon" href="{favicon}">\n'
        f'<style>\n{css}\n</style>\n'
        '</head>\n<body>\n  <div class="wrap">\n'
        f'    <header class="top"><span class="logo">{header_logo}</span>\n'
        '      <div><h1>Code on the Go build assets</h1>\n'
        '      <p>The build environment for Code on the Go, served from this box so a '
        'device on the local network can set it up with no internet. Download a file, '
        'or use "Copy link" to get its address.</p></div>\n'
        '    </header>\n'
        f'    <ul class="assets">\n{items}    </ul>\n'
        '    <footer>Served offline by Knowledge to Go.</footer>\n'
        '  </div>\n'
        f'  <script>{script}</script>\n'
        '</body>\n</html>\n'
    )
    return doc.encode("utf-8")


def _staged_sizes_from(tree, assets):
    sizes = {}
    for a in assets:
        p = tree / a["path"]
        try:
            sizes[a["path"]] = p.stat().st_size
        except OSError:
            pass
    return sizes


def mirror(source_base, serve_base, out, manifest_path=MANIFEST_DEFAULT,
           reuse_from=None, plan_only=False, verbose=True):
    source_base = source_base.rstrip("/")
    serve_base = "/" + serve_base.strip("/")
    out = Path(out)
    reuse_from = Path(reuse_from) if reuse_from else None
    assets = load_manifest(manifest_path)

    published = {a["path"]: published_md5(source_base, a["path"]) for a in assets}
    reuse_ok = {}
    for a in assets:
        p = a["path"]
        reuse_ok[p] = (
            reuse_from is not None
            and published[p] is not None
            and served_md5(reuse_from, p) == published[p]
            and (reuse_from / p).is_file()
        )

    # Same-build short-circuit: every file reuses AND the page would be identical.
    if reuse_from is not None and all(reuse_ok[a["path"]] for a in assets):
        sizes = _staged_sizes_from(reuse_from, assets)
        would_be = build_index(assets, sizes, serve_base)
        try:
            served_index_same = (reuse_from / INDEX).read_bytes() == would_be
        except OSError:
            served_index_same = False
        if served_index_same:
            print("result: up-to-date")
            print("done: 0 downloaded, 0 reused, 0 failed")
            return True

    out.mkdir(parents=True, exist_ok=True)
    if verbose:
        n_reuse = sum(1 for a in assets if reuse_ok[a["path"]])
        print(f"plan: {len(assets)} assets, {n_reuse} reusable, "
              f"{len(assets) - n_reuse} to fetch")

    downloaded = reused = failed = 0
    for a in assets:
        p = a["path"]
        dest = out / p
        dest.parent.mkdir(parents=True, exist_ok=True)

        if reuse_ok[p]:
            if not plan_only:
                shutil.copyfile(reuse_from / p, dest)
                shutil.copyfile(reuse_from / f"{p}.md5", dest.with_name(dest.name + ".md5"))
            reused += 1
            continue

        if plan_only:
            if verbose:
                print(f"  WOULD GET {p}")
            continue

        try:
            data = fetch(f"{source_base}/{p}")
        except RuntimeError as e:
            failed += 1
            print(f"  FAIL {p}: {e}", file=sys.stderr)
            continue
        want = published[p]
        if want is None:
            failed += 1
            print(f"  NO MD5 {p}: source published no checksum", file=sys.stderr)
            continue
        got = md5_of(data)
        if got != want:
            failed += 1
            print(f"  MD5 MISMATCH {p}: got {got[:12]} want {want[:12]}", file=sys.stderr)
            continue
        dest.write_bytes(data)
        dest.with_name(dest.name + ".md5").write_text(f"{want}  {Path(p).name}\n",
                                                      encoding="utf-8")
        downloaded += 1
        if verbose:
            print(f"  GET  {p} ({len(data)} B, verified)")

    if plan_only:
        print(f"done: {downloaded} downloaded, {reused} reused, {failed} failed")
        return failed == 0

    sizes = _staged_sizes_from(out, assets)
    (out / INDEX).write_bytes(build_index(assets, sizes, serve_base))
    if verbose:
        print(f"wrote {INDEX} ({len(assets)} assets)")

    print(f"done: {downloaded} downloaded, {reused} reused, {failed} failed")
    return failed == 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="Mirror the Code on the Go build assets.")
    ap.add_argument("--source-base", default="https://appdevforall.org/dev-assets",
                    help="site to mirror from (moves to R2; only this default changes)")
    ap.add_argument("--serve-base", default="/code-assets",
                    help="local serve path, used for the copy-link URLs on the page")
    ap.add_argument("--manifest", default=str(MANIFEST_DEFAULT),
                    help="asset manifest (paths, titles, descriptions)")
    ap.add_argument("--out", required=True, help="output mirror directory")
    ap.add_argument("--reuse-from", default=None,
                    help="the currently served tree; reuse unchanged files from it "
                         "instead of re-downloading (compared by the published .md5)")
    ap.add_argument("--plan-only", action="store_true",
                    help="test only: fetch the .md5 set and report the plan, "
                         "download no large file and write no page")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)
    ok = mirror(args.source_base, args.serve_base, args.out,
                manifest_path=args.manifest, reuse_from=args.reuse_from,
                plan_only=args.plan_only, verbose=not args.quiet)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
