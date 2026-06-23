#!/usr/bin/env bash
# =============================================================================
# build-iiab-rootfs.sh
# Copyright (c) 2026 AppDevForAll
#
# Build an IIAB-oA rootfs by replicating the APK's PROOT ENVIRONMENT, but on
# native ARM64 Linux (e.g. SBC / GitHub Actions)
#
# Key idea: what makes the IIAB install come out clean is proot
# The app sets `is_proot: True` and runs the Ansible installer under
# proot (fake root via -0, emulated syscalls, PDSM instead of systemd). Docker
# breaks because it offers REAL root / REAL systemd, contradicting
# `is_proot: True`. Here we reproduce the consistent pair (is_proot:True +
# actually under proot) using the SAME libproot.so and flags as PRootEngine.java.
#
# proot source: by default downloads the native-binaries release of
# appdevforall/KnowledgeToGo (the very zip the APK uses via syncNativeArtifacts).
#
# Artifact identifier: the SHORT COMMIT of iiab/iiab that the installer actually
# baked into this rootfs (read from the clone at /opt/iiab/iiab AFTER install =
# total fidelity). If the clone can't be read, falls back to the remote default
# branch (git ls-remote, then the REST API). --iiab-commit overrides everything.
#
# Output:
#   - iiab-oa_<date>_<tier>_<iiab-sha>_<arch>.tar.gz   (top-level = installed-rootfs/iiab/)
#   - iiab-oa_<...>.tar.gz.meta4 / .torrent            (per-artifact, mkmetalink)
#   - latest_<tier>_<arch>.meta4   (STABLE pointer the APK requests; a copy of the
#     per-artifact .meta4. Inside it, <url> mirrors point at the full-named tarball,
#     so this stable name always resolves to the newest build.)
#   - build-<tier>-<arch>.log      (full installer log)
#
# Accepted values:
#   --tier   basic | standard | full      (numeric aliases: 1 | 2 | 3; "medium" = standard)
#   --arch   arm64-v8a | armeabi-v7a
#   --all-tier = build basic + standard + full   ·   --all-arch = build both arches
#
# Usage:
#   sudo ./build-iiab-rootfs.sh --tier standard --arch arm64-v8a
#
#   # Build EVERYTHING in one run (both arches x all three tiers = 6 builds):
#   sudo ./build-iiab-rootfs.sh --all-arch --all-tier
#   # PRISTINE rebuild: delete ALL prior state (dist/ + caches) first. The 2nd flag
#   # confirms non-interactively; WITHOUT it you are prompted (default NO):
#   sudo ./build-iiab-rootfs.sh --all-arch --all-tier --scratch-build --confirm-scratch-yes
#
#   # Or one axis: all tiers for arm64 only / both arches for standard only:
#   sudo ./build-iiab-rootfs.sh --all-tier --arch arm64-v8a
#   sudo ./build-iiab-rootfs.sh --all-arch --tier standard
#
#   # By default it downloads libproot.so from the release. Overrides:
#   sudo ./build-iiab-rootfs.sh --tier 3 --arch arm64-v8a --binaries-tag binaries-2026-06-10_17-17
#   sudo ./build-iiab-rootfs.sh --tier 3 --arch arm64-v8a --proot-bin /path/libproot.so
#   sudo ./build-iiab-rootfs.sh --tier 3 --arch arm64-v8a --system-proot   # (not recommended)
#
#   # Identifier override (skip reading the rootfs / remote):
#   sudo ./build-iiab-rootfs.sh --tier 3 --arch arm64-v8a --iiab-commit ab88e5d
#
#   # Mirrors: PRIMARY is priority 10; each mirror gets 11,12,13,... Edit MIRRORS[]
#   # in the Config below, or append at runtime (repeatable). --reset-mirrors clears
#   # the built-in list first (e.g. to publish to Cloudflare only):
#   sudo ./build-iiab-rootfs.sh --tier 2 --arch arm64-v8a \
#        --publish-url https://cdn.example.org/iiab/rootfs \
#        --reset-mirrors --mirror-url https://m1/iiab/rootfs --mirror-url https://m2/iiab/rootfs
#
#   # mkmetalink is Go-based and needs Go >= 1.25.1. If Go is missing/too old, the
#   # script downloads an official Go toolchain (pinned + checksum-verified) into
#   # WORKDIR -> works unattended on GitHub Actions / SBC. To override:
#   sudo ./build-iiab-rootfs.sh --tier 2 --arch arm64-v8a --go-version go1.25.4
#   sudo ./build-iiab-rootfs.sh --tier 2 --arch arm64-v8a --mkmetalink-bin /usr/local/bin/mkmetalink
#   # (In CI you can instead use actions/setup-go to provide a recent Go, or cache
#   #  the mkmetalink binary and pass --mkmetalink-bin.)
#
# This script BUILDS ONLY — it never publishes/uploads. All outputs stay in OUTDIR
# (dist/) and are recorded in dist/PUBLISH_QUEUE.tsv as READY/HOLD, for a later
# CD / GitHub Actions workflow to push to Cloudflare / a mirror / a GitHub release.
#
# Run as root on the host (proot and the bind mounts want it).
# =============================================================================
set -euo pipefail
SELF="$(readlink -f "$0" 2>/dev/null || echo "$0")"   # absolute path to this script (for --all-* self-dispatch)

# ----------------------------- Colors / log ----------------------------------
RED="\033[31m"; YEL="\033[33m"; GRN="\033[32m"; BLU="\033[34m"; RST="\033[0m"; BOLD="\033[1m"
ok()   { printf "${GRN}[build]${RST} %s\n" "$*"; }
log()  { printf "${BLU}[build]${RST} %s\n" "$*"; }
warn() { printf "${YEL}[build] WARN:${RST} %s\n" "$*" >&2; }
die()  { printf "${RED}[build] ERROR:${RST} %s\n" "$*" >&2; exit 1; }

# ----------------------------- Timing -----------------------------------------
BUILD_START_EPOCH="$(date +%s)"
BUILD_START_HUMAN="$(date '+%Y-%m-%d %H:%M:%S %Z')"
fmt_dur() { local s="$1"; printf '%dh %02dm %02ds' "$((s/3600))" "$(((s%3600)/60))" "$((s%60))"; }

# ----------------------------- Config -----------------------------------------
PD_VERSION="4.29.0"                                  # proot-distro version of the base (same as the APK)
BASE_HOST="https://iiab.switnet.org/android/rootfs"  # where the Debian base + artifacts live currently

# Download sources written into the .meta4, in PRIORITY order. PUBLISH_URL is the
# primary (priority 10); each MIRRORS[] entry then gets 11, 12, 13, ... (lower =
# preferred). Scales to any number of mirrors and is future-proof: today community
# hosts (switnet); tomorrow drop in Cloudflare/enterprise URLs here — or add them at
# runtime with repeated --mirror-url (and --reset-mirrors to start the list empty).
PUBLISH_URL="https://iiab.switnet.org/android/rootfs"          # PRIMARY (priority 10)
MIRRORS=(
  "https://mirror.switnet.org/iiab/android/rootfs"             # mirror 1 -> priority 11
  # "https://cdn.example.org/iiab/android/rootfs"              # mirror 2 -> priority 12
  # ...add more; priority auto-increments in array order
)

# mkmetalink is a Go tool requiring Go >= GO_MIN. We bootstrap an official Go
# toolchain reproducibly when needed (apt's Go is too old on Ubuntu 24.04).
MKMETALINK_BIN=""                  # use a prebuilt mkmetalink binary (skips Go); --mkmetalink-bin
MKMETALINK_VERSION="latest"        # module version to 'go install'; --mkmetalink-version
GO_VERSION=""                      # "" = latest stable from go.dev; or pin e.g. go1.25.4; --go-version
GO_MIN="1.25.1"                    # mkmetalink go.mod requires Go >= this
KERNEL_STR="6.17.0-PRoot-IIAB"                       # same -k as PRootEngine.java
REPO="appdevforall/KnowledgeToGo"
INSTALLER_URL="https://raw.githubusercontent.com/${REPO}/main/iiab-android"
# Release tag holding the native binaries (the same zip the APK uses). Empty =
# auto: read controller/binary_version.txt from main.
BINARIES_TAG=""
VERSION_FILE_URL="https://raw.githubusercontent.com/${REPO}/main/controller/binary_version.txt"

# Upstream Internet-in-a-Box repo (used only as a FALLBACK id source if the rootfs
# clone can't be read). The id itself is normally read from the installed clone.
IIAB_REPO_URL="https://github.com/iiab/iiab.git"
IIAB_REF="HEAD"           # default-branch HEAD for the remote fallback; --iiab-ref to change
IIAB_COMMIT=""            # manual override (full or short) via --iiab-commit

TIER="standard"           # basic|standard|full  or  1|2|3
ARCH="arm64-v8a"          # arm64-v8a | armeabi-v7a
PROOT_BIN=""              # empty = download libproot.so from the release (default); or a local path
USE_SYSTEM_PROOT=0        # 1 = force the system proot (NOT recommended: old / segfaults)
WORKDIR="$(pwd)/iiab-build"
OUTDIR="$(pwd)/dist"
KEEP_ROOTFS=0             # 1 = do not delete the extracted tree when done
ACCEPT_EMULATE=0           # 1 = auto-accept the QEMU fallback (no prompt); --accept-force-emulate-qemu
ALL_ARCH=0                # 1 = build BOTH arches (arm64-v8a + armeabi-v7a) in a loop
ALL_TIER=0                # 1 = build ALL tiers (basic + standard + full) in a loop
SCRATCH_BUILD=0           # 1 = wipe ALL prior state (OUTDIR + WORKDIR) before building
CONFIRM_SCRATCH=0         # 1 = skip the scratch confirmation prompt (--confirm-scratch-yes)

# ----------------------------- Args -------------------------------------------
ORIG_ARGS=("$@")          # preserved verbatim for the --all-* self-dispatch loop
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tier)         TIER="$2"; shift 2 ;;
    --arch)         ARCH="$2"; shift 2 ;;
    --proot-bin)    PROOT_BIN="$2"; shift 2 ;;
    --binaries-tag) BINARIES_TAG="$2"; shift 2 ;;
    --system-proot) USE_SYSTEM_PROOT=1; shift ;;
    --workdir)      WORKDIR="$2"; shift 2 ;;
    --outdir)       OUTDIR="$2"; shift 2 ;;
    --publish-url)  PUBLISH_URL="$2"; shift 2 ;;
    --mirror-url)    MIRRORS+=("$2"); shift 2 ;;   # append a mirror (repeatable): prio 11,12,...
    --reset-mirrors) MIRRORS=(); shift ;;          # clear built-in mirrors (use BEFORE --mirror-url)
    --mkmetalink-bin)     MKMETALINK_BIN="$2"; shift 2 ;;
    --mkmetalink-version) MKMETALINK_VERSION="$2"; shift 2 ;;
    --go-version)         GO_VERSION="$2"; shift 2 ;;
    --iiab-ref)     IIAB_REF="$2"; shift 2 ;;
    --iiab-commit)  IIAB_COMMIT="$2"; shift 2 ;;
    --all-arch)     ALL_ARCH=1; shift ;;
    --all-tier)     ALL_TIER=1; shift ;;
    --scratch-build)       SCRATCH_BUILD=1; shift ;;
    --confirm-scratch-yes) CONFIRM_SCRATCH=1; shift ;;
    --keep)         KEEP_ROOTFS=1; shift ;;
    --accept-force-emulate-qemu) ACCEPT_EMULATE=1; shift ;;
    -h|--help)
      grep -E '^#( |$)' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "Unknown argument: $1 (use --help)" ;;
  esac
done

# Normalize tier name <-> number
case "$TIER" in
  1|basic)            TIER_NUM=1; TIER_NAME="basic" ;;
  2|standard|medium)  TIER_NUM=2; TIER_NAME="standard" ;;
  3|full)             TIER_NUM=3; TIER_NAME="full" ;;
  *) die "Invalid tier: $TIER (use basic|standard|full or 1|2|3)" ;;
esac

# Map Android arch -> proot-distro Debian arch
case "$ARCH" in
  arm64-v8a)   DEB_ARCH="aarch64" ;;
  armeabi-v7a) DEB_ARCH="arm" ;;
  *) die "Invalid arch: $ARCH (use arm64-v8a|armeabi-v7a)" ;;
esac

# -------- Scratch build (--scratch-build): pristine start, delete ALL prior ----
# Wipes OUTDIR (artifacts + .meta4/.torrent + PUBLISH_QUEUE.tsv) AND WORKDIR (Debian
# base, Go toolchain, native binaries, extracted rootfs). Destructive on purpose, so
# it is double-gated: without --confirm-scratch-yes you must type 'yes' at a prompt
# whose DEFAULT is NO (a stray Enter aborts; no TTY also aborts -> CI must pass the
# confirm flag). In --all mode this runs ONCE here in the parent, before dispatch.
if [[ "$SCRATCH_BUILD" -eq 1 ]]; then
  if [[ "$CONFIRM_SCRATCH" -ne 1 ]]; then
    printf "${YEL}${BOLD}SCRATCH BUILD — this DELETES all previous state:${RST}\n" >&2
    printf "  - %s  (built artifacts, .meta4/.torrent, PUBLISH_QUEUE.tsv)\n" "$OUTDIR" >&2
    printf "  - %s  (Debian base, Go toolchain, native binaries, extracted rootfs)\n" "$WORKDIR" >&2
    printf "  Everything above is re-downloaded/rebuilt from zero (can be several GB).\n" >&2
    printf "${YEL}Continue? Type 'yes' to proceed [default: NO]: ${RST}" >&2
    read -r _ans || _ans=""
    case "${_ans:-}" in
      y|Y|yes|YES|Yes) ;;
      *) die "Scratch build NOT confirmed — aborting; nothing was deleted." ;;
    esac
  fi
  warn "Scratch build confirmed: removing ${OUTDIR} and ${WORKDIR} ..."
  rm -rf "$OUTDIR" "$WORKDIR"
fi

# -------- Multi-build dispatch (--all-arch / --all-tier) ----------------------
# When either flag is set, re-invoke THIS script once per (arch, tier) combo,
# passing through every other flag untouched. Keeps the single-build body below
# unchanged. A failed combo is logged but does not stop the rest; overall exit is
# non-zero if any combo failed.
if [[ "$ALL_ARCH" -eq 1 || "$ALL_TIER" -eq 1 ]]; then
  build_archs=("$ARCH");      [[ "$ALL_ARCH" -eq 1 ]] && build_archs=(arm64-v8a armeabi-v7a)
  build_tiers=("$TIER_NAME"); [[ "$ALL_TIER" -eq 1 ]] && build_tiers=(basic standard full)
  # Rebuild the pass-through arg list: drop --all-*, and drop --arch/--tier (+value).
  PASS=(); _skip=0
  for _a in "${ORIG_ARGS[@]}"; do
    if [[ "$_skip" -eq 1 ]]; then _skip=0; continue; fi
    case "$_a" in
      --all-arch|--all-tier|--scratch-build|--confirm-scratch-yes) ;;  # drop (parent already scratched)
      --arch|--tier)         _skip=1 ;;  # drop the flag and its following value
      *)                     PASS+=("$_a") ;;
    esac
  done
  MULTI_RC=0
  for _arch in "${build_archs[@]}"; do
    for _tier in "${build_tiers[@]}"; do
      log "==================== BUILD  tier=${_tier}  arch=${_arch} ===================="
      if bash "$SELF" "${PASS[@]}" --arch "$_arch" --tier "$_tier"; then
        ok "build OK: ${_tier}/${_arch}"
      else
        warn "build FAILED: ${_tier}/${_arch} (continuing with the rest)"; MULTI_RC=1
      fi
    done
  done
  log "Multi-build finished in $(fmt_dur $(( $(date +%s) - BUILD_START_EPOCH ))). Overall: $([[ $MULTI_RC -eq 0 ]] && echo 'ALL CLEAN' || echo 'SOME FAILURES - check logs')"
  exit "$MULTI_RC"
fi

BASE_TARBALL="debian-trixie-${DEB_ARCH}-pd-v${PD_VERSION}.tar.xz"
BASE_URL="${BASE_HOST}/proot-distro-v${PD_VERSION}/${BASE_TARBALL}"
# The app runs/detects the rootfs at filesDir/rootfs/installed-rootfs/iiab, and its
# own backups tar the "installed-rootfs/" dir. So the ARTIFACT MUST contain
# installed-rootfs/iiab/... (NOT a bare iiab/), or restore/fast-install land it in
# the wrong place and the app reports "No component identified". Build into that layout.
INSTALL_WRAP="${WORKDIR}/installed-rootfs"   # archive top-level dir (matches the app)
ROOTFS="${INSTALL_WRAP}/iiab"                # proot root = installed-rootfs/iiab
PROOT_TMP="${WORKDIR}/proot_tmp"

# ----------------------------- iiab/iiab identifier ---------------------------
# Primary: read the commit the installer actually checked out into the rootfs
# (total fidelity — it is literally the IIAB version baked in). git runs on the
# host against the rootfs's .git; safe.directory='*' avoids dubious-ownership.
iiab_commit_from_rootfs() {
  local d p u g
  for d in "$ROOTFS/opt/iiab/iiab" "$ROOTFS/opt/iiab"; do
    if [[ -d "$d/.git" ]]; then
      git -c safe.directory='*' -C "$d" rev-parse --short=7 HEAD 2>/dev/null && return 0
    fi
  done
  # Robust: find any clone under /opt whose origin remote is iiab/iiab.
  while IFS= read -r g; do
    [[ -z "$g" ]] && continue
    p="${g%/.git}"
    u="$(git -c safe.directory='*' -C "$p" remote get-url origin 2>/dev/null || true)"
    case "$u" in
      *iiab/iiab*) git -c safe.directory='*' -C "$p" rev-parse --short=7 HEAD 2>/dev/null && return 0 ;;
    esac
  done < <(find "$ROOTFS/opt" -maxdepth 4 -type d -name .git 2>/dev/null)
  return 1
}

# Fallback: ask the remote for the default-branch commit (git protocol over
# HTTPS: no API, no rate limit, no auth). REST API is a secondary fallback.
resolve_iiab_commit_remote() {
  local full=""
  full="$(git ls-remote "$IIAB_REPO_URL" "$IIAB_REF" 2>/dev/null | awk 'NR==1{print $1}' || true)"
  if [[ -z "$full" ]]; then
    full="$(curl -fsSL ${GITHUB_TOKEN:+-H "Authorization: Bearer ${GITHUB_TOKEN}"} \
              'https://api.github.com/repos/iiab/iiab/commits?per_page=1' 2>/dev/null \
            | grep -oE '"sha"[[:space:]]*:[[:space:]]*"[0-9a-f]{40}"' | head -1 \
            | grep -oE '[0-9a-f]{40}' || true)"
  fi
  [[ -n "$full" ]] || return 1
  printf '%s' "${full:0:7}"
}

# ----------------------------- Go / mkmetalink toolchain ----------------------
# ver_ge A B  -> success (0) if version A >= version B (dotted, e.g. 1.25.1)
ver_ge() { [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -1)" = "$2" ]; }
go_version_num() { "$1" version 2>/dev/null | grep -oE 'go[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1 | sed 's/^go//'; }

# Sets GO_BIN to a Go >= GO_MIN. Prefers an existing new-enough Go; otherwise
# downloads the official toolchain from go.dev (pinned + checksum-verified) into
# WORKDIR (cached, so the --all-* loop reuses it). Reproducible & unattended.
ensure_go() {
  local cand v gv m garch tb url dst exp got
  cand="$(command -v go || true)"
  if [[ -n "$cand" ]]; then
    v="$(go_version_num "$cand")"
    if [[ -n "$v" ]] && ver_ge "$v" "$GO_MIN"; then GO_BIN="$cand"; log "Using existing Go ${v}"; return 0; fi
    warn "Existing Go ${v:-unknown} < ${GO_MIN}; bootstrapping a newer toolchain."
  fi
  gv="$GO_VERSION"
  [[ -z "$gv" ]] && gv="$(curl -fsSL 'https://go.dev/VERSION?m=text' 2>/dev/null | head -1 | tr -d '[:space:]' || true)"
  [[ "$gv" == go* ]] || gv="go${gv}"
  [[ -n "$gv" && "$gv" != "go" ]] || die "Could not determine a Go version to download (set --go-version)."
  m="$(uname -m)"
  case "$m" in
    aarch64|arm64) garch="arm64" ;;
    x86_64|amd64)  garch="amd64" ;;
    armv7l|armv6l) garch="armv6l" ;;
    *) die "Unsupported host arch for Go bootstrap: $m" ;;
  esac
  dst="${WORKDIR}/go-toolchain/${gv}"
  if [[ -x "${dst}/go/bin/go" ]]; then GO_BIN="${dst}/go/bin/go"; log "Using cached Go ${gv}"; return 0; fi
  tb="${gv}.linux-${garch}.tar.gz"
  url="https://go.dev/dl/${tb}"
  log "Downloading Go toolchain ${gv} (${garch}) from go.dev ..."
  mkdir -p "$dst"
  curl -fL --retry 5 --retry-connrefused -o "${WORKDIR}/${tb}" "$url" || die "Could not download ${url}"
  # Expected sha256 from go.dev's authoritative JSON manifest, matched by filename
  # (the per-file .sha256 endpoint is unreliable). Best-effort: only enforce when
  # we actually obtained a clean 64-hex; otherwise warn and rely on HTTPS.
  exp="$(curl -fsSL 'https://go.dev/dl/?mode=json&include=all' 2>/dev/null \
         | tr -d '\n ' \
         | grep -oE '"filename":"'"${tb}"'"[^}]*"sha256":"[0-9a-f]{64}"' \
         | grep -oE '[0-9a-f]{64}' | head -1 || true)"
  if [[ "$exp" =~ ^[0-9a-f]{64}$ ]]; then
    got="$(sha256sum "${WORKDIR}/${tb}" | awk '{print $1}')"
    [[ "$exp" == "$got" ]] || die "Go tarball sha256 mismatch for ${tb} (expected ${exp}, got ${got})."
    ok "Go toolchain sha256 OK"
  else
    warn "Could not obtain a trustworthy sha256 for ${tb} from go.dev; relying on HTTPS."
  fi
  tar -C "$dst" -xzf "${WORKDIR}/${tb}"      # creates ${dst}/go
  GO_BIN="${dst}/go/bin/go"
  [[ -x "$GO_BIN" ]] || die "Go bootstrap failed (no ${GO_BIN})."
  log "Bootstrapped Go $(go_version_num "$GO_BIN")"
}

# Sets MKMETALINK to a usable binary. Priority: --mkmetalink-bin > PATH > go install.
ensure_mkmetalink() {
  if [[ -n "$MKMETALINK_BIN" ]]; then
    [[ -x "$MKMETALINK_BIN" ]] || die "--mkmetalink-bin is not executable: $MKMETALINK_BIN"
    MKMETALINK="$MKMETALINK_BIN"; return 0
  fi
  if command -v mkmetalink >/dev/null 2>&1; then MKMETALINK="$(command -v mkmetalink)"; return 0; fi
  ensure_go
  local gobin="${WORKDIR}/gobin"
  mkdir -p "$gobin"
  log "Installing mkmetalink@${MKMETALINK_VERSION} with $("$GO_BIN" version | awk '{print $3}') ..."
  GOBIN="$gobin" GOPATH="${WORKDIR}/gopath" GOCACHE="${WORKDIR}/gocache" \
    "$GO_BIN" install "github.com/chapmanjacobd/mkmetalink@${MKMETALINK_VERSION}" \
    || die "go install mkmetalink@${MKMETALINK_VERSION} failed."
  MKMETALINK="${gobin}/mkmetalink"
  [[ -x "$MKMETALINK" ]] || die "mkmetalink not produced at ${MKMETALINK}."
}

# ----------------------------- Preflight --------------------------------------
[[ "$(id -u)" == "0" ]] || die "Run as root: sudo $0 ..."

HOST_ARCH="$(uname -m)"
log "Host arch: ${HOST_ARCH} | Target: ${ARCH} (${DEB_ARCH})"

# 32-bit guest on a 64-bit host: glibc's 32-bit pthread_mutex_t aborts when the
# process PID > 65535 ("only supports pids <= 65535"). The PID counter climbs with
# uptime, so this fails intermittently. Cap pid_max so new PIDs stay in range.
# System-wide + resets on reboot; we restore the previous value on exit.
if [[ "$DEB_ARCH" == "arm" && "$HOST_ARCH" == "aarch64" ]]; then
  if [[ -w /proc/sys/kernel/pid_max ]]; then
    _PID_MAX_OLD="$(cat /proc/sys/kernel/pid_max)"
    if [[ "${_PID_MAX_OLD:-0}" -gt 65536 ]]; then
      warn "32-bit guest on 64-bit host: lowering kernel.pid_max ${_PID_MAX_OLD} -> 65536 (glibc 32-bit needs PID<=65535; resets on reboot)."
      echo 65536 > /proc/sys/kernel/pid_max \
        && trap 'echo "${_PID_MAX_OLD}" > /proc/sys/kernel/pid_max 2>/dev/null || true' EXIT \
        || warn "Could not lower pid_max; the 32-bit build may abort (pthread_mutex PID error)."
    fi
  else
    warn "Cannot write /proc/sys/kernel/pid_max (need root / host caps); 32-bit build may abort with pid>65535."
  fi
fi
# --------- Native first, probe, fall back to QEMU only if the CPU refuses ------
# Do NOT assume a 64-bit host can't run 32-bit guests (many can). We try NATIVE
# first and probe the actual target binary; only if the CPU rejects it (ENOEXEC)
# do we emulate the guest with QEMU user-mode via 'proot -q'. The fallback is
# confirmed (default NO; no TTY aborts) unless --accept-force-emulate-qemu is given.
# proot runs on the HOST, so PROOT_ABI picks the host's proot for the emulated case.
case "$HOST_ARCH" in
  aarch64)        PROOT_ABI="arm64-v8a" ;;
  armv7l|armv6l)  PROOT_ABI="armeabi-v7a" ;;
  x86_64|amd64)   PROOT_ABI="x86_64" ;;
  *)              PROOT_ABI="$ARCH" ;;
esac
QEMU_BIN=""    # set only if we fall back to emulation

# Arch-family heuristic (used for the --proot-bin / --system-proot paths, where
# there is no target binary to probe).
native_ok() {
  case "${HOST_ARCH}:${ARCH}" in
    aarch64:arm64-v8a) return 0 ;;
    armv7l:armeabi-v7a|armv6l:armeabi-v7a) return 0 ;;
    x86_64:x86_64|amd64:x86_64) return 0 ;;
    *) return 1 ;;
  esac
}

# True if the host can natively exec the given TARGET ELF (ENOEXEC -> false).
host_can_exec() {
  local elf="$1" out rc
  [[ -f "$elf" ]] || return 1
  chmod +x "$elf" 2>/dev/null || true
  set +e; out="$("$elf" --version 2>&1)"; rc=$?; set -e
  printf '%s' "$out" | grep -qiE 'exec format error|cannot execute' && return 1
  [[ $rc -eq 126 ]] && return 1
  return 0
}

# Fall back to QEMU for the current ARCH: confirm (unless --accept-force-emulate-qemu),
# install qemu-user-static if needed, set QEMU_BIN. Arg: human reason.
enter_qemu_fallback() {
  if [[ "$ACCEPT_EMULATE" -ne 1 ]]; then
    warn "Native ${ARCH} execution is NOT available on this ${HOST_ARCH} host ($1)."
    printf "${YEL}Fall back to QEMU emulation? SLOWER and MAY DIVERGE from native.\n" >&2
    printf "Type 'yes' to continue [default: NO]: ${RST}" >&2
    read -r _ans || _ans=""
    case "${_ans:-}" in y|Y|yes|YES|Yes) ;; *) die "Emulation not confirmed for ${ARCH} — aborting. Use --accept-force-emulate-qemu for unattended runs." ;; esac
  fi
  local want
  case "$ARCH" in
    armeabi-v7a) want="qemu-arm-static" ;;
    arm64-v8a)   want="qemu-aarch64-static" ;;
    *)           want="qemu-${DEB_ARCH}-static" ;;
  esac
  QEMU_BIN="$(command -v "$want" || command -v "${want%-static}" || true)"
  if [[ -z "$QEMU_BIN" ]]; then
    log "Installing qemu-user-static ..."
    apt-get update -y >/dev/null 2>&1 || true
    apt-get install -y qemu-user-static binfmt-support >/dev/null 2>&1 || true
    QEMU_BIN="$(command -v "$want" || command -v "${want%-static}" || true)"
  fi
  [[ -n "$QEMU_BIN" ]] || die "Need ${want} for ${ARCH} emulation but couldn't install it. Install qemu-user-static."
  warn "QEMU emulation enabled (${QEMU_BIN}); SLOWER + possible divergence — verify on-device."
}

mkdir -p "$WORKDIR" "$OUTDIR"
# START CLEAN — never trust leftover state from a previous run (it may have been
# killed mid-build, so cleaning only at the END is not enough). Remove the ephemeral
# build dirs up front: the extracted rootfs and the bound /tmp (PROOT_TMP -> /tmp +
# /dev/shm). Do NOT touch the caches in WORKDIR (Debian base, Go toolchain, native
# binaries) nor OUTDIR (your artifacts + PUBLISH_QUEUE). The host rm here runs
# OUTSIDE proot, so it removes even read-only git *.rev files with no trouble.
rm -rf "$INSTALL_WRAP" "$PROOT_TMP"
mkdir -p "$PROOT_TMP"; chmod 1777 "$PROOT_TMP"

# Base host tools (git is needed to read the iiab/iiab commit id)
for t in curl tar xz sha256sum unzip git; do
  command -v "$t" >/dev/null 2>&1 || { log "Installing host dependencies..."; apt-get update -y; apt-get install -y curl tar xz-utils coreutils unzip git; break; }
done

STAMP="$(date -u +%Y.%j)"                 # e.g. 2026.169 (year.day-of-year)
LOGFILE="${OUTDIR}/build-${TIER_NAME}-${ARCH}.log"

# --------- Select the proot binary (priority: local > system > release) -------
setup_proot_from_dir() {
  # args: <dir containing libproot.so [and libproot-loader*.so]>
  local d="$1"
  [[ -f "$d/libproot.so" ]] || die "No libproot.so in $d"
  chmod +x "$d"/lib*.so 2>/dev/null || true
  PROOT="$d/libproot.so"
  if [[ -f "$d/libproot-loader.so"   ]]; then export PROOT_LOADER="$d/libproot-loader.so"; fi
  if [[ -f "$d/libproot-loader32.so" ]]; then export PROOT_LOADER_32="$d/libproot-loader32.so"; fi
  return 0
}

if [[ -n "$PROOT_BIN" ]]; then
  # (1) Local binary provided by the user
  [[ -f "$PROOT_BIN" ]] || die "PROOT_BIN not found: $PROOT_BIN"
  chmod +x "$PROOT_BIN" 2>/dev/null || true
  PROOT="$PROOT_BIN"
  LOADER="$(dirname "$PROOT")/libproot-loader.so"
  [[ -f "$LOADER" ]] && export PROOT_LOADER="$LOADER"
  LOADER32="$(dirname "$PROOT")/libproot-loader32.so"
  [[ -f "$LOADER32" ]] && export PROOT_LOADER_32="$LOADER32"
  log "Using local proot: $PROOT"
  native_ok || enter_qemu_fallback "user-supplied proot; host arch differs from target (no probe)"
elif [[ "$USE_SYSTEM_PROOT" -eq 1 ]]; then
  # (3) System proot -- NOT recommended (often 5.1.0, old and segfaults)
  command -v proot >/dev/null 2>&1 || { apt-get update -y && apt-get install -y proot; }
  PROOT="$(command -v proot)"
  warn "Using system proot ($PROOT). If it segfaults or lacks flags, drop --system-proot."
  native_ok || enter_qemu_fallback "system proot; host arch differs from target"
else
  # (2) DEFAULT: download the native binaries from the release (same as the APK)
  TAG="$BINARIES_TAG"
  if [[ -z "$TAG" ]]; then
    log "Resolving binaries tag from ${VERSION_FILE_URL} ..."
    TAG="$(curl -fsSL "$VERSION_FILE_URL" 2>/dev/null | tr -d '[:space:]' || true)"
  fi
  [[ -n "$TAG" ]] || die "Could not resolve the binaries tag. Pass it with --binaries-tag <tag>."
  ZIP_URL="https://github.com/${REPO}/releases/download/${TAG}/termux-binaries-latest.zip"
  ZIP_LOCAL="${WORKDIR}/termux-binaries-${TAG}.zip"
  BIN_DIR="${WORKDIR}/native-${TAG}"
  log "Downloading native binaries from release: ${TAG}"
  if [[ ! -f "$ZIP_LOCAL" ]]; then
    curl -fL --retry 5 --retry-connrefused -o "$ZIP_LOCAL" "$ZIP_URL" \
      || die "Could not download $ZIP_URL (check the tag or your connection)."
  fi
  rm -rf "$BIN_DIR"; mkdir -p "$BIN_DIR"
  unzip -oq "$ZIP_LOCAL" -d "$BIN_DIR"
  # The zip ships jniLibs/<arch>/lib*.so + ninja_manifest.json + cacert.pem
  # Try NATIVE first: probe the actual TARGET proot binary. If the CPU runs it,
  # use the target-arch proot (no emulation). If it refuses (ENOEXEC), fall back
  # to the HOST-arch proot + QEMU on the guest.
  if host_can_exec "${BIN_DIR}/jniLibs/${ARCH}/libproot.so"; then
    log "Native ${ARCH} confirmed on this host; using proot from jniLibs/${ARCH}."
    setup_proot_from_dir "${BIN_DIR}/jniLibs/${ARCH}"
  else
    enter_qemu_fallback "the CPU refused a ${ARCH} test binary"
    [[ -d "${BIN_DIR}/jniLibs/${PROOT_ABI}" ]] || die "No host proot jniLibs/${PROOT_ABI} in the zip (contents: $(ls "$BIN_DIR/jniLibs" 2>/dev/null))"
    setup_proot_from_dir "${BIN_DIR}/jniLibs/${PROOT_ABI}"
  fi
  log "Using proot from release: $PROOT (tag ${TAG})"

  # Optional integrity check against ninja_manifest.json
  MANIFEST="${BIN_DIR}/ninja_manifest.json"
  if [[ -f "$MANIFEST" ]]; then
    EXP="$(grep -oE '"libproot\.so"[^}]*"sha256"[[:space:]]*:[[:space:]]*"[0-9a-f]+"' "$MANIFEST" 2>/dev/null | grep -oE '[0-9a-f]{64}' | head -1 || true)"
    if [[ -n "$EXP" ]]; then
      GOT="$(sha256sum "$PROOT" | awk '{print $1}')"
      [[ "$EXP" == "$GOT" ]] && ok "libproot.so integrity OK (sha256)" || warn "libproot.so sha256 does NOT match the manifest"
    fi
  fi
fi

# ptrace allowed (proot needs it)
if [[ -r /proc/sys/kernel/yama/ptrace_scope ]]; then
  PS="$(cat /proc/sys/kernel/yama/ptrace_scope)"
  [[ "${PS:-0}" -le 1 ]] || warn "ptrace_scope=$PS may block proot; consider 'sysctl kernel.yama.ptrace_scope=1'."
fi

# Free disk (>=8 GB recommended for full)
AVAIL_GB="$(df -BG --output=avail "$(dirname "$WORKDIR")" 2>/dev/null | tail -1 | tr -dc '0-9' || echo 0)"
[[ "${AVAIL_GB:-0}" -ge 8 ]] || warn "Low free space (~${AVAIL_GB}G). full needs ~8-10G."

# ----------------------------- 1) Debian base ---------------------------------
log "[1/5] Downloading Debian base (${BASE_TARBALL})..."
BASE_LOCAL="${WORKDIR}/${BASE_TARBALL}"
if [[ ! -f "$BASE_LOCAL" ]]; then
  curl -fL --retry 5 --retry-connrefused -o "$BASE_LOCAL" "$BASE_URL" \
    || die "Could not download the base: $BASE_URL"
fi

log "Extracting base into ${ROOTFS} (archive top-level = installed-rootfs/iiab/) ..."
mkdir -p "$ROOTFS"   # already wiped up front (START CLEAN); just (re)create it
# --strip-components=1 drops the proot-distro wrapper; exclude /dev (proot binds it)
tar --exclude='*/dev/*' --strip-components=1 -xJf "$BASE_LOCAL" -C "$ROOTFS"
[[ -e "$ROOTFS/bin/bash" || -L "$ROOTFS/bin/bash" ]] || die "Base has no /bin/bash; check the tarball."

# DNS inside the rootfs (the app rewrites resolv.conf; we replicate so apt resolves)
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOTFS/etc/resolv.conf"
printf '127.0.0.1 localhost\n' > "$ROOTFS/etc/hosts"
mkdir -p "$ROOTFS/tmp" "$ROOTFS/root"; chmod 1777 "$ROOTFS/tmp"

# Belt-and-suspenders for the iiab-refresh-wiki-docs cleanup under proot: modern git
# writes read-only pack *.rev (reverse-index) files that proot can fail to unlink.
# Disable them system-wide inside the rootfs so no *.rev is ever created during the
# install, so 'rm -rf /tmp/iiab-wiki' has nothing proot chokes on. Harmless elsewhere.
printf '[pack]\n\twriteReverseIndex = false\n' >> "$ROOTFS/etc/gitconfig"

# --- INTERIM proot-safe environment (should move to iiab-android / upstream) --
# Three independent chroot/proot detections misfire under proot on a desktop/
# server host (they happen to pass on-device), so IIAB tries systemd/reboot
# actions that do not exist under proot. We neutralize them deterministically:
#
#  (a) Ansible fact `ansible_facts.is_chroot`: gates e.g. `hostnamectl`
#      (roles/0-init/tasks/hostname.yml: `when: not ansible_facts.is_chroot`).
#      It is controlled by the `debian_chroot` env var. install.txt runs the
#      installer via `sudo`, which env_reset-strips it -> keep it across sudo.
mkdir -p "$ROOTFS/etc/sudoers.d"
echo 'Defaults env_keep += "debian_chroot"' > "$ROOTFS/etc/sudoers.d/99-iiab-chroot"
chmod 440 "$ROOTFS/etc/sudoers.d/99-iiab-chroot"
grep -q '^debian_chroot=' "$ROOTFS/etc/environment" 2>/dev/null \
  || echo 'debian_chroot=iiab' >> "$ROOTFS/etc/environment"
#  (b) hostnamectl shim (writes /etc/hostname) as a safety net.
mkdir -p "$ROOTFS/usr/local/sbin"
cat > "$ROOTFS/usr/local/sbin/hostnamectl" <<'SHIM'
#!/bin/sh
[ "$1" = "set-hostname" ] && [ -n "$2" ] && printf '%s\n' "$2" > /etc/hostname
exit 0
SHIM
chmod +x "$ROOTFS/usr/local/sbin/hostnamectl"
#  (c) native /usr/sbin/iiab decides to reboot via its own bash detection
#      `ischroot -t || systemd-detect-virt --container -q`. Under proot both
#      return false -> it runs `reboot` (rc 127) and aborts iiab-android before
#      its Android tail (dashboard + readiness flags). Force the chroot path:
cat > "$ROOTFS/usr/local/sbin/ischroot" <<'SHIM'
#!/bin/sh
exit 0   # 0 = we are in a chroot/proot (correct and device-faithful)
SHIM
chmod +x "$ROOTFS/usr/local/sbin/ischroot"
cat > "$ROOTFS/usr/local/sbin/reboot" <<'SHIM'
#!/bin/sh
echo "[shim] reboot suppressed (building under proot)"; exit 0
SHIM
chmod +x "$ROOTFS/usr/local/sbin/reboot"
# -----------------------------------------------------------------------------

# ----------------------------- 2) Launch proot (flags = PRootEngine.java) ------
run_in_proot() {
  # Base flags + optional ones only if this proot recognizes them (release does).
  local opts=(-0 -k "$KERNEL_STR")
  [[ -n "${QEMU_BIN:-}" ]] && opts+=(-q "$QEMU_BIN")   # emulate the foreign-arch guest
  local f
  for f in --link2symlink --sysvipc --kill-on-exit; do
    if "$PROOT" --help 2>&1 | grep -q -- "$f"; then
      opts+=("$f")
    else
      warn "proot does not support $f -> skipping it (use the release/APK proot for fidelity)"
    fi
  done
  # PROOT_TMP_DIR on the HOST side: the OUTER proot writes its temp here (writable).
  PROOT_TMP_DIR="${PROOT_TMP}" "$PROOT" \
    "${opts[@]}" \
    -r "$ROOTFS" \
    -b /dev -b /proc -b /sys \
    -b "${PROOT_TMP}:/tmp" \
    -b "${PROOT_TMP}:/dev/shm" \
    -w /root \
    /usr/bin/env -i \
      PREFIX="${WORKDIR}/usr" \
      PROOT_TMP_DIR=/tmp \
      HOME=/root USER=root LOGNAME=root \
      TMPDIR=/tmp TERM=xterm-256color LANG=C.UTF-8 \
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
      AUTO_INSTALL_SIZE="${TIER_NUM}" \
      DEBIAN_FRONTEND=noninteractive \
      debian_chroot=iiab \
    /bin/bash -lc "$1"
}

log "[2/5] Running the IIAB-oA installer under proot (tier=${TIER_NAME}). This takes a while; grab a coffee..."
INSTALL_CMD="set -e
apt-get update
apt-get install -y curl ca-certificates
curl -fsSL '${INSTALLER_URL}' -o /usr/local/sbin/iiab-android
chmod +x /usr/local/sbin/iiab-android
/usr/local/sbin/iiab-android -f"

set +e
run_in_proot "$INSTALL_CMD" 2>&1 | tee "$LOGFILE"
INSTALL_RC="${PIPESTATUS[0]}"
set -e

# ----------------------------- 3) Validate "clean build" ----------------------
log "[3/5] Validating build..."
PASS=1
chk() { if eval "$2" >/dev/null 2>&1; then ok "OK  - $1"; else warn "FAIL - $1"; PASS=0; fi; }

if [[ "$INSTALL_RC" -eq 0 ]]; then ok "OK  - installer exited with code 0"; else warn "FAIL - installer rc=$INSTALL_RC"; PASS=0; fi
chk "PDSM installed (/usr/local/bin/pdsm)"     "test -e '$ROOTFS/usr/local/bin/pdsm'"
chk "flag_install_ready present"               "test -e '$ROOTFS/usr/local/pdsm/flag_install_ready'"
chk "local_vars.yml has is_proot: True"        "grep -qiE '^[[:space:]]*is_proot[[:space:]]*:[[:space:]]*True' '$ROOTFS/etc/iiab/local_vars.yml'"
chk "IIAB-oA repo cloned (/opt/iiab-android)"  "test -d '$ROOTFS/opt/iiab-android'"

# Ansible result: use the PLAY RECAP (authoritative). We do NOT count loose
# 'fatal:'/'FAILED!' lines, because block/rescue tasks (e.g. the WiFi-AP probe
# 'iw list', absent under proot) print those but end up rescued/ignored ->
# failed=0. Only failed/unreachable matter.
if grep -q 'PLAY RECAP' "$LOGFILE" 2>/dev/null; then
  RECAP_FAILED="$(grep -oE 'failed=[0-9]+' "$LOGFILE" | grep -oE '[0-9]+' | awk '{s+=$1} END{print s+0}')"
  RECAP_UNREACH="$(grep -oE 'unreachable=[0-9]+' "$LOGFILE" | grep -oE '[0-9]+' | awk '{s+=$1} END{print s+0}')"
  if [[ "${RECAP_FAILED:-0}" -eq 0 && "${RECAP_UNREACH:-0}" -eq 0 ]]; then
    ok "OK  - Ansible PLAY RECAP clean (failed=0, unreachable=0; rescued/ignored are normal)"
  else
    warn "FAIL - Ansible PLAY RECAP: failed=${RECAP_FAILED}, unreachable=${RECAP_UNREACH}"
    PASS=0
  fi
else
  warn "FAIL - no PLAY RECAP found in the log (incomplete install)"
  PASS=0
fi

if [[ "$PASS" -ne 1 ]]; then
  warn "Validation NOT clean. Review $LOGFILE before publishing."
  warn "(The tarball is still generated so you can inspect the tree; do not publish it yet.)"
fi

# --------- Identify the iiab/iiab version baked into this rootfs --------------
# Done AFTER install so we read the actual checked-out commit (fidelity).
if [[ -n "$IIAB_COMMIT" ]]; then
  IIAB_SHA="${IIAB_COMMIT:0:7}"; IIAB_SRC="manual (--iiab-commit)"
else
  IIAB_SHA="$(iiab_commit_from_rootfs || true)"; IIAB_SRC="rootfs /opt/iiab/iiab"
  if [[ -z "$IIAB_SHA" ]]; then
    warn "Could not read iiab/iiab commit from the rootfs; falling back to the remote default branch."
    IIAB_SHA="$(resolve_iiab_commit_remote || true)"; IIAB_SRC="remote (ls-remote/API)"
  fi
fi
[[ -n "$IIAB_SHA" ]] || die "Could not determine the iiab/iiab commit id. Pass --iiab-commit <sha>."
log "iiab/iiab id: ${IIAB_SHA}  [source: ${IIAB_SRC}]"

ARTIFACT="iiab-oa_${STAMP}_${TIER_NAME}_${IIAB_SHA}_${ARCH}.tar.gz"
META4="latest_${TIER_NAME}_${ARCH}.meta4"

# ----------------------------- 4) Package (top-level installed-rootfs/iiab/) ----
log "[4/5] Packaging ${ARTIFACT} ..."

# proot --link2symlink turns hardlinks into ABSOLUTE symlinks pointing at
# .l2s.<name>NNNN files under the BUILD path (this host). Those break when the
# rootfs is restored at a different path on the device -> e.g. node_modules'
# better_sqlite3.node becomes a dangling link and the dashboard can't start.
# Materialize every .l2s symlink into a real file NOW (the targets still resolve
# on this host), so the packaged rootfs is fully relocatable.
log "Flattening proot link2symlink (.l2s) artifacts for relocatability..."
_l2s_n=0
while IFS= read -r -d '' _lnk; do
  _tgt="$(readlink -f "$_lnk" 2>/dev/null)" || continue
  if [[ -n "$_tgt" && -f "$_tgt" ]]; then
    cp -f --remove-destination "$_tgt" "$_lnk" && _l2s_n=$((_l2s_n+1))
  fi
done < <(find "$ROOTFS" -type l -lname '*/.l2s.*' -print0 2>/dev/null)
find "$ROOTFS" -type f -name '.l2s.*' -delete 2>/dev/null || true
log "Materialized ${_l2s_n} link2symlink (.l2s) entries into real files."
rm -f "$ROOTFS/etc/resolv.conf" 2>/dev/null || true            # ephemeral; the APK injects it at runtime
# Remove the interim build shims so the rootfs does not diverge from the APK
rm -f "$ROOTFS/usr/local/sbin/hostnamectl" \
      "$ROOTFS/usr/local/sbin/ischroot" \
      "$ROOTFS/usr/local/sbin/reboot" 2>/dev/null || true
# Embed the self-validation manifest (INTEGRITY only). Frozen spec + recipe:
#   docs/ROOTFS_MANIFEST.md  (algo: iiab-tree-sha256-v1)
# Two members inside the tree: identity (.iiab-rootfs.json, packed FIRST, hashed)
# and integrity (.iiab-rootfs.integrity.json, packed LAST, excluded from its own
# hash). Lets a manually-imported rootfs (no sidecar .meta4) detect corruption.
TREEHASH_PY="$(dirname "$SELF")/iiab_tree_hash.py"
[[ -f "$TREEHASH_PY" ]] || die "missing $(basename "$TREEHASH_PY") next to the build script (treehash recipe)."
ID_MEMBER="installed-rootfs/iiab/.iiab-rootfs.json"
INTEG_MEMBER="installed-rootfs/iiab/.iiab-rootfs.integrity.json"

log "Embedding rootfs manifest (identity + integrity, iiab-tree-sha256-v1) ..."
rm -f "$ROOTFS/.iiab-rootfs.json" "$ROOTFS/.iiab-rootfs.integrity.json" 2>/dev/null || true

# (a) identity member -- packed first, IS hashed (single-line JSON, no trailing newline issues)
cat > "$ROOTFS/.iiab-rootfs.json" <<JSON
{"schema":1,"kind":"iiab-rootfs","arch":"${ARCH}","deb_arch":"${DEB_ARCH}","tier":"${TIER_NAME}","iiab_commit":"${IIAB_SHA}","built":"${STAMP}","base":"debian-trixie","proot_distro":"${PD_VERSION}","builder":"build-iiab-rootfs.sh"}
JSON

# (b) deterministic member list: identity FIRST, then everything else (LC_ALL=C
#     sorted), both manifest members excluded here. --no-recursion + -T <list>
#     makes the archive's member order EXACTLY this order, so what we hash in (c)
#     equals what ends up in the artifact (and what the verifier later reads).
MEMBER_LIST="$(mktemp)"
( cd "$WORKDIR"
  printf '%s\n' "$ID_MEMBER"
  find installed-rootfs ! -path "$ID_MEMBER" ! -path "$INTEG_MEMBER" -print | LC_ALL=C sort
) > "$MEMBER_LIST"

# (c) compute the treehash over a STREAMED uncompressed tar of that list (no temp
#     copy on disk; scales to multi-GB rootfs). Integrity member is absent here.
TREEHASH="$( ( cd "$WORKDIR" && tar -c --no-recursion -T "$MEMBER_LIST" ) \
             | python3 "$TREEHASH_PY" - "$INTEG_MEMBER" )" \
  || { rm -f "$MEMBER_LIST"; die "treehash computation failed (unhashable member type? see docs/ROOTFS_MANIFEST.md)"; }
[[ -n "$TREEHASH" ]] || { rm -f "$MEMBER_LIST"; die "empty treehash"; }

# (d) integrity member -- packed last, NOT hashed
cat > "$ROOTFS/.iiab-rootfs.integrity.json" <<JSON
{"schema":1,"algo":"iiab-tree-sha256-v1","treehash":"${TREEHASH}"}
JSON

# (e) final artifact: same member order as (c), with the integrity member appended
#     LAST. top-level = installed-rootfs/iiab/ (extracts into rootfs/ as the app expects)
( cd "$WORKDIR" && tar -cz --no-recursion \
    -T <(cat "$MEMBER_LIST"; printf '%s\n' "$INTEG_MEMBER") \
    -f "${OUTDIR}/${ARTIFACT}" ) \
  || { rm -f "$MEMBER_LIST"; die "packaging ${ARTIFACT} failed"; }
rm -f "$MEMBER_LIST"

# (f) build self-verify: re-read the finished artifact and confirm the embedded
#     treehash matches. Never ship an artifact our own verifier would reject.
log "Self-verifying embedded integrity (iiab-tree-sha256-v1) ..."
_VERIFY="$(python3 "$TREEHASH_PY" "${OUTDIR}/${ARTIFACT}" "$INTEG_MEMBER")" \
  || die "self-verify failed to re-read ${ARTIFACT}"
[[ "$_VERIFY" == "$TREEHASH" ]] \
  || die "integrity self-verify MISMATCH (built=${TREEHASH} read=${_VERIFY}); refusing to ship ${ARTIFACT}"
log "Integrity self-verify OK: ${TREEHASH}"
SIZE_BYTES="$(stat -c%s "${OUTDIR}/${ARTIFACT}")"
SHA256="$(sha256sum "${OUTDIR}/${ARTIFACT}" | awk '{print $1}')"
echo "$SHA256  $ARTIFACT" > "${OUTDIR}/${ARTIFACT}.sha256"

# ----------------------------- 5) Generate .meta4 (Metalink, via mkmetalink) --
# mkmetalink (Go) builds a valid Metalink v4 (.meta4) + matching .torrent.
#   https://github.com/chapmanjacobd/mkmetalink
# We pass the publish + mirror base URLs so the .meta4 carries the same <url>
# entries the APK/aria2 expect (priority 10 = primary, 11 = mirror). It names its
# output after the artifact; the APK requests a STABLE name, so we copy it to
# latest_<tier>_<arch>.meta4 (a plain copy: portable to GitHub Actions, where a
# symlink would be awkward; on a normal server a symlink/rename works too).
log "[5/5] Generating ${META4} with mkmetalink ..."
ensure_mkmetalink           # finds/bootstraps mkmetalink (downloads a recent Go if needed)
log "Using mkmetalink: ${MKMETALINK}"

# Run from OUTDIR with the bare filename so <file name>=ARTIFACT and each source
# URL becomes <base>/<ARTIFACT> (no stray directory component). Priority follows the
# -m order: primary = 10, then each mirror = 11, 12, 13, ...
MIRROR_ARGS=(-m "${PUBLISH_URL%/}/")
for _mu in "${MIRRORS[@]}"; do
  [[ -n "$_mu" ]] && MIRROR_ARGS+=(-m "${_mu%/}/")
done
( cd "$OUTDIR" && "$MKMETALINK" "${MIRROR_ARGS[@]}" "$ARTIFACT" ) \
  || die "mkmetalink failed to generate the metalink."
GEN_META4="${OUTDIR}/${ARTIFACT}.meta4"
[[ -f "$GEN_META4" ]] || die "mkmetalink did not produce ${GEN_META4}"
cp -f "$GEN_META4" "${OUTDIR}/${META4}"
ok "Metalink ready: ${OUTDIR}/${META4}  (copy of ${ARTIFACT}.meta4)"
ok "Sources in .meta4: primary ${PUBLISH_URL%/}/ + ${#MIRRORS[@]} mirror(s)."
ok "(also produced: ${ARTIFACT}.torrent in ${OUTDIR})"

# ----------------------------- Summary ----------------------------------------
echo
printf "${BOLD}==================== SUMMARY ====================${RST}\n"
printf "  Tier .............. %s (%s)\n" "$TIER_NAME" "$TIER_NUM"
printf "  Arch .............. %s (%s)\n" "$ARCH" "$DEB_ARCH"
printf "  iiab/iiab id ...... %s  [%s]\n" "$IIAB_SHA" "$IIAB_SRC"
printf "  proot ............. %s\n" "$PROOT"
printf "  Validation ........ %s\n" "$([[ $PASS -eq 1 ]] && echo 'CLEAN - OK' || echo 'HAS FAILURES - check log')"
printf "  Artifact .......... %s (%s bytes)\n" "${OUTDIR}/${ARTIFACT}" "$SIZE_BYTES"
printf "  SHA-256 ........... %s\n" "$SHA256"
printf "  Metalink (stable) . %s\n" "${OUTDIR}/${META4}"
printf "  Primary ........... %s\n" "${PUBLISH_URL%/}/"
_p=11; for _mu in "${MIRRORS[@]}"; do [[ -n "$_mu" ]] && { printf "  Mirror (prio %s) .. %s\n" "$_p" "${_mu%/}/"; _p=$((_p+1)); }; done
printf "  Log ............... %s\n" "$LOGFILE"
BUILD_END_EPOCH="$(date +%s)"; BUILD_SECONDS=$((BUILD_END_EPOCH - BUILD_START_EPOCH))
printf "  Started ........... %s\n" "$BUILD_START_HUMAN"
printf "  Finished .......... %s\n" "$(date '+%Y-%m-%d %H:%M:%S %Z')"
printf "  Build time ........ %s (%ss)\n" "$(fmt_dur "$BUILD_SECONDS")" "$BUILD_SECONDS"
printf "${BOLD}=================================================${RST}\n"
echo

# ---------- Stage only: record in the local publish queue (NO auto-publish) ----
# This server never uploads. Everything stays in OUTDIR; a later GitHub Actions
# workflow reads this queue and pushes READY artifacts to the chosen destination(s)
# (Cloudflare / mirror / GitHub release). HOLD = validation not clean -> hold back.
PUB_STATUS="$([[ "$PASS" -eq 1 ]] && echo READY || echo HOLD)"
QUEUE="${OUTDIR}/PUBLISH_QUEUE.tsv"
[[ -f "$QUEUE" ]] || printf 'built_utc\tstatus\ttier\tarch\tiiab_sha\tartifact\tsize_bytes\tbuild_seconds\tsha256\tmeta4\n' > "$QUEUE"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$PUB_STATUS" "$TIER_NAME" "$ARCH" "$IIAB_SHA" \
  "$ARTIFACT" "$SIZE_BYTES" "$BUILD_SECONDS" "$SHA256" "$META4" >> "$QUEUE"

printf "${BOLD}Staged in %s  —  status: %s  —  NOT published.${RST}\n" "$OUTDIR" "$PUB_STATUS"
echo "This script only BUILDS; it never uploads. A GitHub Actions workflow could"
echo "later push READY artifacts to the destination(s) (Cloudflare / mirror / release)."
echo "Pending-publish queue: ${QUEUE}"
echo "When published, files must land so the baked-in .meta4 URLs resolve:"
echo "  primary: ${PUBLISH_URL%/}/${ARTIFACT}"
for _mu in "${MIRRORS[@]}"; do [[ -n "$_mu" ]] && echo "  mirror : ${_mu%/}/${ARTIFACT}"; done
echo "  stable pointer the APK reads: ${PUBLISH_URL%/}/${META4}"

# ---------- Housekeeping: don't leave build garbage behind ---------------------
# Caches (Debian base, Go toolchain, native binaries) stay in WORKDIR for speed.
# The big ephemeral bits (the extracted rootfs + the bound /tmp) are removed on a
# clean build; on a FAILED one they are kept for inspection. --keep always retains.
if [[ "$KEEP_ROOTFS" -eq 1 ]]; then
  log "Housekeeping: --keep set, retaining ${ROOTFS} and ${PROOT_TMP}."
elif [[ "$PASS" -eq 1 ]]; then
  log "Housekeeping: removing extracted rootfs + /tmp (artifacts kept in ${OUTDIR})."
  rm -rf "$INSTALL_WRAP" "$PROOT_TMP"
else
  warn "Housekeeping: build not clean -> keeping ${ROOTFS} and ${PROOT_TMP} for inspection."
fi

[[ "$PASS" -eq 1 ]] && exit 0 || exit 2
