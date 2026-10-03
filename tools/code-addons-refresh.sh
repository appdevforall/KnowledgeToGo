#!/bin/bash
# ============================================================================
# Name        : tools/code-addons-refresh.sh
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : K2GO-99. dash-node-driven refresh of the offline Code on the Go
#               add-ons gallery. Re-mirrors the published gallery into a staging
#               directory and swaps it in on success, LIVE (box up, no runrole).
#               Writes a status file plus a log that the dash-node endpoint polls.
#               Mirrors tools/forgejo-refresh.sh (status file plus detached run).
#
# Safe: the mirror runs into "<serve>.new"; the live gallery is only replaced
# after a clean run, so a failed or cancelled refresh never serves a half-mirror.
# ============================================================================
set -u
STATUS=/var/run/code-addons-refresh.status
LOG=/var/log/code-addons-refresh.log
PID=/var/run/code-addons-refresh.pid

# SERVE must match code_addons_serve_path (roles/code_addons/defaults). The source
# base and the catalog serve-base come from mirror_addons.py's own defaults, so
# they are not restated here.
SERVE=/library/www/code-addons
# K2GO-440: prefer the mirror from the self-updating clone, so a mirror fix ships via the dash-node
# self-update (git reset on /opt/iiab-android) with NO rebake; fall back to the copy the overlay
# places in the ansible roles dir at bake. Same idea as forgejo-refresh.sh sourcing from the clone.
# The code_addons role is ours only (a Knowledge to Go overlay, not an IIAB upstream role).
MIRROR_CLONE=/opt/iiab-android/tools/upstream-patches/overlays/roles/code_addons/files/mirror_addons.py
MIRROR_BAKED=/opt/iiab/iiab/roles/code_addons/files/mirror_addons.py
MIRROR=$([ -f "$MIRROR_CLONE" ] && echo "$MIRROR_CLONE" || echo "$MIRROR_BAKED")

: > "$LOG" 2>/dev/null || true
echo running > "$STATUS" 2>/dev/null || true
# dash-node spawns this with setsid, so this shell leads the process group. Record its
# PID so POST /addons/refresh/cancel can stop the whole group (kill -PID) mid-run.
echo $$ > "$PID" 2>/dev/null || true
# Drop the pid file on a normal exit, so a finished run leaves no stale pid for a later
# cancel to kill (a reused pid). A SIGKILL from cancel skips this, but cancel also sets
# the status to 'cancelled', which guards against that.
trap 'rm -f "$PID" 2>/dev/null' EXIT

{
  if [ ! -f "$MIRROR" ]; then
    echo "code-addons-refresh: mirror script not found at $MIRROR"
    echo error > "$STATUS" 2>/dev/null || true
    exit 1
  fi

  STAGE="${SERVE}.new"
  OLD="${SERVE}.old"
  rm -rf "$STAGE" "$OLD" 2>/dev/null || true

  # Mirror into the staging dir. mirror_addons.py follows references from index.html and
  # v1/catalog.json, verifies each .cgp by sha256, strips the Cloudflare injections, and
  # rewrites the catalog base. A non-zero exit means a download or checksum failed.
  # K2GO-441: --reuse-from the live tree so only new or changed files download (catalog-driven:
  # reuse by the catalog sha256, and shell assets by their content-hashed name). The mirror prints
  # "result: up-to-date" and builds no staging when the published catalog is byte-identical.
  # Feature-detect the flag: this wrapper lives in /opt/iiab-android and is updated by the
  # dash-node self-rebuild (git reset --hard origin/main), but the mirror lives in the ansible
  # rootfs tree and only a full re-bake updates it. So a box can run a NEW wrapper against an
  # OLD, bake-time mirror that does not know --reuse-from. Pass the flag only when the mirror
  # supports it; otherwise degrade to a full mirror instead of erroring. (K2GO-440 unifies this.)
  REUSE=()
  if [ -d "$SERVE" ] && python3 "$MIRROR" --help 2>/dev/null | grep -q -- '--reuse-from'; then
    REUSE=(--reuse-from "$SERVE")
  fi
  if ! python3 "$MIRROR" --out "$STAGE" "${REUSE[@]}"; then
    echo "code-addons-refresh: mirror failed; live gallery left untouched"
    rm -rf "$STAGE" 2>/dev/null || true
    echo error > "$STATUS" 2>/dev/null || true
    exit 1
  fi

  # K2GO-441: nothing changed. The mirror downloaded nothing and built no staging, so
  # there is nothing to swap: keep the live gallery and report done.
  if grep -q '^result: up-to-date' "$LOG"; then
    rm -rf "$STAGE" 2>/dev/null || true
    echo "code-addons-refresh: already up to date; kept the served gallery"
    echo done > "$STATUS" 2>/dev/null || true
    exit 0
  fi

  # Swap the fresh mirror in. All three paths live under /library/www, so each mv is a
  # same-filesystem rename; the window where <serve> is absent is one rename wide, and
  # nginx serves either the old tree or the new one, never a partial.
  if [ -d "$SERVE" ]; then mv "$SERVE" "$OLD" 2>/dev/null || true; fi
  if mv "$STAGE" "$SERVE" 2>/dev/null; then
    rm -rf "$OLD" 2>/dev/null || true
    echo "code-addons-refresh: swapped in the fresh gallery"
    echo done > "$STATUS" 2>/dev/null || true
  else
    # Could not move the new tree into place: restore the previous one.
    [ -d "$OLD" ] && mv "$OLD" "$SERVE" 2>/dev/null || true
    rm -rf "$STAGE" 2>/dev/null || true
    echo "code-addons-refresh: swap failed; restored the previous gallery"
    echo error > "$STATUS" 2>/dev/null || true
    exit 1
  fi
} >> "$LOG" 2>&1
