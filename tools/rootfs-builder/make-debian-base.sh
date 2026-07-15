#!/usr/bin/env bash
# =============================================================================
# make-debian-base.sh
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
#
# ADFA-4698. Produce a plain Debian rootfs tarball to feed
# `build-iiab-rootfs.sh --base-local`, replacing the frozen proot-distro
# pd-v4.29.0 base currently fetched from switnet.
#
# Two sources (the A/B experiment; this is the probe, not a model decision):
#   --source oci         (default) extract the official debian:<tag> OCI image
#                        via `docker export`. Vanilla: may lack ca-certificates
#                        and locales that the v4 mmdebstrap recipe included --
#                        left as-is on purpose, so the IIAB build exposes the gap.
#   --source mmdebstrap  reproduce Termux's v4 recipe (minbase + ca-certificates
#                        + locales) directly from Debian mirrors.
#
# Output layout matches pd-v4.29.0: a single top-level directory, xz-compressed,
# so build-iiab-rootfs.sh's `tar --strip-components=1 -xJf` consumes it unchanged.
#
# Cross-arch: `--source mmdebstrap` needs QEMU user emulation + binfmt on the
# runner (qemu-user-static + binfmt-support, or docker/setup-qemu-action). The
# OCI path generally does not (docker pull/create/export do not execute guest code).
#
# Usage:
#   ./make-debian-base.sh --arch arm64-v8a  --suite trixie --source oci
#   ./make-debian-base.sh --arch armeabi-v7a --source mmdebstrap --out base-arm.tar.xz
# =============================================================================
set -euo pipefail

SOURCE="oci"
ARCH="arm64-v8a"
SUITE="trixie"
IMAGE=""
OUT=""

log() { printf '\033[1;36m[base]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[base] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source) SOURCE="$2"; shift 2 ;;
    --arch)   ARCH="$2";   shift 2 ;;
    --suite)  SUITE="$2";  shift 2 ;;
    --image)  IMAGE="$2";  shift 2 ;;
    --out)    OUT="$2";    shift 2 ;;
    -h|--help) sed -n '2,33p' "$0"; exit 0 ;;
    *) die "Unknown argument: $1 (use --help)" ;;
  esac
done

# Android ABI -> Debian arch + Docker platform + tarball arch label
case "$ARCH" in
  arm64-v8a|aarch64|arm64) DEB_ARCH="arm64"; DOCKER_PLAT="linux/arm64";  TAG_ARCH="aarch64" ;;
  armeabi-v7a|arm|armhf)   DEB_ARCH="armhf"; DOCKER_PLAT="linux/arm/v7"; TAG_ARCH="arm" ;;
  *) die "Unsupported --arch: $ARCH (arm64-v8a | armeabi-v7a)" ;;
esac

# Suite -> Docker tag (trixie=13, bookworm=12)
case "$SUITE" in
  trixie)   DTAG="13" ;;
  bookworm) DTAG="12" ;;
  *)        DTAG="$SUITE" ;;
esac
IMAGE="${IMAGE:-debian:$DTAG}"
OUT="${OUT:-debian-${SUITE}-${TAG_ARCH}-base.tar.xz}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
TOP="debian-${SUITE}"                 # single wrapper dir -> matches pd-v4.29.0 layout
ROOT="$WORK/$TOP"
mkdir -p "$ROOT"

case "$SOURCE" in
  oci)
    command -v docker >/dev/null || die "docker not found (needed for --source oci)"
    log "Pulling ${IMAGE} for ${DOCKER_PLAT} ..."
    docker pull --platform "$DOCKER_PLAT" "$IMAGE" >&2
    cid="$(docker create --platform "$DOCKER_PLAT" "$IMAGE" /bin/true)"
    log "Exporting container filesystem into ${TOP}/ ..."
    docker export "$cid" | tar -C "$ROOT" -xf -
    docker rm -f "$cid" >/dev/null
    ;;
  mmdebstrap)
    command -v mmdebstrap >/dev/null || die "mmdebstrap not found (needed for --source mmdebstrap)"
    log "Bootstrapping ${SUITE} (${DEB_ARCH}) with mmdebstrap ..."
    mmdebstrap --architectures="$DEB_ARCH" --variant=minbase \
      --components="main,contrib" --include="ca-certificates,locales" \
      "$SUITE" "$ROOT" http://deb.debian.org/debian
    ;;
  *) die "Unknown --source: $SOURCE (oci | mmdebstrap)" ;;
esac

[[ -e "$ROOT/bin/bash" || -L "$ROOT/bin/bash" ]] || die "Base has no /bin/bash -- build/extract failed?"

log "Packaging ${OUT} (top-level dir: ${TOP}/) ..."
rm -f "$OUT"
tar -C "$WORK" -cJf "$OUT" "$TOP"
log "Done: ${OUT}  ($(du -h "$OUT" | cut -f1))"
log "Feed it to: build-iiab-rootfs.sh --arch ${ARCH} --base-local ${OUT}"
