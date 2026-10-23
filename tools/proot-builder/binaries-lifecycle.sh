#!/usr/bin/env bash
# =============================================================================
# binaries-lifecycle.sh: archive the native-binaries releases to R2 and prune old
# GitHub releases without losing a pinned or unarchived build (K2GO-506).
# Author: AppDevForAll
#
#   sync   Archive every binaries-* GitHub release that R2 does not have yet, at
#          binaries/<tag>/termux-binaries-latest.zip (+ .sha256). Skips a
#          release that is already archived.
#   prune  Delete the binaries-* GitHub releases older than the newest $KEEP,
#          except a tag that binary_version.txt pins on any branch or v* tag,
#          and except a release that is not archived in R2 yet.
#
# Env: GITHUB_REPOSITORY, BUCKET_NAME, R2_ACCOUNT_ID, AWS_* credentials,
#      KEEP (default 6), DRY_RUN=1 (report only). Needs gh, aws and a full git
#      clone (all origin branches and the v* tags).
# Self-test: test-binaries-lifecycle.sh.
# See controller/docs/UPDATING_NATIVE_BINARIES.md and controller/docs/R2_LAYOUT.md.
# =============================================================================
set -euo pipefail
shopt -s inherit_errexit

: "${GITHUB_REPOSITORY:?}" "${BUCKET_NAME:?}" "${R2_ACCOUNT_ID:?}"
ASSET="termux-binaries-latest.zip"
PREFIX="binaries"
KEEP="${KEEP:-6}"
PIN_FILE="controller/binary_version.txt"
DRY_RUN="${DRY_RUN:-0}"
[[ "$KEEP" =~ ^[0-9]+$ ]] || { echo "::error::KEEP must be a number, got '$KEEP'"; exit 2; }

s3() { aws --endpoint-url "https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com" "$@"; }

# The .sha256 is uploaded after the zip, so its presence marks a complete archive.
archived() {
  s3 s3api head-object --bucket "$BUCKET_NAME" --key "$PREFIX/$1/$ASSET.sha256" >/dev/null 2>&1
}

# binaries-* release tags, newest first.
release_tags() {
  local all
  all="$(gh release list --repo "$GITHUB_REPOSITORY" --limit 200 \
    --json tagName,createdAt --jq 'sort_by(.createdAt) | reverse | .[].tagName')"
  printf '%s\n' "$all" | grep '^binaries-' || true
}

cmd_sync() {
  local tags tag dir
  tags="$(release_tags)"
  for tag in $tags; do
    if archived "$tag"; then echo "archived: $tag"; continue; fi
    if [ "$DRY_RUN" = "1" ]; then echo "DRY-RUN: would archive $tag"; continue; fi
    dir="$(mktemp -d)"
    gh release download "$tag" --repo "$GITHUB_REPOSITORY" --pattern "$ASSET" --dir "$dir"
    (cd "$dir" && sha256sum "$ASSET" > "$ASSET.sha256")
    s3 s3 cp "$dir/$ASSET" "s3://$BUCKET_NAME/$PREFIX/$tag/$ASSET" --content-type application/zip
    s3 s3 cp "$dir/$ASSET.sha256" "s3://$BUCKET_NAME/$PREFIX/$tag/$ASSET.sha256" --content-type text/plain
    rm -rf "$dir"
    echo "archived now: $tag"
  done
}

cmd_prune() {
  local pins main_pin refs ref tags tag n=0
  # Fail closed: when the pin on main is unknown, delete nothing.
  main_pin="$(git show "origin/main:$PIN_FILE" | tr -d '[:space:]')"
  [ -n "$main_pin" ] || { echo "::error::empty pin in origin/main:$PIN_FILE, nothing deleted"; exit 1; }
  # Every branch (development, open PRs) and every app release tag keeps the tag it pins.
  refs="$(git for-each-ref --format='%(refname)' refs/remotes/origin 'refs/tags/v*')"
  pins="$main_pin"
  for ref in $refs; do
    pins="$pins $(git show "$ref:$PIN_FILE" 2>/dev/null | tr -d '[:space:]' || true)"
  done
  echo "pinned: $(printf '%s\n' $pins | sort -u | tr '\n' ' ')"

  tags="$(release_tags)"
  for tag in $tags; do
    n=$((n + 1))
    if [ "$n" -le "$KEEP" ]; then echo "keep (newest $KEEP): $tag"; continue; fi
    case " $pins " in *" $tag "*) echo "keep (pinned): $tag"; continue ;; esac
    if ! archived "$tag"; then echo "::warning::keep (not archived in R2): $tag"; continue; fi
    if [ "$DRY_RUN" = "1" ]; then echo "DRY-RUN: would delete $tag"; continue; fi
    echo "delete: $tag"
    gh release delete "$tag" --repo "$GITHUB_REPOSITORY" --cleanup-tag --yes
  done
}

case "${1:-}" in
  sync)  cmd_sync ;;
  prune) cmd_prune ;;
  *) echo "usage: $0 sync|prune" >&2; exit 2 ;;
esac
