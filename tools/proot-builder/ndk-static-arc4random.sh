#!/bin/bash
# =============================================================================
# ndk-static-arc4random.sh: put static-arc4random.c in the NDK libc.a.
# Author    : AppDevForAll
# Copyright : Copyright (c) 2026 AppDevForAll
#
# Runs in the termux builder container, from the termux-packages root, before
# the first build-package.sh (build_static.sh does it through run-docker.sh).
# It replaces arc4random.o and arc4random_uniform.o in libc.a of both ARM ABIs:
# the NDK r30 arc4random aborts on Linux older than 4.14 (see the C file).
# Termux builds through a fuse-overlayfs view of this NDK folder, and it does
# not change libc.a, so the change shows in every static link. Dynamic
# binaries (aria2c) take the libc.so of the phone and do not use libc.a.
# A second run does nothing.
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
for pair in "aarch64-linux-android:aarch64-linux-android24" "arm-linux-androideabi:armv7a-linux-androideabi24"; do
    lib="$TC/sysroot/usr/lib/${pair%%:*}/libc.a"
    target="${pair##*:}"
    [ -f "$lib" ] || { echo "!! [IIAB] missing $lib"; exit 1; }
    # A variable, not a pipe: with pipefail, grep -q can stop llvm-ar with SIGPIPE.
    members="$("$TC/bin/llvm-ar" t "$lib")"
    if grep -qx "$MEMBER" <<< "$members"; then
        echo ">> [IIAB] $lib already has $MEMBER"
        continue
    fi
    grep -qx arc4random.o <<< "$members" \
        || { echo "!! [IIAB] $lib has no arc4random.o: the NDK changed, check this patch"; exit 1; }
    tmp="$(mktemp -d)"
    new="$lib.iiab-new"
    trap 'rm -rf "$tmp" "$new"' EXIT
    "$TC/bin/clang" --target="$target" -O2 -fPIC -Wall -Werror -c "$SRC" -o "$tmp/$MEMBER"
    # Change a copy in the same folder and rename it at the end (atomic): a stop in the middle
    # leaves libc.a as it was.
    cp "$lib" "$new"
    "$TC/bin/llvm-ar" d "$new" arc4random.o arc4random_uniform.o
    "$TC/bin/llvm-ar" r "$new" "$tmp/$MEMBER"
    # Exactly one definition of each symbol, and it is ours.
    for sym in arc4random arc4random_buf arc4random_uniform arc4random_mutex_lock arc4random_mutex_unlock; do
        n="$("$TC/bin/llvm-nm" -A "$new" 2>/dev/null | grep -cE ":$MEMBER:.* T $sym\$" || true)"
        all="$("$TC/bin/llvm-nm" -A "$new" 2>/dev/null | grep -cE " T $sym\$" || true)"
        [ "$n" = 1 ] && [ "$all" = 1 ] || { echo "!! [IIAB] $lib: $sym defined $all time(s), $n by $MEMBER"; exit 1; }
    done
    mv "$new" "$lib"
    rm -rf "$tmp"
    echo ">> [IIAB] $lib: arc4random.o and arc4random_uniform.o replaced by $MEMBER"
done
