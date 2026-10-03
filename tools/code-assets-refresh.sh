#!/bin/bash
# ============================================================================
# Name        : tools/code-assets-refresh.sh
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : K2GO-437. dash-node-driven refresh of the offline Code on the Go
#               build assets. Re-mirrors the published release set into a staging
#               directory and swaps it in on success, LIVE (box up, no runrole).
#               Writes a status file plus a log that the dash-node endpoint polls.
#               Mirrors tools/code-addons-refresh.sh (status file plus detached run).
#
# Safe: the mirror runs into "<serve>.new"; the live tree is only replaced after a
# clean run, so a failed or cancelled refresh never serves a half-mirror.
# ============================================================================
set -u
STATUS=/var/run/code-assets-refresh.status
LOG=/var/log/code-assets-refresh.log
PID=/var/run/code-assets-refresh.pid

# SERVE must match code_assets_serve_path (roles/code_assets/defaults). The source
# base and the serve-base come from mirror_code_assets.py's own defaults, so they
# are not restated here.
SERVE=/library/www/code-assets
# K2GO-440: prefer the mirror from the self-updating clone, so a mirror fix ships via the dash-node
# self-update (git reset on /opt/iiab-android) with NO rebake; fall back to the copy the overlay
# places in the ansible roles dir at bake. Same idea as forgejo-refresh.sh sourcing from the clone.
# The code_assets role is ours only (a Knowledge to Go overlay, not an IIAB upstream role).
MIRROR_CLONE=/opt/iiab-android/tools/upstream-patches/overlays/roles/code_assets/files/mirror_code_assets.py
MIRROR_BAKED=/opt/iiab/iiab/roles/code_assets/files/mirror_code_assets.py
MIRROR=$([ -f "$MIRROR_CLONE" ] && echo "$MIRROR_CLONE" || echo "$MIRROR_BAKED")

: > "$LOG" 2>/dev/null || true
echo running > "$STATUS" 2>/dev/null || true
# dash-node spawns this with setsid, so this shell leads the process group. Record its
# PID so POST /code-assets/refresh/cancel can stop the whole group (kill -PID) mid-run.
echo $$ > "$PID" 2>/dev/null || true
# Drop the pid file on a normal exit, so a finished run leaves no stale pid for a later
# cancel to kill (a reused pid). A SIGKILL from cancel skips this, but cancel also sets
# the status to 'cancelled', which guards against that.
trap 'rm -f "$PID" 2>/dev/null' EXIT

{
  if [ ! -f "$MIRROR" ]; then
    echo "code-assets-refresh: mirror script not found at $MIRROR"
    echo error > "$STATUS" 2>/dev/null || true
    exit 1
  fi

  STAGE="${SERVE}.new"
  OLD="${SERVE}.old"
  rm -rf "$STAGE" "$OLD" 2>/dev/null || true

  # Mirror into the staging dir. mirror_code_assets.py reads the committed manifest,
  # downloads each file, verifies it against the sibling ".md5", and regenerates the
  # browse page. A non-zero exit means a download or checksum failed.
  # K2GO-437: --reuse-from the live tree so only new or changed files download (reuse by
  # the published ".md5", no re-hash). The mirror prints "result: up-to-date" and builds
  # no staging when nothing changed.
  # Feature-detect the flag: this wrapper lives in /opt/iiab-android and is updated by the
  # dash-node self-rebuild (git reset --hard origin/main), but the mirror ships as an
  # overlay in the ansible rootfs tree and only a full re-bake updates it. So a box can run
  # a NEW wrapper against an OLD, bake-time mirror that does not know --reuse-from. Pass the
  # flag only when the mirror supports it; otherwise degrade to a full mirror. (K2GO-440.)
  REUSE=()
  if [ -d "$SERVE" ] && python3 "$MIRROR" --help 2>/dev/null | grep -q -- '--reuse-from'; then
    REUSE=(--reuse-from "$SERVE")
  fi
  if ! python3 "$MIRROR" --out "$STAGE" "${REUSE[@]}"; then
    echo "code-assets-refresh: mirror failed; live tree left untouched"
    rm -rf "$STAGE" 2>/dev/null || true
    echo error > "$STATUS" 2>/dev/null || true
    exit 1
  fi

  # K2GO-437: nothing changed. The mirror downloaded nothing and built no staging, so
  # there is nothing to swap: keep the live tree and report done.
  if grep -q '^result: up-to-date' "$LOG"; then
    rm -rf "$STAGE" 2>/dev/null || true
    echo "code-assets-refresh: already up to date; kept the served tree"
    echo done > "$STATUS" 2>/dev/null || true
    exit 0
  fi

  # Swap the fresh mirror in. All three paths live under /library/www, so each mv is a
  # same-filesystem rename; the window where <serve> is absent is one rename wide, and
  # nginx serves either the old tree or the new one, never a partial.
  if [ -d "$SERVE" ]; then mv "$SERVE" "$OLD" 2>/dev/null || true; fi
  if mv "$STAGE" "$SERVE" 2>/dev/null; then
    rm -rf "$OLD" 2>/dev/null || true
    echo "code-assets-refresh: swapped in the fresh tree"
    echo done > "$STATUS" 2>/dev/null || true
  else
    # Could not move the new tree into place: restore the previous one.
    [ -d "$OLD" ] && mv "$OLD" "$SERVE" 2>/dev/null || true
    rm -rf "$STAGE" 2>/dev/null || true
    echo "code-assets-refresh: swap failed; restored the previous tree"
    echo error > "$STATUS" 2>/dev/null || true
    exit 1
  fi
} >> "$LOG" 2>&1
