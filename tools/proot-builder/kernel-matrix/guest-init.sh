#!/bin/busybox sh
# =============================================================================
# guest-init.sh: /init of one kernel-matrix virtual machine (K2GO-595).
# Author    : AppDevForAll
# Copyright : Copyright (c) 2026 AppDevForAll
#
# Runs the bundle binaries on the real kernel of this VM, for each ABI in /k2go, and prints one
# line per test: K2GO-TEST|<kernel>|<abi>|<test>|PASS|FAIL|SKIP|<detail>. The last line is
# K2GO-END. run-kernel-matrix.sh builds the initramfs and reads the lines. Each test names the
# build_static.sh customization it covers. proot runs with the options of the app (PRootEngine)
# and its seccomp fallback (ADFA-5362).
# =============================================================================
/bin/busybox --install -s /bin
mount -t proc proc /proc
mount -t sysfs sys /sys
mount -t devtmpfs dev /dev 2>/dev/null
mount -t tmpfs tmp /tmp

KNAME="$(sed -n 's/.*k2go\.kernel=\([^ ]*\).*/\1/p' /proc/cmdline)"
REL="$(uname -r)"
# 3.16 -> 3016, 4.4 -> 4004, 6.12 -> 6012
KV="$(echo "$REL" | awk -F'[.-]' '{print $1 * 1000 + $2}')"
echo "K2GO-KERNEL|$KNAME|$REL|$(uname -m)"

say() { echo "K2GO-TEST|$KNAME|$1|$2|$3|$4"; }
check() { # abi test detail-on-fail command...
    local abi="$1" t="$2" d="$3"; shift 3
    if "$@" >/tmp/out 2>&1; then say "$abi" "$t" PASS ""; else say "$abi" "$t" FAIL "$d: $(head -c 200 /tmp/out | tr '\n|' '  ')"; fi
}
# A proot test, with the seccomp fallback of the app (ADFA-5362, PRootEngine + SeccompFailure):
# when proot aborts with one of its seccomp markers, the app remembers PROOT_NO_SECCOMP=1 for the
# next launches. Here the same ABI keeps it for its next tests, and the failed test runs again.
# A PASS with seccomp off says so in its detail. Any other failure stays a FAIL. The markers come
# from SeccompFailure.java (run-kernel-matrix.sh writes them to /k2go/seccomp-markers).
SECCOMP_NOTE="seccomp off: the app fallback (ADFA-5362)"
seccomp_abort() {
    grep -q -F -f /k2go/seccomp-markers /tmp/pout
}
ptest() { # abi test detail-on-fail command... (the command prints its failure detail)
    local abi="$1" t="$2" d="$3" note=""; shift 3
    [ -n "$PROOT_NO_SECCOMP" ] && note="$SECCOMP_NOTE"
    if "$@" >/tmp/pout 2>&1; then say "$abi" "$t" PASS "$note"; return; fi
    if [ -z "$PROOT_NO_SECCOMP" ] && seccomp_abort; then
        export PROOT_NO_SECCOMP=1
        note="$SECCOMP_NOTE, after: $(grep -m 1 -F -f /k2go/seccomp-markers /tmp/pout | head -c 80 | tr '|' ' ')"
        if "$@" >/tmp/pout 2>&1; then say "$abi" "$t" PASS "$note"; return; fi
    fi
    say "$abi" "$t" FAIL "$d: $(head -c 200 /tmp/pout | tr '\n|' '  ')${note:+ ($note)}"
}

for abi in arm64-v8a armeabi-v7a; do
    B="/k2go/$abi"
    R="/r/$abi"
    [ -d "$B" ] || continue
    W="/tmp/w-$abi"
    mkdir -p "$W/src/sub" "/tmp/pt-$abi"
    cd "$W" || exit 1
    head -c 1000000 /dev/urandom > src/sub/b.bin
    echo hello > src/a.txt
    chmod 640 src/a.txt
    ln -s a.txt src/link
    export PROOT_LOADER="$B/libproot-loader.so" PROOT_TMP_DIR="/tmp/pt-$abi"
    # Each ABI starts with seccomp on, as a new install of the app does.
    unset PROOT_NO_SECCOMP
    # The binds matter: before Linux 3.17 there is no getrandom, and the RNG reads /dev/urandom.
    PROOT="$B/libproot.so --sysvipc -0 --link2symlink --kill-on-exit -b /dev -b /proc -b /sys"

    # Static start (K2GO-594: the NDK r30 arc4random aborted here on Linux older than 4.14).
    for b in tar gzip xz proot rsync nano less; do
        out="$("$B/lib$b.so" --version 2>&1 </dev/null)"; rc=$?
        if [ "$rc" -eq 0 ]; then say "$abi" "start-$b" PASS ""
        else say "$abi" "start-$b" FAIL "exit=$rc $(echo "$out" | head -n 1)"; fi
    done
    say "$abi" "start-aria2c" SKIP "dynamic: needs the Android linker (device check)"

    # The extraction path of the app (gzip | tar -xvf - -C, TarExtractor). The app is not root, so
    # tar does not set owners; --no-same-owner does the same here, where the VM runs as root.
    TARX="$B/libtar.so -xvf - --no-same-owner"
    check "$abi" gzip-tar-roundtrip "round-trip" sh -c "$B/libtar.so -cf a.tar -C src . && $B/libgzip.so -c a.tar > a.tgz && mkdir o1 && $B/libgzip.so -dc a.tgz | $TARX -C o1 >/dev/null && cmp src/sub/b.bin o1/sub/b.bin && [ \"\$(readlink o1/link)\" = a.txt ]"
    check "$abi" xz-tar-roundtrip "round-trip" sh -c "$B/libxz.so -z -c a.tar > a.txz && mkdir o2 && $B/libxz.so -dc a.txz | $TARX -C o2 >/dev/null && cmp src/sub/b.bin o2/sub/b.bin && [ \"\$(readlink o2/link)\" = a.txt ]"
    check "$abi" rsync-copy "copy" sh -c "$B/librsync.so -a src/ o3/ && cmp src/sub/b.bin o3/sub/b.bin && [ \"\$(readlink o3/link)\" = a.txt ] && [ \"\$(stat -c %a o3/a.txt)\" = 640 ]"

    # proot launches a program in a rootfs (static libtalloc, liblog stubs: K2GO-544).
    ptest "$abi" proot-launch "launch" sh -c "$PROOT -r $R -w / /prog --version | grep -q tar"
    # A shell in proot forks and waits: every box service does this.
    ptest "$abi" proot-fork "fork in proot" sh -c "$PROOT -r $R -w / /bin/busybox sh -c '/bin/busybox true & wait; echo forked' | grep -q forked"

    # fchmodat2 (ADFA-5334): proot translates its path like fchmodat. It does not change the call,
    # so before Linux 6.6 the kernel answers ENOSYS in proot too (glibc then uses fchmodat). With
    # 6.6 or later the call must change the file in the rootfs, not the file of the host.
    if [ "$KV" -ge 6006 ]; then want=0; else want=38; fi
    "/r/$abi/probe" fchmodat2 "$W/src/a.txt" >/tmp/out 2>&1; rc=$?
    [ "$rc" -eq "$want" ] && say "$abi" fchmodat2-native PASS "kernel result $rc as expected" \
        || say "$abi" fchmodat2-native FAIL "probe exit $rc, expected $want: $(cat /tmp/out)"
    fchmodat2_in_proot() {
        touch "$R/f" /f; chmod 600 "$R/f" /f
        $PROOT -r "$R" -w / /probe fchmodat2 /f 2>&1; rc=$?
        if [ "$rc" -ne "$want" ]; then
            echo "probe exit $rc, expected $want"; return 1
        elif [ "$rc" -eq 0 ] && { [ "$(stat -c %a "$R/f")" != 640 ] || [ "$(stat -c %a /f)" != 600 ]; }; then
            echo "path not translated: rootfs mode $(stat -c %a "$R/f"), host mode $(stat -c %a /f)"; return 1
        fi
    }
    ptest "$abi" fchmodat2-in-proot "fchmodat2" fchmodat2_in_proot
    rm -f /f

    # futex_time64 (K2GO-411): 32-bit Go needs it; proot turns it into futex before Linux 5.1.
    if [ "$abi" = armeabi-v7a ]; then
        "/r/$abi/probe" futex_time64 >/tmp/out 2>&1; rc=$?
        if [ "$KV" -ge 5001 ]; then want=0; else want=38; fi
        [ "$rc" -eq "$want" ] && say "$abi" futex_time64-native PASS "kernel result $rc as expected" \
            || say "$abi" futex_time64-native FAIL "probe exit $rc, expected $want: $(cat /tmp/out)"
        ptest "$abi" futex_time64-in-proot "probe in proot" $PROOT -r "$R" -w / /probe futex_time64
    fi

    # PTRACE_O_EXITKILL (K2GO-544, K2GO-550): a daemon in proot dies when proot is SIGKILLed.
    # Every kernel here is 3.8 or newer, so proot must ask for it.
    proot_exitkill() {
        rm -f "$R/d.pid"
        $PROOT -r "$R" -w / /bin/busybox sh -c \
            "/bin/busybox setsid /bin/busybox sh -c 'echo \$\$ > /d.pid; exec /bin/busybox sleep 300' & exec /bin/busybox sleep 300" \
            >/tmp/ek.log 2>&1 &
        p=$!
        i=0; while [ ! -s "$R/d.pid" ] && [ "$i" -lt 200 ]; do sleep 0.1; i=$((i + 1)); done
        d="$(cat "$R/d.pid" 2>/dev/null)"
        if [ -z "$d" ] || [ ! -d "/proc/$d" ]; then
            kill -9 "$p" 2>/dev/null
            echo "the daemon did not start: $(tail -n 3 /tmp/ek.log)"
            return 1
        fi
        kill -9 "$p"
        # The emulated CPU is slow: give the kernel up to 5 s to kill the tracee. Gone or zombie = dead.
        i=0; while [ "$i" -lt 50 ]; do
            st="$(sed -n 's/^State:[[:space:]]*//p' "/proc/$d/status" 2>/dev/null)"
            case "$st" in ""|Z*) return 0 ;; esac
            sleep 0.1; i=$((i + 1))
        done
        kill -9 "$d"
        echo "daemon $d survived proot (State $st)"
        return 1
    }
    ptest "$abi" proot-exitkill "exitkill" proot_exitkill
    cd /
done

echo "K2GO-END|$KNAME"
poweroff -f
