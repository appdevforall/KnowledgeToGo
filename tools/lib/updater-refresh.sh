#!/bin/bash
# ============================================================================
# Name        : tools/lib/updater-refresh.sh
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : K2GO-449. Shared helpers for the dash-node content-refresh wrappers
#               (forgejo / code-addons / code-assets). This file is SOURCED, not run.
#               It holds the session setup every wrapper copied, the clone-or-baked
#               path resolver, and the mirror-into-staging + atomic-swap body that the
#               two mirror wrappers (code-addons, code-assets) shared almost verbatim.
#               A wrapper sources this and passes its own paths.
# ============================================================================

# refresh_begin STATUS LOG PID
# Common session setup for a dash-node refresh run: truncate the log, mark the status
# "running", and record THIS shell's PID. dash-node spawns the wrapper with setsid, so the
# wrapper leads the process group and POST .../refresh/cancel can stop the whole group with
# "kill -PID". The EXIT trap drops the pid on a normal exit, so a finished run leaves no stale
# pid for a later cancel to kill (a reused pid). A SIGKILL from cancel skips the trap, but
# cancel also writes "cancelled", which guards that case.
refresh_begin() {
  local status="$1" log="$2" pid="$3"
  : > "$log" 2>/dev/null || true
  echo running > "$status" 2>/dev/null || true
  echo $$ > "$pid" 2>/dev/null || true
  # shellcheck disable=SC2064  # expand $pid now (at trap-set time), not when the trap fires
  trap "rm -f '$pid' 2>/dev/null" EXIT
}

# resolve_from_clone CLONE BAKED
# Echo the path to use, preferring the self-updating clone (so a fix ships via the dash-node
# self-update, a git reset on /opt/iiab-android, with NO rebake) and falling back to the
# bake-time copy in the ansible rootfs tree. Uses [ -s ] (exists AND non-empty), not [ -f ], so
# a zero-byte clone file never shadows a good baked one (K2GO-440 review).
resolve_from_clone() {
  if [ -s "$1" ]; then echo "$1"; else echo "$2"; fi
}

# mirror_and_swap NAME SERVE MIRROR STATUS LOG
# Re-mirror the published tree into "<serve>.new" and swap it in on success. The swap is a
# same-filesystem rename, so the window where <serve> is absent is one rename wide and nginx
# serves either the old or the new tree, never a partial. A failed or cancelled run leaves the
# live tree untouched. Honors the mirror's --reuse-from when present, and its "result: up-to-date"
# short-circuit. Writes "done" or "error" to STATUS; returns 0 on success/up-to-date, else 1.
# Call it inside the wrapper's "{ ... } >> LOG 2>&1" block so the mirror output lands in LOG.
mirror_and_swap() {
  local name="$1" serve="$2" mirror="$3" status="$4" log="$5"

  if [ ! -s "$mirror" ]; then
    echo "$name: mirror script not found at $mirror"
    echo error > "$status" 2>/dev/null || true
    return 1
  fi

  local stage="${serve}.new" old="${serve}.old"
  rm -rf "$stage" "$old" 2>/dev/null || true

  # Feature-detect --reuse-from: a NEW wrapper (updated via the clone) can run against an OLD,
  # bake-time mirror that does not know the flag, so pass it only when the mirror supports it and
  # otherwise degrade to a full mirror instead of erroring (K2GO-440).
  local reuse=()
  if [ -d "$serve" ] && python3 "$mirror" --help 2>/dev/null | grep -q -- '--reuse-from'; then
    reuse=(--reuse-from "$serve")
  fi
  if ! python3 "$mirror" --out "$stage" "${reuse[@]}"; then
    echo "$name: mirror failed; live tree left untouched"
    rm -rf "$stage" 2>/dev/null || true
    echo error > "$status" 2>/dev/null || true
    return 1
  fi

  # Nothing changed: the mirror downloaded nothing and built no staging, so there is nothing to
  # swap. Keep the live tree and report done.
  if grep -q '^result: up-to-date' "$log"; then
    rm -rf "$stage" 2>/dev/null || true
    echo "$name: already up to date; kept the served tree"
    echo done > "$status" 2>/dev/null || true
    return 0
  fi

  if [ -d "$serve" ]; then mv "$serve" "$old" 2>/dev/null || true; fi
  if mv "$stage" "$serve" 2>/dev/null; then
    rm -rf "$old" 2>/dev/null || true
    echo "$name: swapped in the fresh tree"
    echo done > "$status" 2>/dev/null || true
    return 0
  fi
  # Could not move the new tree into place: restore the previous one.
  [ -d "$old" ] && mv "$old" "$serve" 2>/dev/null || true
  rm -rf "$stage" 2>/dev/null || true
  echo "$name: swap failed; restored the previous tree"
  echo error > "$status" 2>/dev/null || true
  return 1
}
