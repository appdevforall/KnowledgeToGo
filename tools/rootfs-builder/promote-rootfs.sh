#!/usr/bin/env bash
# =============================================================================
# promote-rootfs.sh : K2GO-511. Promote a tested rootfs build to production.
# Author    : AppDevForAll
# Copyright : Copyright (c) 2026 AppDevForAll
#
# A pointer (latest_<tier>_<arch>.meta4/.installed) is a byte copy of a dated build's
# sidecar, so promotion is a copy, never a rebuild. The default source is the
# dev channel (dev_latest_*): the exact build that was tested on devices. SOURCE can also
# name a dated tarball (k2go_<date>_<tier>_<sha>_<arch>.tar.gz) to roll production back.
#
# Every pair is validated BEFORE any copy, so a bad pair stops the run with production
# untouched. Reads go through the public base (no credentials); only the upload uses aws.
#
# Env:
#   TIERS        "basic standard full matomo" (default) or a subset
#   ARCHES       "arm64-v8a armeabi-v7a" (default) or a subset
#   SOURCE       dev_latest (default) | k2go_<date>_<tier>_<sha>_<arch>.tar.gz
#   DRY_RUN      1 (default) = plan and checks only; 0 = copy
#   PUBLIC_BASE  public URL of the bucket
#   BUCKET_NAME, ENDPOINT, PFX : needed only when DRY_RUN=0
# =============================================================================
set -euo pipefail

TIERS="${TIERS:-basic standard full matomo}"
ARCHES="${ARCHES:-arm64-v8a armeabi-v7a}"
SOURCE="${SOURCE:-dev_latest}"
DRY_RUN="${DRY_RUN:-1}"
PUBLIC_BASE="${PUBLIC_BASE:-https://pub-d64c885cef6c42db8c7925144d73d0ee.r2.dev}"
PFX="${PFX:-}"
TARBALL_RE='k2go_[0-9.]+_[a-z]+_[0-9a-f]+_[a-z0-9-]+\.tar\.gz'

fail() { echo "!! $*" >&2; exit 1; }
http_ok() { curl -sfI -m 30 "$PUBLIC_BASE/${PFX}$1" >/dev/null; }
# Always returns 0 (empty output = not found), so a caller under set -e reports the failure itself.
tarball_of() { curl -sf -m 30 "$PUBLIC_BASE/${PFX}$1" | grep -oE "$TARBALL_RE" | head -1 || true; }

# A dated SOURCE names exactly one (tier, arch).
if [ "$SOURCE" != dev_latest ]; then
    [[ "$SOURCE" =~ ^k2go_[0-9.]+_([a-z]+)_[0-9a-f]+_([a-z0-9-]+)\.tar\.gz$ ]] \
        || fail "SOURCE must be dev_latest or a dated tarball name: $SOURCE"
    TIERS="${BASH_REMATCH[1]}"; ARCHES="${BASH_REMATCH[2]}"
fi

# 1) Validate every pair; build the copy plan.
plan=()
for arch in $ARCHES; do
    for tier in $TIERS; do
        if [ "$SOURCE" = dev_latest ]; then
            src="dev_latest_${tier}_${arch}"; log_src="dev_build-${tier}-${arch}.log"
        else
            src="$SOURCE"; log_src=""
        fi
        dst="latest_${tier}_${arch}"
        want="$(tarball_of "${src}.meta4")"
        [ -n "$want" ] || fail "$tier/$arch: ${src}.meta4 missing or names no tarball"
        [[ "$want" == *"_${tier}_"*"_${arch}.tar.gz" ]] || fail "$tier/$arch: ${src}.meta4 names $want"
        http_ok "$want" || fail "$tier/$arch: tarball $want is not on R2"
        http_ok "${src}.installed" || fail "$tier/$arch: ${src}.installed missing"
        now="$(tarball_of "${dst}.meta4")"
        echo ">> $tier/$arch: ${now:-<none>} -> $want"
        plan+=("${src}.installed|${dst}.installed" "${src}.meta4|${dst}.meta4")
        if [ -n "$log_src" ]; then
            if http_ok "$log_src"; then plan+=("${log_src}|build-${tier}-${arch}.log")
            else echo "   warn: $log_src missing (bake predates it); build-${tier}-${arch}.log stays as is"; fi
        fi
    done
done

if [ "$DRY_RUN" != 0 ]; then
    echo ">> DRY RUN: all pairs valid; nothing copied. Plan:"
    printf '   %s\n' "${plan[@]/|/ -> }"
    exit 0
fi

# 2) Copy. Per pair the .installed goes before the .meta4, the pointer the app reads first.
#    Download through the public base and upload with s3cp: the same PutObject path the bake
#    publishes these pointers with. A server-side s3-to-s3 copy failed on R2 (HeadObject 400).
#    The run is idempotent (it re-reads the source), so a failed copy is fixed by running it again.
: "${BUCKET_NAME:?}" "${ENDPOINT:?}"
source "$(dirname "$0")/r2-cp.sh"   # s3cp: aws s3 cp with retries
tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
for p in "${plan[@]}"; do
    from="${p%%|*}"; to="${p##*|}"
    echo ">> copy $from -> $to"
    { curl -sf -m 60 -o "$tmp" "$PUBLIC_BASE/${PFX}${from}" && s3cp "$tmp" "s3://${BUCKET_NAME}/${PFX}${to}"; } \
        || fail "copy $from -> $to failed; production can be mixed now. Run the promotion again."
done

# 3) Post-check: each latest_* now names the tarball its source named.
for arch in $ARCHES; do
    for tier in $TIERS; do
        if [ "$SOURCE" = dev_latest ]; then src="dev_latest_${tier}_${arch}"; else src="$SOURCE"; fi
        want="$(tarball_of "${src}.meta4")"; got="$(tarball_of "latest_${tier}_${arch}.meta4")"
        [ "$got" = "$want" ] || fail "post-check $tier/$arch: latest names ${got:-<none>}, expected $want"
        echo ">> ok $tier/$arch -> $got"
    done
done
