"""Self-hosted F-Droid repository of Knowledge to Go on Cloudflare R2 (K2GO-421).

Design: controller/docs/ADR-K2GO-421-self-hosted-fdroid-repo.md.

R2 has no server code and no directory listing. The F-Droid client does not
need them: it reads repo/entry.jar, then the index that entry.jar names, then
APKs and icons, at fixed paths. A person in a browser gets index.html, which
this script makes from the signed index.

The workflow app-fdroid-repo.yml runs the steps. Each one is a subcommand:

  config  write config.yml for fdroid update. It holds no secret: the keystore
          passwords and the key alias come from environment variables.
  qr      write the QR code of the "add this repository" link (in the container).
  verify  stop when the release APK is not in the index, or another key signed it.
  page    write the landing page <work>/index.html from repo/index-v2.json.
  plan    print the upload order (and the deletions) for the R2 copy.

Python stdlib only. Tests: python -m unittest -v test_fdroid_repo
"""

import argparse
import datetime as dt
import hashlib
import html
import json
import re
import sys
from pathlib import Path, PurePosixPath

APP_ID = "org.appdevforall.k2go"
HERE = Path(__file__).resolve().parent
# The QR code of the add_repo_link(), next to the landing page (publish.sh makes it).
ADD_REPO_QR = "add-repo-qr.png"
# The look of the add-ons site (appdevforall/plugin-examples, site/): the page links these files
# under assets/ with a content hash in the name. Cloudflare can keep a .css or .svg in a browser
# for hours whatever Cache-Control says, so new bytes get a new name (the add-ons site does the same).
SITE = HERE / "site"
SITE_ASSETS = ("styles.css", "fdroid.css", "adfa-logo.svg")
# The app icon on the page is an SVG made from the adaptive icon of the app (one source for the
# icon: these vector drawables). A PNG of 512 px goes soft on a phone screen at 3x density.
APP_RES = HERE.parent.parent / "controller" / "app" / "src" / "main" / "res" / "drawable"
ICON_LAYERS = (APP_RES / "ic_launcher_background.xml", APP_RES / "ic_launcher_foreground.xml")
ICON_ASSET = "k2go-icon.svg"

# The repo folders that fdroid update writes and that go to R2. status/ is the
# run log of fdroidserver: it stays local.
REPO_DIRS = ("repo", "archive")
LOCAL_ONLY = ("status/",)

TYPES = {
    ".apk": "application/vnd.android.package-archive",
    ".jar": "application/java-archive",
    ".json": "application/json",
    ".xml": "application/xml",
    ".html": "text/html; charset=utf-8",
    ".css": "text/css",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".svg": "image/svg+xml",
}
SHORT = "public, max-age=60"
LONG = "public, max-age=31536000, immutable"

# minSdk -> the Android version that a person knows.
ANDROID = {21: "5.0", 22: "5.1", 23: "6.0", 24: "7.0", 25: "7.1", 26: "8.0",
           27: "8.1", 28: "9", 29: "10", 30: "11", 31: "12", 32: "12L",
           33: "13", 34: "14", 35: "15", 36: "16"}


# --- config --------------------------------------------------------------------------------

def config_yml(repo_url: str, keystore: str) -> str:
    """config.yml for fdroid update.

    The repo index is signed with the app signing key (decision of the
    maintainer, K2GO-421): the same keystore and the same GitHub secrets that
    sign the APK. fdroidserver reads {env: NAME} values from the environment,
    so no secret is in the file.
    """
    url = repo_url.rstrip("/")
    if not url.endswith("/repo"):
        raise ValueError(f"repo URL must end with /repo: {repo_url}")
    archive = url[: -len("/repo")] + "/archive"
    return "\n".join([
        "# Written by tools/fdroid-repo/fdroid_repo.py config. Do not edit.",
        f"repo_url: {json.dumps(url)}",
        'repo_name: "Knowledge to Go (App Dev for All)"',
        'repo_description: "The F-Droid repository of Knowledge to Go, self-hosted by '
        'App Dev for All. It ships the F-Droid flavor of the app, signed with the same key '
        'as the downloads on the website."',
        "repo_icon: k2go.png",
        f"archive_url: {json.dumps(archive)}",
        'archive_name: "Knowledge to Go (App Dev for All) archive"',
        'archive_description: "Older versions of Knowledge to Go."',
        "archive_icon: k2go.png",
        # Keep the three newest versions in repo/; older ones move to archive/.
        "archive_older: 3",
        f"keystore: {json.dumps(keystore)}",
        "repo_keyalias: {env: KEY_ALIAS}",
        "keystorepass: {env: KEYSTORE_PASSWORD}",
        "keypass: {env: KEY_PASSWORD}",
        "",
    ])


# --- page ----------------------------------------------------------------------------------

def esc(value) -> str:
    return html.escape(str(value), quote=True)


def en(localized) -> str:
    """The en-US value of a localized field, else the first value."""
    if not localized:
        return ""
    return localized.get("en-US") or next(iter(localized.values()))


def size(n: int) -> str:
    return f"{n / 1048576:.1f} MB"


def day(ms: int) -> str:
    return dt.datetime.fromtimestamp(ms / 1000, dt.timezone.utc).strftime("%Y-%m-%d")


def spaced(hex_value: str) -> str:
    h = hex_value.replace(":", "").replace(" ", "").upper()
    return " ".join(h[i:i + 2] for i in range(0, len(h), 2))


def add_repo_link(address: str, fingerprint: str) -> str:
    """The link that opens the "add repository" screen of the F-Droid app.

    The F-Droid app claims https://fdroid.link as a verified App Link, so Android
    opens the app directly. Without the app, the browser shows fdroid.link, which
    explains how to get F-Droid. The repo address is in the fragment (after "#"),
    so the browser does not send it to fdroid.link. The app no longer claims the
    fdroidrepo:// scheme of plain-HTTP repos; this link works for HTTP and HTTPS.
    """
    fp = fingerprint.replace(":", "").replace(" ", "").upper()
    return f"https://fdroid.link/#{address.rstrip('/')}?fingerprint={fp}"


def hashed_name(name: str, data: bytes) -> str:
    """styles.css -> styles.<8 hex of sha256>.css"""
    stem, _, suffix = name.rpartition(".")
    return f"{stem}.{hashlib.sha256(data).hexdigest()[:8]}.{suffix}"


ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
# Adaptive icon: a 108 dp canvas; every launcher mask shows at least its central 66 dp.
CANVAS_DP, SAFE_DP = 108, 66
# The only attributes the converter knows. Anything else stops the run: a half-drawn icon on
# the public page is worse than a failed publish.
VECTOR_ATTRS = {"width", "height", "viewportWidth", "viewportHeight"}
GROUP_ATTRS = {"scaleX", "scaleY", "translateX", "translateY", "pivotX", "pivotY", "rotation"}
PATH_ATTRS = {"pathData", "fillColor", "fillAlpha"}


def _num(value) -> str:
    v = float(value)
    return f"{0.0 if v == 0 else v:g}"   # no "-0" in the SVG


def _color(value: str) -> tuple:
    """#RGB, #RRGGBB or #AARRGGBB -> (#RRGGBB, opacity)."""
    if not re.fullmatch(r"#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})", value):
        raise ValueError(f"color {value!r}: only #RGB, #RRGGBB and #AARRGGBB are known")
    h = value[1:]
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    if len(h) == 8:
        return "#" + h[2:], int(h[:2], 16) / 255
    return "#" + h, 1.0


def _check(node, known: set) -> dict:
    attrs = {}
    for key, value in node.attrib.items():
        if not key.startswith(ANDROID_NS):
            continue
        name = key[len(ANDROID_NS):]
        if name not in known:
            raise ValueError(f"<{node.tag} android:{name}> is not known to the SVG converter")
        attrs[name] = value
    return attrs


def _svg_children(node) -> list:
    out = []
    for child in node:
        if child.tag == "group":
            a = _check(child, GROUP_ATTRS)
            px, py = float(a.get("pivotX", 0)), float(a.get("pivotY", 0))
            tx, ty = float(a.get("translateX", 0)), float(a.get("translateY", 0))
            # Android: scale and rotate about the pivot, then translate.
            transform = (f"translate({_num(tx + px)} {_num(ty + py)}) "
                         f"rotate({_num(a.get('rotation', 0))}) "
                         f"scale({_num(a.get('scaleX', 1))} {_num(a.get('scaleY', 1))}) "
                         f"translate({_num(-px)} {_num(-py)})")
            out.append(f'<g transform="{transform}">' + "".join(_svg_children(child)) + "</g>")
        elif child.tag == "path":
            a = _check(child, PATH_ATTRS)
            if "fillColor" not in a:
                # Android draws no fill when fillColor is missing (SVG would draw black).
                out.append(f'<path d="{html.escape(a["pathData"])}" fill="none"/>')
                continue
            fill, opacity = _color(a["fillColor"])
            opacity *= float(a.get("fillAlpha", 1))
            extra = "" if opacity == 1 else f' fill-opacity="{_num(opacity)}"'
            out.append(f'<path d="{html.escape(a["pathData"])}" fill="{fill}"{extra}/>')
        else:
            raise ValueError(f"<{child.tag}> is not known to the SVG converter")
    return out


def launcher_svg(background: Path, foreground: Path) -> str:
    """The adaptive launcher icon of the app (two vector drawables) as one square SVG.

    The SVG shows the central 66 dp of the 108 dp canvas: the area that every launcher mask
    shows. The rest of the canvas is a margin for masks, and with it the icon looks small.
    """
    import xml.etree.ElementTree as ET
    fg = ET.parse(foreground).getroot()
    bg = ET.parse(background).getroot()
    fa, ba = _check(fg, VECTOR_ATTRS), _check(bg, VECTOR_ATTRS)
    w, h = float(fa["viewportWidth"]), float(fa["viewportHeight"])
    sx, sy = w / float(ba["viewportWidth"]), h / float(ba["viewportHeight"])
    vw, vh = w * SAFE_DP / CANVAS_DP, h * SAFE_DP / CANVAS_DP
    box = f"{_num((w - vw) / 2)} {_num((h - vh) / 2)} {_num(vw)} {_num(vh)}"
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{box}">'
            f'<g transform="scale({_num(sx)} {_num(sy)})">' + "".join(_svg_children(bg)) + "</g>"
            + "".join(_svg_children(fg)) + "</svg>\n")


def write_assets(work: Path, site: Path = SITE, icon_layers: tuple = ICON_LAYERS) -> dict:
    """Write the site assets to <work>/assets/ under hashed names. Returns name -> URL path."""
    out = work / "assets"
    out.mkdir(parents=True, exist_ok=True)
    files = {name: (site / name).read_bytes() for name in SITE_ASSETS}
    files[ICON_ASSET] = launcher_svg(*icon_layers).encode("ascii")
    urls = {}
    for name, data in files.items():
        target = out / hashed_name(name, data)
        target.write_bytes(data)
        urls[name] = f"assets/{target.name}"
    return urls


def render(index: dict, fingerprint: str, template: str, assets: dict = None) -> str:
    repo = index["repo"]
    address = repo["address"].rstrip("/")
    package = index["packages"][APP_ID]
    meta = package["metadata"]
    versions = sorted(package["versions"].values(),
                      key=lambda v: v["manifest"]["versionCode"], reverse=True)
    latest = versions[0]
    manifest = latest["manifest"]
    min_sdk = manifest["usesSdk"]["minSdkVersion"]

    rows = "\n".join(
        f'<tr><td>{esc(v["manifest"]["versionName"])}</td>'
        f'<td>{esc(day(v["added"]))}</td>'
        f'<td>{esc(size(v["file"]["size"]))}</td>'
        f'<td><a href="repo{esc(v["file"]["name"])}">APK</a></td></tr>'
        for v in versions)

    values = {
        "repo_name": esc(en(repo["name"])),
        "repo_description": esc(en(repo.get("description"))),
        "address": esc(address),
        "add_link": esc(add_repo_link(address, fingerprint)),
        "fingerprint": esc(spaced(fingerprint)),
        "app_name": esc(en(meta["name"])),
        "summary": esc(en(meta.get("summary"))),
        "version": esc(manifest["versionName"]),
        "version_code": esc(manifest["versionCode"]),
        "updated": esc(day(latest["added"])),
        "apk_size": esc(size(latest["file"]["size"])),
        "apk_sha256": esc(latest["file"]["sha256"]),
        "android": esc(ANDROID.get(min_sdk, f"API {min_sdk}")),
        "abis": esc(", ".join(manifest.get("nativecode", [])) or "all"),
        "signer": esc(spaced(manifest["signer"]["sha256"][0])),
        "source": esc(meta.get("sourceCode", "")),
        "license": esc(meta.get("license", "")),
        "rows": rows,
        "generated": esc(day(repo["timestamp"])),
        "asset_qr": esc(ADD_REPO_QR),
    }
    for name, url in (assets or {}).items():
        values[f"asset:{name}"] = esc(url)
    out = template
    for key, value in values.items():
        out = out.replace("{{" + key + "}}", value)
    return out


# --- plan ----------------------------------------------------------------------------------

def stage(rel: str) -> int:
    """Upload stage of a file in the work folder. Lower stages go first.

    The client trusts entry.jar, and entry.jar names the index, and the index
    names APKs and icons. So upload in the other direction: a file is in R2
    before any published file names it (the same rule as update.json, ADFA-4984).
    """
    name = PurePosixPath(rel).name
    if name in ("entry.jar", "entry.json"):
        return 3
    if name.startswith("index-v") or name in ("index.jar", "index.xml") or "/diff/" in rel:
        return 2
    if rel == "index.html":     # the landing page, at the top of the prefix
        return 4
    return 1                    # APKs, icons, screenshots, the QR image, index.html of fdroidserver


def cache_control(rel: str) -> str:
    # An APK name carries its version; a localized icon or a site asset name carries its hash.
    name = PurePosixPath(rel).name
    if (name.endswith(".apk") or (name.startswith("icon_") and "/" in rel)
            or rel.startswith("assets/")):
        return LONG
    return SHORT


def local_files(work: Path) -> list:
    """Relative POSIX paths of the files to publish, from the work folder."""
    found = []
    for d in REPO_DIRS + ("assets",):
        base = work / d
        if not base.is_dir():
            continue
        for p in base.rglob("*"):
            if not p.is_file():
                continue
            rel = p.relative_to(work).as_posix()
            if any(rel[len(d) + 1:].startswith(x) for x in LOCAL_ONLY):
                continue
            found.append(rel)
    for top in (ADD_REPO_QR, "index.html"):
        if (work / top).is_file():
            found.append(top)
    return found


def plan(files: list, prefix: str, remote: list) -> list:
    """Upload lines, then delete lines, as tuples.

    ("put", key, rel, content_type, cache_control) and ("rm", key).
    Deletions come last: an APK that moved from repo/ to archive/ stays at its
    old key until the new index (that no longer names it) is up. A remote key
    that is not under repo/ or archive/ is never deleted.
    """
    prefix = prefix.strip("/")
    remote = set(remote)
    lines = []
    for rel in sorted(files, key=lambda r: (stage(r), r)):
        key = f"{prefix}/{rel}"
        # An APK key carries its version, so its bytes never change: an APK that
        # is in R2 already is not sent again (each one is about 40 MB).
        if rel.endswith(".apk") and key in remote:
            continue
        lines.append(("put", key, rel,
                      TYPES.get(PurePosixPath(rel).suffix, "application/octet-stream"),
                      cache_control(rel)))
    # The repo address itself ("<prefix>/repo", often with "?fingerprint=..."): a person can type
    # or paste it in a browser, and the QR code on repo/index.html of fdroidserver holds it. R2 has
    # no object there, so the landing page also goes to that key (no slash). Its relative links
    # resolve the same from there. Do not remove it: the browser then shows "Object not found".
    if "index.html" in files:
        lines.append(("put", f"{prefix}/repo", "index.html", TYPES[".html"], SHORT))
    keep = {f"{prefix}/{rel}" for rel in files}
    owned = tuple(f"{prefix}/{d}/" for d in REPO_DIRS)
    for key in sorted(remote):
        if key.startswith(owned) and key not in keep:
            lines.append(("rm", key))
    return lines


# --- verify --------------------------------------------------------------------------------

def verify(index: dict, tag: str, fingerprint: str) -> None:
    """Stop the publish when the new index is not what this release expects.

    The release APK must be in the index, and its signer must be the key that
    signed the index: the repo and the app share one key (K2GO-421). So an APK
    that the release key did not sign is never published.
    """
    fp = fingerprint.replace(":", "").replace(" ", "").lower()
    versions = index["packages"].get(APP_ID, {}).get("versions", {}).values()
    found = [v for v in versions if v["manifest"]["versionName"] == tag]
    if not found:
        raise ValueError(f"{tag} is not in the index")
    signers = found[0]["manifest"]["signer"]["sha256"]
    if signers != [fp]:
        raise ValueError(f"{tag} is signed by {signers}, the repo by {fp}")


def named_files(node) -> set:
    """Every file that an index-v2 names: a dict with "name" and "sha256"."""
    found = set()
    if isinstance(node, dict):
        if isinstance(node.get("name"), str) and "sha256" in node:
            found.add(node["name"])
        for value in node.values():
            found |= named_files(value)
    elif isinstance(node, list):
        for value in node:
            found |= named_files(value)
    return found


def missing_files(index: dict, repo_dir: Path) -> list:
    return sorted(n for n in named_files(index) if not (repo_dir / n.lstrip("/")).is_file())


# --- CLI -----------------------------------------------------------------------------------

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("config", help="write <work>/config.yml")
    c.add_argument("--work", type=Path, required=True)
    c.add_argument("--repo-url", required=True)
    c.add_argument("--keystore", required=True, help="keystore path, seen from the work folder")

    p = sub.add_parser("page", help="write <work>/index.html")
    p.add_argument("--work", type=Path, required=True)
    p.add_argument("--fingerprint", required=True, help="SHA-256 of the repo signing certificate")
    p.add_argument("--template", type=Path, default=SITE / "page.template.html")

    q = sub.add_parser("qr", help="write <work>/add-repo-qr.png (needs the qrcode module)")
    q.add_argument("--work", type=Path, required=True)
    q.add_argument("--repo-url", required=True)
    q.add_argument("--fingerprint", required=True)

    v = sub.add_parser("verify", help="the release is in the index, signed by the repo key")
    v.add_argument("--work", type=Path, required=True)
    v.add_argument("--tag", required=True)
    v.add_argument("--fingerprint", required=True)

    u = sub.add_parser("plan", help="print the upload and delete order, tab-separated")
    u.add_argument("--work", type=Path, required=True)
    u.add_argument("--prefix", required=True, help="R2 key prefix, for example fdroid")
    u.add_argument("--remote", type=Path, help="file with the remote keys under the prefix, one per line")

    args = parser.parse_args(argv)

    if args.cmd == "config":
        target = args.work / "config.yml"
        target.write_text(config_yml(args.repo_url, args.keystore), encoding="utf-8", newline="\n")
        # fdroidserver refuses a config with passwords that others can read.
        target.chmod(0o600)
        print(f"wrote {target}")
    elif args.cmd == "page":
        index = json.loads((args.work / "repo" / "index-v2.json").read_text(encoding="utf-8"))
        assets = write_assets(args.work)
        page = render(index, args.fingerprint, args.template.read_text(encoding="utf-8"), assets)
        missing = sorted(set(re.findall(r"\{\{[^}]+\}\}", page)))
        if missing:
            print(f"page: the template has unknown placeholders: {missing}", file=sys.stderr)
            return 1
        (args.work / "index.html").write_text(page, encoding="utf-8", newline="\n")
        print(f"wrote {args.work / 'index.html'}")
    elif args.cmd == "qr":
        # Runs in the fdroidserver container (python3-qrcode). A phone camera opens the link,
        # and Android gives it to the F-Droid app (see add_repo_link).
        import qrcode
        link = add_repo_link(args.repo_url, args.fingerprint)
        qrcode.make(link).save(str(args.work / ADD_REPO_QR))
        print(f"wrote {args.work / ADD_REPO_QR}: {link}")
    elif args.cmd == "verify":
        index = json.loads((args.work / "repo" / "index-v2.json").read_text(encoding="utf-8"))
        try:
            verify(index, args.tag, args.fingerprint)
        except ValueError as e:
            print(f"verify: {e}", file=sys.stderr)
            return 1
        # The index must not name a file that the upload does not send (an icon, an APK).
        for d in REPO_DIRS:
            path = args.work / d / "index-v2.json"
            if not path.is_file():
                continue
            missing = missing_files(json.loads(path.read_text(encoding="utf-8")), args.work / d)
            if missing:
                print(f"verify: {d}/index-v2.json names missing files: {missing}", file=sys.stderr)
                return 1
        print(f"verify: {args.tag} is in the index, signed by the repo key; no file is missing")
    elif args.cmd == "plan":
        remote = []
        if args.remote and args.remote.is_file():
            remote = [k.strip() for k in args.remote.read_text(encoding="utf-8").splitlines() if k.strip()]
        for line in plan(local_files(args.work), args.prefix, remote):
            print("\t".join(line))
    return 0


if __name__ == "__main__":
    sys.exit(main())
