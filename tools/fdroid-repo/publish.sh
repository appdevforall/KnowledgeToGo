#!/usr/bin/env bash
# Publish the self-hosted F-Droid repository (K2GO-421). Run by app-fdroid-repo.yml.
#
# R2 holds the state: each run pulls <prefix>/repo and <prefix>/archive, adds the
# release APK, runs fdroid update, and uploads in the order of fdroid_repo.py plan.
#
# Environment:
#   TAG                release tag, for example v1.2.0 (the APK is K2Go-$TAG-fdroid-release.apk)
#   PREFIX             key prefix: fdroid, or fdroid-staging
#   BASE_URL           public URL of the bucket, for example https://k2go-download.appdevforall.org
#   KEYSTORE_FILE      the app signing keystore (the repo index is signed with the same key)
#   KEY_ALIAS, KEYSTORE_PASSWORD, KEY_PASSWORD   the same secrets that sign the APK
#   R2 backend:    BUCKET_NAME, R2_ACCOUNT_ID, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
#   Local backend: LOCAL_BUCKET=<folder> uses a folder as the bucket (local test only)
#   FDROID_IMAGE       Docker image for fdroid update (default debian:trixie, fdroidserver from apt)
#   WORK               work folder (default: a new temporary folder)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
: "${TAG:?}" "${PREFIX:?}" "${BASE_URL:?}" "${KEYSTORE_FILE:?}"
: "${KEY_ALIAS:?}" "${KEYSTORE_PASSWORD:?}" "${KEY_PASSWORD:?}"
FDROID_IMAGE="${FDROID_IMAGE:-debian:trixie}"
PREFIX="${PREFIX%/}"
apk_name="K2Go-${TAG}-fdroid-release.apk"
export REPO_URL="$BASE_URL/$PREFIX/repo"
work="${WORK:-$(mktemp -d)}"
py="$(command -v python3 || command -v python)"

# --- storage backend: R2 (aws CLI) or a local folder -------------------------------------
if [ -n "${LOCAL_BUCKET:-}" ]; then
  s_list()  { (cd "$LOCAL_BUCKET" && find "$PREFIX" -type f 2>/dev/null | sed 's#/\.r2-object$##') || true; }
  s_pull()  { if [ -d "$LOCAL_BUCKET/$1" ]; then cp -r "$LOCAL_BUCKET/$1/." "$2/"; rm -f "$2/.r2-object"; fi; }
  s_get()   { cp "$LOCAL_BUCKET/$1" "$2"; }
  # R2 keys are flat: "fdroid/repo" and "fdroid/repo/entry.jar" can both exist. A folder cannot
  # also be a file, so the local bucket keeps the object of a folder key in <folder>/.r2-object.
  s_put()   { local dest="$LOCAL_BUCKET/$2"
              if [ -d "$dest" ]; then dest="$dest/.r2-object"; fi
              mkdir -p "$(dirname "$dest")"; cp "$1" "$dest"; }
  s_rm()    { rm -f "$LOCAL_BUCKET/$1"; }
else
  : "${BUCKET_NAME:?}" "${R2_ACCOUNT_ID:?}"
  endpoint="https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com"
  # An empty prefix (the first publish) is not an error: list-objects-v2 prints "None".
  s_list()  { aws s3api list-objects-v2 --bucket "$BUCKET_NAME" --prefix "$PREFIX/" \
                --endpoint-url "$endpoint" --query 'Contents[].Key' --output text \
                | tr '\t' '\n' | grep -v '^None$' || true; }
  s_pull()  { aws s3 sync "s3://$BUCKET_NAME/$1/" "$2/" --endpoint-url "$endpoint" --only-show-errors; }
  s_get()   { aws s3 cp "s3://$BUCKET_NAME/$1" "$2" --endpoint-url "$endpoint" --only-show-errors; }
  s_put()   { aws s3 cp "$1" "s3://$BUCKET_NAME/$2" --endpoint-url "$endpoint" --only-show-errors \
                --content-type "$3" --cache-control "$4"; }
  s_rm()    { aws s3 rm "s3://$BUCKET_NAME/$1" --endpoint-url "$endpoint" --only-show-errors; }
fi

# The keystore copy lives in the work folder only while fdroid update runs.
trap 'rm -f "$work/keystore.jks"' EXIT

echo "== work folder: $work"
mkdir -p "$work/repo" "$work/archive" "$work/metadata"

echo "== pull the published repo from $PREFIX/ (index and APKs: fdroid update keeps the added dates)"
s_pull "$PREFIX/repo" "$work/repo"
s_pull "$PREFIX/archive" "$work/archive"
s_list > "$work/remote-keys.txt"
# The localized images (repo/<app id>/) come from metadata/ on each run. A pulled copy makes
# fdroid update drop the hashed icon file that its new index still names.
rm -rf "$work/repo/org.appdevforall.k2go" "$work/archive/org.appdevforall.k2go"

echo "== add $apk_name"
if [ ! -f "$work/repo/$apk_name" ] && [ ! -f "$work/archive/$apk_name" ]; then
  s_get "$apk_name" "$work/repo/$apk_name"
fi

echo "== config, metadata, icons"
cp -r "$here/metadata/." "$work/metadata/"
mkdir -p "$work/metadata/org.appdevforall.k2go/en-US"
icon="$here/../../controller/app/src/main/ic_launcher-playstore.png"
cp "$icon" "$work/metadata/org.appdevforall.k2go/en-US/icon.png"
cp "$icon" "$work/k2go.png"
cp "$KEYSTORE_FILE" "$work/keystore.jks"
"$py" "$here/fdroid_repo.py" config --work "$work" \
  --repo-url "$REPO_URL" --keystore keystore.jks

echo "== fdroid update ($FDROID_IMAGE)"
vol="$work"; tools="$here"
if command -v cygpath >/dev/null 2>&1; then vol="$(cygpath -w "$work")"; tools="$(cygpath -w "$here")"; fi
MSYS_NO_PATHCONV=1 docker run --rm -v "$vol:/work" -v "$tools:/tools:ro" -w /work \
  -e KEY_ALIAS -e KEYSTORE_PASSWORD -e KEY_PASSWORD -e REPO_URL \
  "$FDROID_IMAGE" sh -c '
    set -e
    if ! command -v fdroid >/dev/null; then
      apt-get update -qq
      # python3-qrcode makes the QR codes: add-repo-qr.png (landing page) and repo/index.png.
      apt-get install -y -qq --no-install-recommends fdroidserver python3-qrcode >/dev/null
    fi
    fdroid --version
    fdroid update --pretty
    # The repo fingerprint: SHA-256 of the certificate that signed entry.jar.
    keytool -printcert -jarfile repo/entry.jar \
      | sed -n "s/^[[:space:]]*SHA256: //p" | head -n1 | tr -d ":" > fingerprint.txt
    test -s fingerprint.txt
    python3 /tools/fdroid_repo.py qr --work /work --repo-url "$REPO_URL" --fingerprint "$(cat fingerprint.txt)"
    rm -f keystore.jks
    # Let the host user read and remove what root wrote here (the keystore copy is gone).
    chmod -R a+rwX /work
  '
fingerprint="$(cat "$work/fingerprint.txt")"
rm -f "$work/keystore.jks"

echo "== verify, landing page, upload plan"
"$py" "$here/fdroid_repo.py" verify --work "$work" --tag "$TAG" --fingerprint "$fingerprint"
"$py" "$here/fdroid_repo.py" page --work "$work" --fingerprint "$fingerprint"
"$py" "$here/fdroid_repo.py" plan --work "$work" --prefix "$PREFIX" \
  --remote "$work/remote-keys.txt" > "$work/plan.tsv"

echo "== upload ($(grep -c '^put' "$work/plan.tsv" || true) files, $(grep -c '^rm' "$work/plan.tsv" || true) deletions)"
while IFS=$'\t' read -r op key rel ctype cache; do
  case "$op" in
    put) echo "put $key"; s_put "$work/$rel" "$key" "$ctype" "$cache" ;;
    rm)  echo "rm  $key"; s_rm "$key" ;;
  esac
done < "$work/plan.tsv"

echo "== done"
echo "repo:        $BASE_URL/$PREFIX/repo"
echo "page:        $BASE_URL/$PREFIX/index.html"
echo "fingerprint: $fingerprint"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### F-Droid repository ($PREFIX)"
    echo "- Repo: $BASE_URL/$PREFIX/repo"
    echo "- Page: $BASE_URL/$PREFIX/index.html"
    echo "- Fingerprint: \`$fingerprint\`"
  } >> "$GITHUB_STEP_SUMMARY"
fi
