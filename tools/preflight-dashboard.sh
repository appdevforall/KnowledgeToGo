#!/bin/sh
# tools/preflight-dashboard.sh [CLONE_DIR] [BRANCH] — ADFA-5011
#
# Step 1 of the app-orchestrated dash-node rebuild (see ADR-5011). The APP runs this INSIDE the proot
# and reads its output to decide go/no-go BEFORE anything is changed. It is strictly NON-DESTRUCTIVE:
# the only network/state touch is `git fetch` (updates remote-tracking refs; never the working tree,
# never a build, never a restart). Idempotent and version-independent — safe to run on any installed
# dash-node (1.0.1, 1.1, ...).
#
# Output: human "[preflight] ..." lines for the log, then ONE machine-readable line the app parses:
#   PREFLIGHT_RESULT={"ok":true|false,"installed":"x","available":"y","update_available":bool,"reasons":[...]}
# Exit 0 = safe to proceed to the rebuild; non-zero = do NOT proceed.
#
#   sh tools/preflight-dashboard.sh /opt/iiab-android main
set -u

CLONE_DIR="${1:-/opt/iiab-android}"
BRANCH="${2:-main}"
LIVE="/library/dashboard"
MIN_FREE_MB="${K2GO_REBUILD_MIN_FREE_MB:-800}"

ok=1
reasons=""
add_reason() { reasons="${reasons:+$reasons|}$1"; ok=0; }
say() { echo "[preflight] $*"; }

# Extract "version" from a package.json fed on stdin (no jq dependency).
pkg_version() {
    grep '"version"' | head -1 | sed 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/'
}

is_repo=0
if git -C "$CLONE_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    is_repo=1
else
    add_reason "no_git_repo"; say "FAIL: $CLONE_DIR is not a git repo"
fi

# Required tools (the surgeon needs these present in the proot).
for t in git node yarn tar; do
    command -v "$t" >/dev/null 2>&1 || { add_reason "missing_tool:$t"; say "FAIL: missing tool: $t"; }
done
{ command -v pdsm >/dev/null 2>&1 || [ -x /usr/local/bin/pdsm ]; } || { add_reason "missing_tool:pdsm"; say "FAIL: missing tool: pdsm"; }

# The live install must exist (we never create it here).
[ -d "$LIVE" ] || { add_reason "no_live_dashboard"; say "FAIL: $LIVE not found (dashboard not installed?)"; }

if [ "$is_repo" -eq 1 ]; then
    # Clean working tree — a rebuild does `git reset --hard`, so refuse if the user has local edits.
    if [ -n "$(git -C "$CLONE_DIR" status --porcelain 2>/dev/null)" ]; then
        add_reason "dirty_worktree"; say "FAIL: local modifications in $CLONE_DIR (would be discarded)"
    fi
    cur=$(git -C "$CLONE_DIR" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "?")
    [ "$cur" = "$BRANCH" ] || say "note: on branch '$cur' (rebuild will switch to '$BRANCH')"
    # Network + remote reachable: fetch is non-destructive (updates refs only).
    if git -C "$CLONE_DIR" fetch origin "$BRANCH" >/dev/null 2>&1; then
        say "fetch OK (origin/$BRANCH)"
    else
        add_reason "fetch_failed"; say "FAIL: git fetch origin $BRANCH (offline or unreachable?)"
    fi
fi

# Disk headroom for a staging build.
free_mb=$(df -Pm "$LIVE" 2>/dev/null | awk 'NR==2 {print $4}')
if [ -n "$free_mb" ] && [ "$free_mb" -ge "$MIN_FREE_MB" ]; then
    say "disk OK (${free_mb}MB free)"
else
    add_reason "low_disk:${free_mb:-unknown}"; say "FAIL: low disk (${free_mb:-?}MB < ${MIN_FREE_MB}MB)"
fi

# Versions: installed (on box) vs available (tip of origin/BRANCH).
installed="unknown"
[ -f "$LIVE/package.json" ] && installed=$(pkg_version < "$LIVE/package.json")
[ -n "$installed" ] || installed="unknown"
available="unknown"
if [ "$is_repo" -eq 1 ]; then
    v=$(git -C "$CLONE_DIR" show "origin/$BRANCH:static/dashboard/package.json" 2>/dev/null | pkg_version)
    [ -n "$v" ] && available="$v"
fi
update_available=false
[ "$available" != "unknown" ] && [ "$installed" != "$available" ] && update_available=true
say "installed=$installed available=$available update_available=$update_available"

# Machine-readable verdict (single line the app greps for).
[ "$ok" -eq 1 ] && okj=true || okj=false
rj=""
if [ -n "$reasons" ]; then
    oldIFS=$IFS; IFS='|'
    for r in $reasons; do rj="${rj:+$rj,}\"$r\""; done
    IFS=$oldIFS
fi
echo "PREFLIGHT_RESULT={\"ok\":$okj,\"installed\":\"$installed\",\"available\":\"$available\",\"update_available\":$update_available,\"reasons\":[$rj]}"

[ "$ok" -eq 1 ]
