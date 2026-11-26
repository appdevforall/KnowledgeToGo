#!/bin/bash
# =============================================================================
# ndk-static-arc4random.sh: put static-arc4random.c in the NDK libc.a.
# Author    : AppDevForAll
# Copyright : Copyright (c) 2026 AppDevForAll
#
# Runs in the termux builder container, from the termux-packages root, before
# the first build-package.sh (build_static.sh does it through run-docker.sh).
# The NDK r30 arc4random aborts on Linux older than 4.14 (see the C file), so
# libc.a of both ARM ABIs must have static-arc4random.o in place of
# arc4random.o and arc4random_uniform.o.
#
# The restricted AppArmor profile of run-docker.sh denies writes to
# /home/builder/lib (the NDK). So this script does not change the NDK:
#   build <dir>  writes each changed libc.a to <dir>, a folder below output/
#                (the profile allows writes there), and <dir>/install.list
#                with one "<file> <NDK libc.a>" line for each. The host copies
#                the files in with docker cp. A libc.a that already has
#                static-arc4random.o gets no line.
#   check        stops with an error unless each NDK libc.a has the
#                arc4random family of static-arc4random.o only.
# Termux builds through a fuse-overlayfs view of this NDK folder, and it does
# not change libc.a, so the change shows in every static link. Dynamic
# binaries (aria2c) take the libc.so of the phone and do not use libc.a.
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/static-arc4random.c"
MEMBER="static-arc4random.o"
# NDK: termux properties.sh gives its path (it holds the NDK version of the pinned commit).
set +u
. ./scripts/properties.sh
set -u
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -x "$TC/bin/clang" ] || { echo "!! [IIAB] no NDK toolchain at $TC"; exit 1; }
echo ">> [IIAB] NDK: $(grep -m1 'Pkg.Revision' "$NDK/source.properties")"

# libc.a folder : clang target (API 24 = the minSdk; termux builds at the same level).
PAIRS=("aarch64-linux-android:aarch64-linux-android24" "arm-linux-androideabi:armv7a-linux-androideabi24")

# Exactly one definition of each symbol, and it is ours.
verify() {
    local a="$1" nm sym n all
    nm="$("$TC/bin/llvm-nm" -A "$a" 2>/dev/null || true)"
    for sym in arc4random arc4random_buf arc4random_uniform arc4random_mutex_lock arc4random_mutex_unlock; do
        n="$(grep -cE ":$MEMBER:.* T $sym\$" <<< "$nm" || true)"
        all="$(grep -cE " T $sym\$" <<< "$nm" || true)"
        [ "$n" = 1 ] && [ "$all" = 1 ] || { echo "!! [IIAB] $a: $sym defined $all time(s), $n by $MEMBER"; return 1; }
    done
}

case "${1:-}" in
build)
    OUT="${2:?usage: $0 build <dir below output/>}"
    rm -rf "$OUT"
    mkdir -p "$OUT"
    : > "$OUT/install.list"
    for pair in "${PAIRS[@]}"; do
        abi="${pair%%:*}"
        lib="$TC/sysroot/usr/lib/$abi/libc.a"
        [ -f "$lib" ] || { echo "!! [IIAB] missing $lib"; exit 1; }
        # A variable, not a pipe: with pipefail, grep -q can stop llvm-ar with SIGPIPE.
        # docker cp writes in place: a stop during the copy leaves a broken libc.a in the container.
        members="$("$TC/bin/llvm-ar" t "$lib")" \
            || { echo "!! [IIAB] $lib is not a valid archive: remove the builder container and run again"; exit 1; }
        if grep -qx "$MEMBER" <<< "$members"; then
            echo ">> [IIAB] $lib already has $MEMBER"
            continue
        fi
        grep -qx arc4random.o <<< "$members" \
            || { echo "!! [IIAB] $lib has no arc4random.o: the NDK changed, check this patch"; exit 1; }
        mkdir -p "$OUT/$abi"
        "$TC/bin/clang" --target="${pair##*:}" -O2 -fPIC -Wall -Werror -c "$SRC" -o "$OUT/$abi/$MEMBER"
        cp "$lib" "$OUT/$abi/libc.a"
        "$TC/bin/llvm-ar" d "$OUT/$abi/libc.a" arc4random.o arc4random_uniform.o
        "$TC/bin/llvm-ar" r "$OUT/$abi/libc.a" "$OUT/$abi/$MEMBER"
        verify "$OUT/$abi/libc.a"
        echo "$abi/libc.a $lib" >> "$OUT/install.list"
        echo ">> [IIAB] $OUT/$abi/libc.a: ready for $lib"
    done
    ;;
check)
    for pair in "${PAIRS[@]}"; do
        lib="$TC/sysroot/usr/lib/${pair%%:*}/libc.a"
        verify "$lib"
        echo ">> [IIAB] $lib: arc4random family from $MEMBER"
    done
    ;;
*)
    echo "usage: $0 build <dir below output/> | check" >&2
    exit 2
    ;;
esac
