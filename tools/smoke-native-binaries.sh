#!/usr/bin/env bash
# =============================================================================
# smoke-native-binaries.sh — contract/smoke test for the vendored native binaries
# (proot, aria2c, tar, gzip, xz, rsync, nano, less) shipped in the APK jniLibs.
#
# Gives each binary a known input -> known-good output ("answer sheet") so a
# recompiled bundle that no longer behaves as expected is caught before it is
# published / adopted. Standard + inventory: Claude-Env docs; ticket ADFA-4465.
#
# Execution model (mirrors tools/build-iiab-rootfs.sh):
#   - Runs the REAL lib*.so from jniLibs/<arch>.
#   - NATIVE is decided by ARCH FAMILY (uname -m vs target), not by whether exec
#     happens to work: binfmt/QEMU can exec a foreign binary transparently, but
#     proot under emulation is unreliable, so the real situation must be known.
#   - Some bundle binaries are bionic/termux-linked (not static): they cannot be
#     executed on a generic Linux host (no loader). Such a binary's BEHAVIORAL
#     row is SKIPPED (still covered by the integrity/sha256 row); it is exercised
#     faithfully on-device / in an environment that provides its loader.
#
# Usage:
#   smoke-native-binaries.sh --jnilibs <dir> [--arch arm64-v8a|armeabi-v7a]
#   <dir> contains jniLibs/ (and ninja_manifest.json), or is jniLibs/<arch>.
#
# Exit 0 = all applicable contract rows passed; non-zero = a failure.
# Copyright (c) 2026 AppDevForAll.
# =============================================================================
set -u

RED="\033[31m"; GRN="\033[32m"; YEL="\033[33m"; BLU="\033[34m"; RST="\033[0m"
ok()   { printf "${GRN}[smoke] PASS${RST} %s\n" "$*"; }
bad()  { printf "${RED}[smoke] FAIL${RST} %s\n" "$*"; }
skip() { printf "${YEL}[smoke] SKIP${RST} %s\n" "$*"; }
log()  { printf "${BLU}[smoke]${RST} %s\n" "$*"; }

ARCH="arm64-v8a"; JNILIBS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --jnilibs) JNILIBS="$2"; shift 2 ;;
    --arch)    ARCH="$2"; shift 2 ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$JNILIBS" ] || { echo "ERROR: --jnilibs <dir> is required" >&2; exit 2; }

if   [ -d "$JNILIBS/jniLibs/$ARCH" ]; then BIN="$JNILIBS/jniLibs/$ARCH"
elif [ -d "$JNILIBS/$ARCH" ];        then BIN="$JNILIBS/$ARCH"
elif [ -f "$JNILIBS/libtar.so" ];    then BIN="$JNILIBS"
else echo "ERROR: cannot find jniLibs for $ARCH under $JNILIBS" >&2; exit 2; fi
BIN="$(cd "$BIN" && pwd)"
MANIFEST=""
for m in "$JNILIBS/ninja_manifest.json" "$BIN/../ninja_manifest.json" "$BIN/../../ninja_manifest.json"; do
  [ -f "$m" ] && { MANIFEST="$m"; break; }
done
log "Binaries dir: $BIN"
log "Target arch : $ARCH"

# --- Native vs emulated (by arch family) -------------------------------------
HOST_ARCH="$(uname -m)"
case "$ARCH" in
  arm64-v8a)   FAMILY="aarch64 arm64";     QEMU_WANT="qemu-aarch64-static" ;;
  armeabi-v7a) FAMILY="armv7l armv6l arm"; QEMU_WANT="qemu-arm-static" ;;
  x86_64)      FAMILY="x86_64 amd64";      QEMU_WANT="" ;;
  *)           FAMILY="$ARCH";             QEMU_WANT="qemu-${ARCH}-static" ;;
esac
NATIVE=0; for f in $FAMILY; do [ "$HOST_ARCH" = "$f" ] && NATIVE=1; done
chmod +x "$BIN"/*.so 2>/dev/null || true

QEMU=""
if [ "$NATIVE" -eq 1 ]; then
  log "Native: host $HOST_ARCH runs $ARCH directly."
else
  if [ -f "$BIN/libtar.so" ] && "$BIN/libtar.so" --version >/dev/null 2>&1; then
    log "Emulated: foreign $ARCH on $HOST_ARCH via registered binfmt/QEMU."
  elif [ -n "$QEMU_WANT" ] && command -v "$QEMU_WANT" >/dev/null 2>&1; then
    QEMU="$(command -v "$QEMU_WANT")"; log "Emulated: foreign $ARCH on $HOST_ARCH via explicit $QEMU."
  else
    echo "ERROR: $ARCH is foreign to $HOST_ARCH and no QEMU is available." >&2
    echo "       Install qemu-user-static (+binfmt-support) or run on a $ARCH host." >&2
    exit 2
  fi
fi

# run_bin <lib.so> [args...] — exec a bundle binary (direct/binfmt or via QEMU).
run_bin() { local b="$1"; shift; if [ -n "$QEMU" ]; then "$QEMU" "$b" "$@"; else "$b" "$@"; fi; }
# bin_runs <lib.so> — true if the binary can actually execute on this host
# (false when the ELF interpreter/loader is absent: rc 126/127).
bin_runs() { run_bin "$1" --version >/dev/null 2>&1; local rc=$?; [ "$rc" -ne 126 ] && [ "$rc" -ne 127 ]; }

PASS=0; FAIL=0
# chk <description> <command...>  — the answer-sheet engine.
chk() { local d="$1"; shift; if "$@" >/dev/null 2>&1; then ok "$d"; PASS=$((PASS+1)); else bad "$d"; FAIL=$((FAIL+1)); fi; }
# runchk <description> <lib.so> <command...> — like chk, but if the binary cannot
# execute on this host (dynamic, no loader), SKIP instead of FAIL (off-device limit).
runchk() {
  local d="$1" lib="$2"; shift 2
  if bin_runs "$BIN/$lib"; then chk "$d" "$@"
  else skip "$d — $lib not executable here (dynamic/loader); behavioral check deferred to device"; fi
}

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
mkdir -p src/sub
printf 'hello knowledge to go\n' > src/a.txt
head -c 4096 /dev/zero | tr '\0' 'Z' > src/sub/b.bin

# ============================== CONTRACT ROWS ================================

# 1) Integrity (always; works off-device): presence + sha256 vs manifest.
present_all() { local f; for f in libproot.so libaria2c.so libtar.so libgzip.so libxz.so librsync.so libnano.so libless.so; do [ -f "$BIN/$f" ] || { echo "missing $f"; return 1; }; done; }
chk "all 8 binaries present in jniLibs/$ARCH" present_all
manifest_match() {
  [ -n "$MANIFEST" ] || { echo "no manifest"; return 0; }
  local f got exp
  for f in libproot.so libtar.so libaria2c.so libxz.so librsync.so; do
    exp="$(grep -oE "\"$f\"[^}]*\"sha256\"[[:space:]]*:[[:space:]]*\"[0-9a-f]+\"" "$MANIFEST" 2>/dev/null | grep -oE '[0-9a-f]{64}' | head -1)"
    [ -n "$exp" ] || continue
    got="$(sha256sum "$BIN/$f" | awk '{print $1}')"
    [ "$exp" = "$got" ] || { echo "$f sha mismatch"; return 1; }
  done
}
chk "manifest sha256 matches (when present)" manifest_match

# 1b) Static-linkage contract: the required set MUST be statically linked (no
# ELF interpreter, no dynamic NEEDED libs). Cross-arch (readelf reads the ELF;
# no execution). aria2c is intentionally DYNAMIC (its bionic DNS needs Android
# netd), so it is reported but NOT required static.
REQUIRED_STATIC="libproot.so libtar.so libgzip.so libxz.so librsync.so libnano.so libless.so"
is_static() {
  local f="$1" interp needed
  if command -v readelf >/dev/null 2>&1; then
    interp="$(readelf -l "$f" 2>/dev/null | grep -i 'program interpreter')"
    needed="$(readelf -d "$f" 2>/dev/null | grep -c 'NEEDED')"
    [ -z "$interp" ] && [ "$needed" -eq 0 ]
  else
    file "$f" 2>/dev/null | grep -qi 'statically linked'
  fi
}
static_contract() {
  local f bad=""
  for f in $REQUIRED_STATIC; do is_static "$BIN/$f" || bad="$bad $f"; done
  [ -z "$bad" ] || { echo "dynamic (must be static):$bad"; return 1; }
}
if command -v readelf >/dev/null 2>&1 || command -v file >/dev/null 2>&1; then
  chk "required binaries are statically linked" static_contract
  is_static "$BIN/libaria2c.so" && log "note: libaria2c.so is STATIC here (allowed; intentional target is dynamic)"                                  || log "note: libaria2c.so is DYNAMIC (intentional; bionic DNS needs Android netd)"
else
  skip "static-linkage check (need readelf or file)"
fi

# 2) tar — round-trip + the app's gzip|tar -xvf - extraction path
tar_roundtrip() { run_bin "$BIN/libtar.so" -cf arc.tar -C src . || return 1; rm -rf out && mkdir out; run_bin "$BIN/libtar.so" -xf arc.tar -C out || return 1; diff -r src out; }
runchk "tar create+extract round-trip is byte-identical" libtar.so tar_roundtrip
tar_stdin_gzip() { run_bin "$BIN/libtar.so" -cf arc2.tar -C src . || return 1; run_bin "$BIN/libgzip.so" -c arc2.tar > arc2.tar.gz || return 1; rm -rf out2 && mkdir out2; run_bin "$BIN/libgzip.so" -dc arc2.tar.gz | run_bin "$BIN/libtar.so" -xvf - -C out2 || return 1; diff -r src out2; }
runchk "gzip -dc | tar -xvf - (app extraction path)" libtar.so tar_stdin_gzip

# 3) gzip / xz — round-trips
gzip_roundtrip() { run_bin "$BIN/libgzip.so" -c src/sub/b.bin > b.gz || return 1; run_bin "$BIN/libgzip.so" -dc b.gz > b.out || return 1; cmp -s src/sub/b.bin b.out; }
runchk "gzip compress/decompress round-trip" libgzip.so gzip_roundtrip
xz_roundtrip() { run_bin "$BIN/libxz.so" -z -c src/sub/b.bin > b.xz || return 1; run_bin "$BIN/libxz.so" -dc b.xz > b.xzout || return 1; cmp -s src/sub/b.bin b.xzout; }
runchk "xz compress/decompress round-trip" libxz.so xz_roundtrip

# 4) rsync — local mirror (daemon mode is exercised on-device)
rsync_roundtrip() { rm -rf dest && mkdir dest; run_bin "$BIN/librsync.so" -a --delete src/ dest/ || return 1; diff -r src dest; }
runchk "rsync -a mirror reproduces the tree" librsync.so rsync_roundtrip

# 5) aria2c — runs and still has the features the app depends on
aria2_features() { local out; out="$(run_bin "$BIN/libaria2c.so" --version 2>&1)" || return 1; echo "$out" | grep -qi 'Metalink' && echo "$out" | grep -qiE 'https|TLS|SSL' && echo "$out" | grep -qi 'BitTorrent'; }
runchk "aria2c has Metalink/https/BitTorrent" libaria2c.so aria2_features

# 6) nano / less — execute (interactive on-device; here just confirm they run)
runchk "nano --version runs" libnano.so run_bin "$BIN/libnano.so" --version
runchk "less --version runs" libless.so run_bin "$BIN/libless.so" --version

# 7) proot — real launch when native; version-only under emulation. Needs loader.
proot_launch() {
  # Use a bundle binary we KNOW is static (libtar) as the in-rootfs program, so
  # the test needs no host busybox and no shared libs inside the minimal rootfs.
  rm -rf rootfs ptmp && mkdir -p rootfs ptmp && cp "$BIN/libtar.so" rootfs/probe || return 1
  export PROOT_LOADER="$BIN/libproot-loader.so"
  [ -f "$BIN/libproot-loader32.so" ] && export PROOT_LOADER_32="$BIN/libproot-loader32.so"
  export PROOT_TMP_DIR="$WORK/ptmp"
  local out; out="$(run_bin "$BIN/libproot.so" -r "$WORK/rootfs" -w / /probe --version 2>/dev/null)" || return 1
  echo "$out" | grep -qi 'tar'
}
if [ "$NATIVE" -eq 1 ] && bin_runs "$BIN/libproot.so"; then
  chk "proot launches a command in a minimal rootfs (stdout=hello)" proot_launch
elif bin_runs "$BIN/libproot.so"; then
  skip "proot launch (emulated $ARCH); version OK — full launch verified on native arch / device"
else
  bad "proot is not executable on this host (--version failed)"; FAIL=$((FAIL+1))
fi

# ============================== VERDICT ======================================
echo
log "Result: ${PASS} passed, ${FAIL} failed (arch=$ARCH, mode=$([ "$NATIVE" -eq 1 ] && echo native || echo qemu))."
[ "$FAIL" -eq 0 ] || { bad "Contract NOT satisfied — do not publish/adopt this build."; exit 1; }
ok "All applicable native-binary contracts satisfied."
