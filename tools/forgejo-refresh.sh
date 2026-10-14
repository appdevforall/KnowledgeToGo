#!/bin/bash
# ============================================================================
# Name        : tools/forgejo-refresh.sh
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : K2GO-422 / K2GO-449. dash-node-driven Forgejo repo refresh. Runs the box
#               orchestration (static/forgejo/orchestration -> refresh_forgejo) LIVE, while the
#               box is up (no runrole, no box stop). The session setup (status/log/pid + the
#               cancel trap) lives in tools/lib/updater-refresh.sh; the git refresh body stays
#               here (forgejo is git-based, not a mirror-into-staging like code-addons/assets).
#
# Non-destructive: each seeded example repo is fast-forwarded when pristine, or merged when the
# owner committed locally and the merge is clean; a conflict is left AS IS and reported. The
# served branch only moves forward. The refresh operates on whatever is already seeded.
# ============================================================================
set -u
STATUS=/var/run/forgejo-refresh.status
LOG=/var/log/forgejo-refresh.log
PID=/var/run/forgejo-refresh.pid
ORCH=/opt/iiab-android/static/forgejo/orchestration
# Source the shared lib. Define STATUS first so a missing lib still reports 'error' to dash-node.
. "$(dirname "${BASH_SOURCE[0]}")/lib/updater-refresh.sh" || { echo error > "$STATUS" 2>/dev/null; echo "forgejo-refresh: shared lib missing" >&2; exit 1; }

refresh_begin "$STATUS" "$LOG" "$PID"
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
