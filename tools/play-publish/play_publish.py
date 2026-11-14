#!/usr/bin/env python3
"""Upload a K2Go release candidate to Google Play (K2GO-564).

Design: controller/docs/ADR-K2GO-564-play-release-candidates.md.
API: https://developers.google.com/android-publisher/api-ref/rest

Standard library only. The JWT for the service account is signed with the openssl CLI,
so the runner needs no pip packages.

Commands (the service account JSON comes from $PLAY_SERVICE_ACCOUNT_JSON):
  plan --gradle F --notes F --tags F     print version, rc tag and release notes as GitHub outputs
  next-code --package P --base N         print the Play versionCode for the next upload
  final-tag --rc-tag T --tags F          print the release tag for an approved candidate
  check-release --gradle F --tag T       fail unless versionName is exactly the final tag T
  publish --package P --track T --aab F --code N --name S --notes-file F
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD_API = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
NOTES_MAX = 500  # Play limit for "What's new" per language.
SEMVER = re.compile(r"^v\d+\.\d+\.\d+$")
LABEL = re.compile(r"^(v\d+\.\d+\.\d+)(?:-rc\.(\d+))?$")
RC_TAG = re.compile(r"^(v\d+\.\d+\.\d+)-rc\.(\d+)$")


class PlanError(Exception):
    pass


# ---- pure rules (unit-tested) -------------------------------------------------------------

def version_name(gradle_text):
    """The version label in build.gradle: "v1.2.0-rc.1" for a candidate, "v1.2.0" for the
    final release. The same label names the git tag, the Play release and the R2 files."""
    m = re.search(r'versionName\s+"(v[^"$]+)\$\{ciSuffix\}"', gradle_text)
    if not m:
        raise PlanError("versionName not found in build.gradle")
    if not LABEL.match(m.group(1)):
        raise PlanError("versionName %r is not vMAJOR.MINOR.PATCH[-rc.N]" % m.group(1))
    return m.group(1)


def candidate_tag(tags, label):
    """The rc tag of a candidate build is its versionName. It must be the next candidate of an
    unreleased version, so a used, skipped or final label is refused."""
    m = LABEL.match(label)
    if not m or m.group(2) is None:
        raise PlanError("versionName %r is not a candidate (vX.Y.Z-rc.N)" % label)
    expected = next_rc_tag(tags, m.group(1))
    if label != expected:
        raise PlanError("versionName is %s; the next candidate is %s" % (label, expected))
    return label


def check_release(gradle_text, tag):
    """A release tag goes only on a build whose versionName is that exact final version:
    an rc build is never released as final."""
    label = version_name(gradle_text)
    if label != tag or not SEMVER.match(tag):
        raise PlanError("versionName is %s, so this build cannot be released as %s" % (label, tag))
    return tag


def version_code_base(gradle_text):
    m = re.search(r"^\s*versionCode\s+(\d+)\s*$", gradle_text, re.M)
    if not m:
        raise PlanError("versionCode not found in build.gradle")
    return int(m.group(1))


def _rc_numbers(tags, version):
    pattern = re.compile(r"^" + re.escape(version) + r"-rc\.(\d+)$")
    return [int(m.group(1)) for m in (pattern.match(t) for t in tags) if m]


def next_rc_tag(tags, version):
    """Next "vX.Y.Z-rc.N" for this version. A released version (its final tag exists) is
    closed: a new change after a release is a new version (semver item 3)."""
    if version in tags:
        raise PlanError("%s is already released; bump versionName first" % version)
    return "%s-rc.%d" % (version, max(_rc_numbers(tags, version), default=0) + 1)


def final_tag(tags, rc_tag):
    """The release tag for an approved candidate: "v1.2.0-rc.2" -> "v1.2.0". Only the newest
    candidate can be released, because each new candidate replaces the older one in Play."""
    m = RC_TAG.match(rc_tag)
    if not m:
        raise PlanError("%r is not a candidate tag (vX.Y.Z-rc.N)" % rc_tag)
    version = m.group(1)
    if rc_tag not in tags:
        raise PlanError("%s does not exist" % rc_tag)
    if version in tags:
        raise PlanError("%s already exists; a released version is closed" % version)
    newest = max(_rc_numbers(tags, version))
    if int(m.group(2)) != newest:
        raise PlanError("%s-rc.%d is newer than %s; Play holds the newest candidate"
                        % (version, newest, rc_tag))
    return version


def release_notes(md_text, version):
    """Body of the top entry of controller/ci/ota-release-notes.md. Same source and same
    header rule as the OTA update.json, so Play and the app show the same words."""
    sections = re.split(r"^## +", md_text, flags=re.M)
    if len(sections) < 2:
        raise PlanError("no '## <version>' entry in the release notes")
    header, _, body = sections[1].partition("\n")
    if header.strip() != version:
        raise PlanError("top release-notes entry is %r, expected %r" % (header.strip(), version))
    text = "\n".join(line for line in body.splitlines() if line.strip()).strip()
    if not text:
        raise PlanError("release notes for %s are empty" % version)
    if len(text) > NOTES_MAX:
        raise PlanError("release notes are %d characters; Play allows %d" % (len(text), NOTES_MAX))
    return text


def next_version_code(used_codes, base):
    """Play rejects a versionCode it has seen, so every upload takes the highest used + 1.
    The floor base*10 matches the code an APK of the same build.gradle carries."""
    return max([base * 10] + [c + 1 for c in used_codes])


def used_version_codes(tracks, bundles, apks):
    """Every versionCode Play knows: on a track, or uploaded but never released."""
    codes = set()
    for track in tracks.get("tracks", []):
        for release in track.get("releases", []):
            codes.update(int(c) for c in release.get("versionCodes", []))
    codes.update(int(b["versionCode"]) for b in bundles.get("bundles", []))
    codes.update(int(a["versionCode"]) for a in apks.get("apks", []))
    return codes


def track_release(track, name, code, notes):
    return {
        "track": track,
        "releases": [{
            "name": name,
            "versionCodes": [str(code)],
            "status": "completed",
            "releaseNotes": [{"language": "en-US", "text": notes}],
        }],
    }


# ---- Play API --------------------------------------------------------------------------

def _b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=")


def access_token(account):
    """OAuth2 service-account flow (RFC 7523): a signed JWT is exchanged for a token."""
    now = int(time.time())
    header = _b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = _b64(json.dumps({"iss": account["client_email"], "scope": SCOPE,
                              "aud": account["token_uri"], "iat": now, "exp": now + 3600}).encode())
    signing_input = header + b"." + claims
    with tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False) as key:
        key.write(account["private_key"])
    try:
        signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key.name],
                                   input=signing_input, capture_output=True, check=True).stdout
    finally:
        os.unlink(key.name)
    body = urllib.parse.urlencode({"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                                   "assertion": (signing_input + b"." + _b64(signature)).decode()})
    with urllib.request.urlopen(account["token_uri"], body.encode(), timeout=60) as r:
        return json.load(r)["access_token"]


class Play:
    def __init__(self, package):
        self.package = package
        self.token = access_token(json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"]))

    def call(self, method, url, body=None, data=None, content_type="application/json"):
        if body is not None:
            data = json.dumps(body).encode()
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self.token)
        if data is not None:
            req.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(req, timeout=900) as r:
                raw = r.read()
        except urllib.error.HTTPError as e:
            # Play explains rejections in the body (pre-review checks, duplicate code, ...).
            sys.exit("Play API %s %s -> HTTP %d\n%s" % (method, url.split("?")[0], e.code,
                                                       e.read().decode(errors="replace")))
        return json.loads(raw) if raw else {}

    def edit_url(self, edit_id, path=""):
        return "%s/%s/edits/%s%s" % (API, self.package, edit_id, path)

    def open_edit(self):
        return self.call("POST", "%s/%s/edits" % (API, self.package), body={})["id"]


def cmd_plan(args):
    gradle = open(args.gradle, encoding="utf-8").read()
    tags = set(open(args.tags, encoding="utf-8").read().split())
    rc = candidate_tag(tags, version_name(gradle))
    version = RC_TAG.match(rc).group(1)
    notes = release_notes(open(args.notes, encoding="utf-8").read(), version)
    with open(args.notes_out, "w", encoding="utf-8") as f:
        f.write(notes)
    print("version=%s" % version)
    print("rc_tag=%s" % rc)
    print("code_base=%d" % version_code_base(gradle))


def cmd_next_code(args):
    play = Play(args.package)
    edit = play.open_edit()
    try:
        used = used_version_codes(play.call("GET", play.edit_url(edit, "/tracks")),
                                  play.call("GET", play.edit_url(edit, "/bundles")),
                                  play.call("GET", play.edit_url(edit, "/apks")))
    finally:
        try:
            play.call("DELETE", play.edit_url(edit))
        except SystemExit:
            pass  # Keep the first error; an unused edit expires on its own.
    print(next_version_code(used, args.base))


def cmd_final_tag(args):
    tags = set(open(args.tags, encoding="utf-8").read().split())
    print(final_tag(tags, args.rc_tag))


def cmd_check_release(args):
    print(check_release(open(args.gradle, encoding="utf-8").read(), args.tag))


def cmd_publish(args):
    play = Play(args.package)
    notes = open(args.notes_file, encoding="utf-8").read()
    edit = play.open_edit()
    with open(args.aab, "rb") as f:
        bundle = play.call("POST", "%s/%s/edits/%s/bundles?uploadType=media"
                           % (UPLOAD_API, args.package, edit),
                           data=f.read(), content_type="application/octet-stream")
    if int(bundle["versionCode"]) != args.code:
        sys.exit("uploaded bundle has versionCode %s, expected %d" % (bundle["versionCode"], args.code))
    play.call("PUT", play.edit_url(edit, "/tracks/" + args.track),
              body=track_release(args.track, args.name, args.code, notes))
    # ERROR_IF_IN_REVIEW: the default (CANCEL_IN_REVIEW_AND_SUBMIT) would silently cancel a
    # candidate that Google is reviewing now. Fail instead, and let a person decide.
    play.call("POST", play.edit_url(edit, ":commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW"))
    print("Sent %s (versionCode %d) to the %s track for review." % (args.name, args.code, args.track))


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("plan")
    s.add_argument("--gradle", required=True)
    s.add_argument("--notes", required=True)
    s.add_argument("--tags", required=True, help="file with one git tag name per line")
    s.add_argument("--notes-out", required=True)
    s.set_defaults(func=cmd_plan)
    s = sub.add_parser("next-code")
    s.add_argument("--package", required=True)
    s.add_argument("--base", required=True, type=int)
    s.set_defaults(func=cmd_next_code)
    s = sub.add_parser("final-tag")
    s.add_argument("--rc-tag", required=True)
    s.add_argument("--tags", required=True, help="file with one git tag name per line")
    s.set_defaults(func=cmd_final_tag)
    s = sub.add_parser("check-release")
    s.add_argument("--gradle", required=True)
    s.add_argument("--tag", required=True)
    s.set_defaults(func=cmd_check_release)
    s = sub.add_parser("publish")
    s.add_argument("--package", required=True)
    s.add_argument("--track", required=True, choices=["internal", "alpha", "beta", "production"])
    s.add_argument("--aab", required=True)
    s.add_argument("--code", required=True, type=int)
    s.add_argument("--name", required=True)
    s.add_argument("--notes-file", required=True)
    s.set_defaults(func=cmd_publish)
    args = p.parse_args(argv)
    try:
        args.func(args)
    except PlanError as e:
        sys.exit("::error::" + str(e))


if __name__ == "__main__":
    main()
