#!/bin/sh
# tools/rebuild-dashboard.sh <BRANCH> [CLONE_DIR] : ADFA-5011 / ADFA-5051
#
# Rebuild ONLY the dash-node REST API from the on-device clone, without a rootfs rebuild.
# Blue-green + verify-before-commit so the live API is never left in a broken or misreporting state:
#
#   1. git fetch + reset --hard origin/<branch>      (deterministic; the box clone isn't edited)
#   2. build in a STAGING dir (yarn install + build)
#   3. smoke-test the STAGED build on a temp port     (tools/dashboard-smoketest.sh)
#   4. only if it passes: back up live dist, sync node_modules, atomically swap the dist in, restart
#      dash-node, and verify LIVE
#   5a. live OK  -> FINALIZE: only now advance source + package.json + nginx vhost to match the running
#       dist (so the reported version can never get ahead of the code), reload nginx, done.
#   5b. live FAIL -> roll the dist back, restart, and RE-VERIFY the rollback recovered. Because source +
#       package.json were never advanced (5a), a failed update leaves NO version/source drift.
#
# ADFA-5051 also hardens: proot ".l2s." symlink-loop artifacts no longer break the node_modules copy or
# the staging cleanup (see purge_staging), and the backup is cleaned on every path.
#
# If step 1-3 fail, the LIVE dashboard is never touched. Launched DETACHED (setsid) by
# POST /api/system/dashboard/rebuild, so the `pdsm restart dash-node` in step 4 cannot kill
# this script. Single-flight via a lock dir; progress in $LOG; state in $STATUS for the app.
set -u

# Simpler invocation: BRANCH is the value that changes, so it's $1 (env K2GO_BRANCH still works, which
# is how the app's detached REST call passes it). CLONE_DIR is the optional $2 — the install location is
# almost always the same, so it defaults; pass it only if it moved.
#   sh tools/rebuild-dashboard.sh <branch> [clone_dir]
usage() {
  cat <<'USAGE'
rebuild-dashboard.sh — rebuild ONLY the dash-node REST API from the on-device clone, no rootfs
rebuild. Blue-green + verify-before-commit (ADFA-5011).

Usage: sh rebuild-dashboard.sh <BRANCH> [CLONE_DIR]
  BRANCH     REQUIRED: git branch to fetch + reset --hard from origin ($1 or env K2GO_BRANCH; no default)
  CLONE_DIR  clone location to build from       (default: /opt/iiab-android)

Run inside the proot box. Fetches origin/<BRANCH>, builds in a staging dir, smoke-tests it,
atomically swaps the dist in, restarts dash-node, and verifies live (rolls back on failure).
USAGE
}
case "${1:-}" in -h|--help) usage; exit 0 ;; esac

# Safety (K2GO): the branch is REQUIRED. This rebuild runs `git reset --hard origin/<branch>`, so a bare
# run with no branch used to fall back to main and reset + rebuild to it, which has wrecked a working
# dashboard. With neither arg nor K2GO_BRANCH set, do NOTHING: print a ready-to-paste command and exit.
# The app always passes K2GO_BRANCH, so its rebuild flow is unchanged; only a hand-run with no branch stops.
CLONE_DIR="${2:-/opt/iiab-android}"
# Absolute path to THIS script, so the suggested command pastes and runs from any directory.
SELF="$(cd "$(dirname "$0")" 2>/dev/null && pwd)/$(basename "$0")"
BRANCH="${1:-${K2GO_BRANCH:-}}"
if [ -z "$BRANCH" ]; then
  # Suggest the branch the clone currently has checked out (that is almost always the one to rebuild).
  guess="$(git -C "$CLONE_DIR" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  if [ -z "$guess" ] || [ "$guess" = "HEAD" ]; then guess="<branch>"; fi
  {
    echo "ERROR: no branch given. This rebuild runs 'git reset --hard origin/<branch>', so a branch is"
    echo "required. Nothing was changed. Copy the line below (full path, ready to paste):"
    echo
    echo "sh $SELF $guess"
    echo
    echo "clone_dir is an optional 2nd arg (default: /opt/iiab-android)."
  } >&2
  exit 2
fi
SRC="$CLONE_DIR/static/dashboard"
LIVE="/library/dashboard"
STAGE="/library/dashboard.staging"
BACKUP="/library/dashboard.dist.bak"
NGINX_CONF_DIR="/etc/nginx/conf.d"
TEST_PORT="${K2GO_REBUILD_TEST_PORT:-4010}"
# K2GO-557: the staged 2nd node is cold and, on a low-RAM 32-bit device, needs more than the old
# blind `sleep 3` to boot (measured: ~11s to first 200 on a 1.4 GB arm32 Q8). Poll up to this long.
STAGE_SMOKE_SECS="${K2GO_REBUILD_STAGE_TIMEOUT:-45}"
# The app runs the newest scripts from a temp dir (extracted via `git show`), so it can point us at
# the matching smoke test there; falls back to the clone's copy for a manual/dev run.
SMOKE="${K2GO_SMOKE:-$CLONE_DIR/tools/dashboard-smoketest.sh}"

STATUS="/var/run/dash-rebuild.status"
LOG="/var/log/dash-rebuild.log"
LOCK="/var/run/dash-rebuild.lock"
# ADFA-5333: coarse phase for a safe cancel. "building" (steps 1-3) never touches the live dashboard, so
# a cancel there just kills this run and purges staging; "promoting" (step 4: dist swap + restart) is the
# short, non-cancelable window — the cancel endpoint refuses while it is set. PID lets the endpoint signal
# this whole detached session (setsid group) to stop.
PHASE="/var/run/dash-rebuild.phase"
PIDFILE="/var/run/dash-rebuild.pid"

# ADFA-4893: also echo to the console so a manual foreground run shows progress live (not just the
# log file). Harmless when launched detached (POST .../rebuild) — stdout goes nowhere then.
log() { _m="[$(date '+%Y-%m-%d %H:%M:%S')] $*"; echo "$_m" >> "$LOG" 2>/dev/null; echo "$_m"; }
set_status() { echo "$1" > "$STATUS" 2>/dev/null || true; }
set_phase() { echo "$1" > "$PHASE" 2>/dev/null || true; }
# ADFA-5051: proot renders some native-build symlinks (e.g. in better-sqlite3/build) as ".l2s." loops
# that `rm -rf` can't recurse into (ELOOP -> "Directory not empty"), but a direct unlink removes them.
# Sweep them first, then remove the tree — so staging is always cleanly removable.
purge_staging() {
    [ -d "$STAGE" ] || return 0
    find "$STAGE" -name '*.l2s.*' -exec rm -f {} + 2>/dev/null || true
    rm -rf "$STAGE" 2>/dev/null || true
}
# ADFA-5051 + K2GO-557: ONE poll helper for BOTH the staged and the live smoke check. Was two rules
# for the same thing: verify_live polled ~30s, but the staged gate was a blind `sleep 3`, so a cold
# 2nd node on a slow/low-RAM device false-failed. $1 = base url, $2 = max seconds (~2s per try).
poll_smoke() {
    _deadline=$(( $(date +%s) + ${2:-30} ))
    while :; do
        if sh "$SMOKE" "$1" >>"$LOG" 2>&1; then return 0; fi
        [ "$(date +%s)" -ge "$_deadline" ] && return 1
        sleep 2
    done
}
verify_live() { poll_smoke "http://127.0.0.1:4000/api" 30; }
cleanup() { [ -n "${TESTPID:-}" ] && kill "$TESTPID" 2>/dev/null || true; purge_staging; rmdir "$LOCK" 2>/dev/null || true; rm -f "$PHASE" "$PIDFILE" 2>/dev/null || true; }
fail() { log "FAIL: $*"; set_status "error"; cleanup; exit 1; }
# ADFA-5333: a cancel (SIGTERM/INT during the building phase) self-heals the state — set the status to
# idle BEFORE cleanup so a killed build can never leave a wedged "running" (the endpoint also writes idle,
# but this makes it true regardless of whether the endpoint completes). Ignored during promote (trapped off).
on_cancel() { log "canceled"; set_status "idle"; cleanup; exit 0; }

# Single-flight: mkdir is atomic.
mkdir "$LOCK" 2>/dev/null || { echo "another rebuild is running" >&2; exit 3; }
# ADFA-5333: clean staging + phase/pid on ANY exit, including a cancel signal (TERM/INT). Killing this
# session during steps 1-3 therefore leaves nothing behind and the live dashboard untouched.
trap cleanup EXIT
trap on_cancel INT TERM
: > "$LOG" 2>/dev/null || true
set_status "running"
set_phase "building"
# ADFA-5333: record THIS shell's pid. Under setsid it is the session/group leader, so the cancel endpoint
# can signal the whole group (this script + yarn/node/tar children) with kill(-pid).
echo $$ > "$PIDFILE" 2>/dev/null || true
log "rebuild start (branch=$BRANCH, clone=$CLONE_DIR)"

[ -d "$SRC" ]  || fail "source $SRC not found"
[ -d "$LIVE" ] || fail "live $LIVE not found (dashboard not installed?)"
[ -f "$SMOKE" ] || fail "smoke test $SMOKE not found"

# 1) refresh the clone to the tip of the branch (deterministic; discards any local drift).
log "git fetch + reset --hard origin/$BRANCH"
git -C "$CLONE_DIR" fetch origin "$BRANCH" >>"$LOG" 2>&1 || fail "git fetch (offline?)"
git -C "$CLONE_DIR" reset --hard "origin/$BRANCH" >>"$LOG" 2>&1 || fail "git reset"
# K2GO: name the exact commit being built, so the operator can confirm it is the intended one.
log "building commit $(git -C "$CLONE_DIR" log -1 --format='%h %s' 2>/dev/null)"

# 2) build in staging. Reuse live node_modules to speed the install; build fresh dist.
log "staging build"
purge_staging; mkdir -p "$STAGE" || fail "mkdir staging"
( cd "$SRC" && tar --exclude=node_modules --exclude=dist -cf - . ) | ( cd "$STAGE" && tar -xf - ) || fail "copy source to staging"
# Warm the install from the live node_modules to speed `yarn install`. Use tar (not cp -a) and EXCLUDE
# proot's ".l2s." loop artifacts so the copy never trips on them (cp -a would ELOOP) and staging stays
# cleanly removable; yarn reconciles anything missing.
# Also EXCLUDE better-sqlite3/build: proot leaves ".l2s." hardlink artifacts AND dangling symlinks
# (e.g. build/.../deps/sqlite3.a) there, which node-gyp's own `clean` cannot unlink under proot (EPERM).
# K2GO-557 seeds the one compiled binary back explicitly below, so the warm copy stays a cache of the
# pure-JS deps only. Other packages' build/ dirs are kept.
if [ -d "$LIVE/node_modules" ]; then
    ( cd "$LIVE" && tar --exclude='*.l2s.*' --exclude='better-sqlite3/build' -cf - node_modules ) | ( cd "$STAGE" && tar -xf - ) || true
fi

# --- K2GO-557: avoid the ~20-min better-sqlite3 node-gyp recompile. Small hops, not one pillar:
#     (2a) reconcile JS deps WITHOUT building native; (2b) reuse the already-compiled native binary
#     from the live box when it is still compatible: node enforces the CPU-ABI and Node-ABI match at
#     require(), and we gate the package version; else compile from source; (2c) tsc. Correctness never
#     depends on reuse: any miss falls back to the pre-K2GO-557 full install. Measured on a 1.4 GB arm32
#     Q8: 36s + 4s + 84s = ~2 min, vs the full recompile's yarn install alone = ~20 min.
BSQ_REL="node_modules/better-sqlite3/build/Release/better_sqlite3.node"
native_ok=0
# 2a) JS deps only: --ignore-scripts skips better-sqlite3's "prebuild-install || node-gyp rebuild",
#     so nothing compiles here. This assumes better-sqlite3 is the only native dep; a new native dep
#     would be left unbuilt on the reuse path and caught (fail-closed) by the step 3 smoke, never
#     shipped silently.
log "staging: reconcile JS deps (no native build)"
if ( cd "$STAGE" && yarn install --ignore-scripts ) >>"$LOG" 2>&1; then
    _lv=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$LIVE/node_modules/better-sqlite3/package.json" 2>/dev/null | head -1)
    _sv=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$STAGE/node_modules/better-sqlite3/package.json" 2>/dev/null | head -1)
    if [ -f "$LIVE/$BSQ_REL" ] && [ -n "$_lv" ] && [ "$_lv" = "$_sv" ]; then
        log "staging: reuse live better_sqlite3.node (better-sqlite3 $_lv unchanged)"
        mkdir -p "$STAGE/node_modules/better-sqlite3/build/Release"
        cp -f "$LIVE/$BSQ_REL" "$STAGE/$BSQ_REL"
        if ( cd "$STAGE" && node -e 'require("better-sqlite3")(":memory:").close()' ) >>"$LOG" 2>&1; then
            native_ok=1; log "staging: reused native binary loads OK (skipped recompile)"
        else
            log "staging: reused binary did not load -> compiling from source"
            rm -f "$STAGE/$BSQ_REL"
        fi
    else
        log "staging: better-sqlite3 changed (${_lv:-?} -> ${_sv:-?}) or no live binary -> compiling from source"
    fi
else
    log "staging: 'yarn install --ignore-scripts' failed -> full install"
fi
# 2b) compile native from source only when reuse did not apply (the pre-K2GO-557 behavior).
if [ "$native_ok" != 1 ]; then
    log "staging: building native deps from source (yarn install)"
    { ( cd "$STAGE" && yarn install ); echo $? > "$STAGE/.nativerc"; } 2>&1 | tee -a "$LOG"
    [ "$(cat "$STAGE/.nativerc" 2>/dev/null || echo 1)" = 0 ] || fail "yarn install (native build: offline or error) — live untouched"
fi
# 2c) compile TypeScript -> dist (always; cheap).
log "staging: tsc build"
{ ( cd "$STAGE" && yarn build ); echo $? > "$STAGE/.buildrc"; } 2>&1 | tee -a "$LOG"
[ "$(cat "$STAGE/.buildrc" 2>/dev/null || echo 1)" = 0 ] || fail "yarn build (tsc error) — live untouched"
[ -f "$STAGE/dist/server.js" ] || fail "no dist/server.js after build — live untouched"

# 3) smoke-test the STAGED build on a temp port (does not touch the live :4000).
# K2GO-557: poll until healthy (up to $STAGE_SMOKE_SECS) instead of a blind `sleep 3`. A cold 2nd node
# on a low-RAM 32-bit device takes ~11s to answer, so `sleep 3` false-failed the update.
log "smoke test staged build on :$TEST_PORT (poll up to ${STAGE_SMOKE_SECS}s)"
( cd "$STAGE" && PORT="$TEST_PORT" node dist/server.js >>"$LOG" 2>&1 ) &
TESTPID=$!
if poll_smoke "http://127.0.0.1:$TEST_PORT/api" "$STAGE_SMOKE_SECS"; then RC=0; else RC=1; fi
kill "$TESTPID" 2>/dev/null || true; wait "$TESTPID" 2>/dev/null || true; TESTPID=""
[ "$RC" -eq 0 ] || fail "staged smoke test failed (rc=$RC, waited ${STAGE_SMOKE_SECS}s) — NOT promoting; live untouched"

# K2GO-557: dry-run stops here with the staged build validated and the live dashboard untouched.
# Lets a low-RAM device be measured end-to-end (build + smoke) without a live promote/restart.
if [ "${K2GO_REBUILD_DRYRUN:-0}" = 1 ]; then
    log "DRY-RUN: staged build + smoke passed; skipping promote (live untouched)"
    set_status "done"; exit 0
fi

# 4) promote — swap the dist FIRST and verify it live; only advance source + package.json (and the
#    nginx vhost) AFTER the live check passes. That way a failed verify rolls back the dist and the
#    version/source were NEVER touched, so the box can't report a version it isn't actually running.
# ADFA-5333: entering the non-cancelable window (dist swap + restart). The cancel endpoint refuses once
# the phase is "promoting"; we also IGNORE stop signals through the swap so a cancel that raced the phase
# read can't land mid-swap. (EXIT still runs cleanup on normal completion.) Restored after step 5.
set_phase "promoting"
trap '' INT TERM
log "staged build passed — promoting"
rm -rf "$BACKUP"
[ -d "$LIVE/dist" ] && cp -a "$LIVE/dist" "$BACKUP"
# node_modules is additive (new deps added, old ones remain), so the old dist tolerates it on rollback;
# safe to sync before the swap so the new dist has its dependencies. tar+exclude avoids the ".l2s." loops.
( cd "$STAGE" && tar --exclude='*.l2s.*' -cf - node_modules ) | ( cd "$LIVE" && tar -xf - ) || true
# The dist swap is the near-atomic, restart-critical step (dash-node runs dist/server.js).
rm -rf "$LIVE/dist" && cp -a "$STAGE/dist" "$LIVE/dist" || fail "dist swap"

log "restart dash-node"
/usr/local/bin/pdsm restart dash-node >>"$LOG" 2>&1 || log "warn: pdsm restart dash-node returned non-zero"

# 5) verify LIVE. On success, finalize (source + package.json + nginx); on failure, roll the dist back.
log "verifying live :4000"
if verify_live; then
    log "live OK — finalizing (source + package.json + nginx)"
    # Now safe to advance the source so the reported version + the next build match the running dist.
    # Additive tar after dropping the pure-source subdirs; runtime state (node_modules, *.sqlite3 job
    # storage, books/catalog.db) is left in place.
    for d in sockets views public test; do rm -rf "$LIVE/$d"; done
    ( cd "$STAGE" && tar --exclude=node_modules --exclude=dist -cf - . ) | ( cd "$LIVE" && tar -xf - ) || log "warn: source sync incomplete"
    # nginx reads /etc/nginx/conf.d, not /library/dashboard, so mirror the vhost then reload nginx.
    [ -f "$LIVE/dash-node-nginx.conf" ] && { cp -f "$LIVE/dash-node-nginx.conf" "$NGINX_CONF_DIR/dash-node-nginx.conf"; chmod 0600 "$NGINX_CONF_DIR/dash-node-nginx.conf"; }
    /usr/local/bin/pdsm restart nginx >>"$LOG" 2>&1 || log "warn: pdsm restart nginx returned non-zero"
    # K2GO-386 (ADR-386): re-assert K2Go-owned log rotation on every update (proot has no cron).
    sh "$CLONE_DIR/tools/setup-proot-logging.sh" >>"$LOG" 2>&1 || log "warn: log-rotation setup failed (non-fatal)"
    # ADFA-5339: optionally refresh the served landing page, from the SAME clone the git fetch+reset
    # above just refreshed, so it matches the new source. Runs only here — after the core swap has
    # verified live — and is best-effort: the site is a separate, versionless artifact, so a failure is
    # a warning, never a rollback of the core update that already succeeded. K2GO_SITE=1 opts in.
    if [ "${K2GO_SITE:-0}" = "1" ]; then
        SITE_UPDATER="$CLONE_DIR/static/site/site-updater.sh"
        if [ -f "$SITE_UPDATER" ]; then
            log "updating the served website (site-updater)"
            # site-updater.sh is a bash script (uses ${BASH_SOURCE[0]}, arrays); run it with bash, not
            # this sh — dash trips on the bash-isms and mis-resolves its own source dir (ADFA-5339).
            bash "$SITE_UPDATER" >>"$LOG" 2>&1 || log "warn: website update failed (core update succeeded)"
        else
            log "warn: K2GO_SITE=1 but site-updater not found at $SITE_UPDATER (core update succeeded)"
        fi
    fi
    log "rebuild complete"
    rm -rf "$BACKUP"
    set_status "done"
else
    log "live check FAILED after swap — rolling back dist"
    if [ -d "$BACKUP" ]; then
        rm -rf "$LIVE/dist" && cp -a "$BACKUP" "$LIVE/dist"
        /usr/local/bin/pdsm restart dash-node >>"$LOG" 2>&1 || true
        # Confirm the rolled-back build actually recovered — don't just assume it did.
        if verify_live; then
            log "rolled back — verified healthy on the previous build (source + version were never advanced)"
        else
            log "ROLLBACK VERIFY FAILED — dashboard may be degraded; manual check needed"
        fi
    else
        log "no backup available to roll back to — dashboard may be degraded"
    fi
    rm -rf "$BACKUP"
    set_status "error"
fi
# Promote done: the terminal status (done/error) is already written and only cleanup remains, so keep
# INT/TERM ignored — a late signal must NOT run on_cancel and overwrite that status with "idle". The EXIT
# trap still runs cleanup on the way out.
cleanup
