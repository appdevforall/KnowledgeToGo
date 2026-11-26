#!/usr/bin/env bash
# =============================================================================
# run-kernel-matrix.sh: run the native binaries on real old and new kernels (K2GO-595).
# Author    : AppDevForAll
# Copyright : Copyright (c) 2026 AppDevForAll
#
# The CI runners have a new kernel, and proot only fakes what uname says: each call still goes to
# the real kernel, on the phone the kernel of the phone. So this script boots each kernel of
# kernels.lock in QEMU (arm64 virt machine) and runs guest-init.sh in it, on both ABIs. A FAIL, or a
# VM that does not reach its end line, fails the script.
#
# Usage: run-kernel-matrix.sh --jnilibs <dir> [--cache <dir>] [--kernels "3.16 4.9"]
#   <dir> holds arm64-v8a/ and armeabi-v7a/ (or is a folder with jniLibs/ in it).
# Needs: qemu-system-aarch64, cpio, gzip, dpkg-deb, sha256sum, curl,
#        aarch64-linux-gnu-gcc and arm-linux-gnueabihf-gcc (static probes).
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LOCK="$HERE/kernels.lock"
JNI=""
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/k2go-kernel-matrix"
KERNELS=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --jnilibs) JNI="$2"; shift 2 ;;
        --cache)   CACHE="$2"; shift 2 ;;
        --kernels) KERNELS="$2"; shift 2 ;;
        *) echo "usage: $0 --jnilibs <dir> [--cache <dir>] [--kernels \"3.16 4.9\"]" >&2; exit 2 ;;
    esac
done
[ -n "$JNI" ] || { echo "ERROR: --jnilibs is required" >&2; exit 2; }
[ -d "$JNI/jniLibs" ] && JNI="$JNI/jniLibs"
for t in qemu-system-aarch64 cpio gzip dpkg-deb sha256sum curl aarch64-linux-gnu-gcc arm-linux-gnueabihf-gcc; do
    command -v "$t" >/dev/null || { echo "ERROR: missing tool: $t" >&2; exit 2; }
done
[ -n "$KERNELS" ] || KERNELS="$(awk '!/^#/ && $1 ~ /^[0-9]/ {print $1}' "$LOCK" | tr '\n' ' ')"
mkdir -p "$CACHE"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Download one lock entry to the cache, or reuse it; the SHA-256 must match in both cases.
fetch() {
    local name="$1" sha="" url="" f
    read -r sha url < <(awk -v n="$name" '$1 == n {print $2, $3}' "$LOCK") || true
    [ -n "${sha:-}" ] || { echo "ERROR: $name is not in kernels.lock" >&2; exit 2; }
    f="$CACHE/$name.deb"
    if [ ! -f "$f" ] || [ "$(sha256sum < "$f" | cut -d' ' -f1)" != "$sha" ]; then
        curl -sSfL --retry 3 -o "$f.part" "$url"
        mv "$f.part" "$f"
    fi
    [ "$(sha256sum < "$f" | cut -d' ' -f1)" = "$sha" ] \
        || { echo "ERROR: SHA-256 of $name does not match kernels.lock" >&2; exit 1; }
    echo "$f"
}

# --- The initramfs: busybox as /bin, the bundle, and one proot rootfs per ABI ---
ROOT="$WORK/root"
mkdir -p "$ROOT"/{bin,proc,sys,dev,tmp,k2go,r}
dpkg-deb -x "$(fetch busybox-arm64)" "$WORK/bb-arm64"
dpkg-deb -x "$(fetch busybox-armhf)" "$WORK/bb-armhf"
cp "$WORK/bb-arm64/bin/busybox" "$ROOT/bin/busybox"
cp "$HERE/guest-init.sh" "$ROOT/init"
chmod 755 "$ROOT/init"
# The seccomp markers of the app fallback (ADFA-5362). SeccompFailure.java is their one source.
SECCOMP_SRC="$HERE/../../../controller/app/src/main/java/org/appdevforall/k2go/proot/domain/SeccompFailure.java"
sed -n 's/^ *private static final String [A-Z_]*_MARKER = "\(.*\)";$/\1/p' "$SECCOMP_SRC" > "$ROOT/k2go/seccomp-markers"
[ "$(wc -l < "$ROOT/k2go/seccomp-markers")" -ge 1 ] \
    || { echo "ERROR: no seccomp markers in $SECCOMP_SRC" >&2; exit 2; }
aarch64-linux-gnu-gcc -static -O2 -Wall -Werror -o "$WORK/probe-arm64" "$HERE/probe.c"
arm-linux-gnueabihf-gcc -static -O2 -Wall -Werror -o "$WORK/probe-armhf" "$HERE/probe.c"
ABIS=""
for pair in arm64-v8a:arm64 armeabi-v7a:armhf; do
    abi="${pair%%:*}"; deb="${pair##*:}"
    [ -d "$JNI/$abi" ] || continue
    ABIS="$ABIS $abi"
    mkdir -p "$ROOT/k2go/$abi" "$ROOT/r/$abi"/{bin,dev,proc,sys,tmp}
    # aria2c is dynamic (Android linker) and does not run here: it stays out of the image.
    for b in tar gzip xz proot proot-loader rsync nano less; do
        cp "$JNI/$abi/lib$b.so" "$ROOT/k2go/$abi/"
    done
    cp "$WORK/bb-$deb/bin/busybox" "$ROOT/r/$abi/bin/busybox"
    cp "$WORK/probe-$deb" "$ROOT/r/$abi/probe"
    cp "$JNI/$abi/libtar.so" "$ROOT/r/$abi/prog"
done
chmod -R a+rx "$ROOT/k2go" "$ROOT/r"
[ -n "$ABIS" ] || { echo "ERROR: no arm64-v8a/ or armeabi-v7a/ in $JNI" >&2; exit 2; }
(cd "$ROOT" && find . | cpio -o -H newc --quiet | gzip -1) > "$WORK/initrd.gz"

# --- File checks (no kernel needed) ---
RESULTS="$WORK/results"
: > "$RESULTS"
for abi in $ABIS; do
    # ncurses fallback terminals (build_static.sh NCURSES PATCH): nano and less work without terminfo.
    for b in nano less; do
        if grep -q 'xterm-256color' "$JNI/$abi/lib$b.so"; then r=PASS; else r=FAIL; fi
        echo "K2GO-TEST|file|$abi|ncurses-fallback-$b|$r|" >> "$RESULTS"
    done
done

# --- One VM per kernel ---
FAILED=0
for k in $KERNELS; do
    deb="$(fetch "$k")"
    rm -rf "$WORK/k"; mkdir -p "$WORK/k"
    dpkg-deb --fsys-tarfile "$deb" | tar -x -C "$WORK/k" --wildcards './boot/vmlinuz-*'
    vmlinuz="$(ls "$WORK"/k/boot/vmlinuz-*)"
    log="$WORK/vm-$k.log"
    echo ">> kernel $k: booting $(basename "$vmlinuz")"
    timeout 900 qemu-system-aarch64 -M virt -cpu cortex-a57 -m 1024 -smp 2 -nographic -no-reboot \
        -kernel "$vmlinuz" -initrd "$WORK/initrd.gz" \
        -append "console=ttyAMA0 panic=-1 quiet rdinit=/init k2go.kernel=$k" > "$log" 2>&1 || true
    tr -d '\r\000' < "$log" | grep -a '^K2GO-' > "$WORK/lines-$k" || true
    grep -a '^K2GO-KERNEL|' "$WORK/lines-$k" || true
    grep -a '^K2GO-TEST|' "$WORK/lines-$k" >> "$RESULTS" || true
    if ! grep -aq "^K2GO-END|$k" "$WORK/lines-$k"; then
        echo "K2GO-TEST|$k|-|vm-end|FAIL|the VM did not reach its end line (boot failure or hang); last output: $(tail -n 5 "$log" | tr -d '\000' | tr '\n|' '  ')" >> "$RESULTS"
    fi
done

# --- Report ---
printf '%-6s %-12s %-24s %-5s %s\n' KERNEL ABI TEST RESULT DETAIL
while IFS='|' read -r _ kern abi test res detail; do
    printf '%-6s %-12s %-24s %-5s %s\n' "$kern" "$abi" "$test" "$res" "$detail"
    [ "$res" = FAIL ] && FAILED=$((FAILED + 1))
done < "$RESULTS"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo "### Native binaries on real kernels"
        echo
        echo "| Kernel | ABI | Test | Result | Detail |"
        echo "|---|---|---|---|---|"
        while IFS='|' read -r _ kern abi test res detail; do echo "| $kern | $abi | $test | $res | $detail |"; done < "$RESULTS"
    } >> "$GITHUB_STEP_SUMMARY"
fi
echo ">> $(grep -c '|PASS|' "$RESULTS") passed ($(grep -c '|PASS|seccomp off' "$RESULTS") with seccomp off, ADFA-5362), $FAILED failed, $(grep -c '|SKIP|' "$RESULTS") skipped (kernels: $KERNELS)"
[ "$FAILED" -eq 0 ]
