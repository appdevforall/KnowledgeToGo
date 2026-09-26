#!/bin/bash
# ============================================================================
# Name        : tools/forgejo-refresh.sh
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : K2GO-422. dash-node-driven Forgejo repo refresh. Runs the box
#               orchestration (static/forgejo/orchestration -> refresh_forgejo)
#               LIVE, while the box is up (no runrole, no box stop), and writes
#               a status file + a log that the dash-node endpoint polls.
#               Mirrors tools/forgejo-seed.sh (status + detached run).
#
# Non-destructive: each seeded example repo is fast-forwarded when pristine, or
# merged when the owner committed locally and the merge is clean; a conflict is
# left AS IS and reported. The served branch only moves forward. The refresh
# operates on whatever is already seeded (it skips repos that are absent).
# ============================================================================
set -u
STATUS=/var/run/forgejo-refresh.status
LOG=/var/log/forgejo-refresh.log
PID=/var/run/forgejo-refresh.pid
ORCH=/opt/iiab-android/static/forgejo/orchestration

: > "$LOG" 2>/dev/null || true
echo running > "$STATUS" 2>/dev/null || true
# K2GO-422: dash-node spawns this with setsid, so this shell is the session/process-group leader.
# Record its PID so POST /forgejo/refresh/cancel can stop the whole group (kill -PID) mid-run.
echo $$ > "$PID" 2>/dev/null || true
# Remove the pid file on a normal exit so a finished run leaves no stale pid for cancel to kill (a reused
# pid). A SIGKILL from cancel skips this, but cancel sets status to 'cancelled', which already guards it.
trap 'rm -f "$PID" 2>/dev/null' EXIT

{
  if [ ! -f "$ORCH" ]; then
    echo "forgejo-refresh: orchestration not found at $ORCH"
    echo error > "$STATUS" 2>/dev/null || true
    exit 1
  fi
  # shellcheck disable=SC1090
  . "$ORCH"
  # Refresh the full example set; _fj_refresh_one skips any repo that is not seeded.
  export FORGEJO_REPOS="$FORGEJO_REPOS_FULL"
  if refresh_forgejo; then
    echo done > "$STATUS" 2>/dev/null || true
  else
    echo error > "$STATUS" 2>/dev/null || true
  fi
} >> "$LOG" 2>&1
