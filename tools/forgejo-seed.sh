#!/bin/bash
# ============================================================================
# Name        : tools/forgejo-seed.sh
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : K2GO-417. dash-node-driven Forgejo seed. Runs the box
#               orchestration (static/forgejo/orchestration -> seed_forgejo)
#               LIVE, while the box is up (no runrole, no box stop), and writes
#               a status file + a log that the dash-node endpoint polls.
#               Mirrors tools/rebuild-dashboard.sh (status + detached run).
#
# The forge (Forgejo) is installed as a conventional role by runrole; this
# script only injects the content: the admin, the org, and the example repos.
# FORGEJO_SEED_REPOS=0 seeds only the admin and org (a usable empty forge);
# any other value also seeds the full example repo set.
# ============================================================================
set -u
STATUS=/var/run/forgejo-seed.status
LOG=/var/log/forgejo-seed.log
ORCH=/opt/iiab-android/static/forgejo/orchestration

: > "$LOG" 2>/dev/null || true
echo running > "$STATUS" 2>/dev/null || true

{
  if [ ! -f "$ORCH" ]; then
    echo "forgejo-seed: orchestration not found at $ORCH"
    echo error > "$STATUS" 2>/dev/null || true
    exit 1
  fi
  # shellcheck disable=SC1090
  . "$ORCH"
  # Default on (full example set). FORGEJO_SEED_REPOS=0 -> admin/org only.
  if [ "${FORGEJO_SEED_REPOS:-1}" = "0" ]; then
    export FORGEJO_REPOS=""
  else
    export FORGEJO_REPOS="$FORGEJO_REPOS_FULL"
  fi
  if seed_forgejo; then
    echo done > "$STATUS" 2>/dev/null || true
  else
    echo error > "$STATUS" 2>/dev/null || true
  fi
} >> "$LOG" 2>&1
