#!/bin/sh
# tools/rebuild-dashboard.sh [CLONE_DIR] — ADFA-5011 / ADFA-5051
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

CLONE_DIR="${1:-/opt/iiab-android}"
BRANCH="${K2GO_BRANCH:-main}"
SRC="$CLONE_DIR/static/dashboard"
LIVE="/library/dashboard"
STAGE="/library/dashboard.staging"
BACKUP="/library/dashboard.dist.bak"
NGINX_CONF_DIR="/etc/nginx/conf.d"
TEST_PORT="${K2GO_REBUILD_TEST_PORT:-4010}"
# The app runs the newest scripts from a temp dir (extracted via `git show`), so it can point us at
# the matching smoke test there; falls back to the clone's copy for a manual/dev run.
SMOKE="${K2GO_SMOKE:-$CLONE_DIR/tools/dashboard-smoketest.sh}"

STATUS="/var/run/dash-rebuild.status"
LOG="/var/log/dash-rebuild.log"
LOCK="/var/run/dash-rebuild.lock"

# ADFA-4893: also echo to the console so a manual foreground run shows progress live (not just the
# log file). Harmless when launched detached (POST .../rebuild) — stdout goes nowhere then.
log() { _m="[$(date '+%Y-%m-%d %H:%M:%S')] $*"; echo "$_m" >> "$LOG" 2>/dev/null; echo "$_m"; }
set_status() { echo "$1" > "$STATUS" 2>/dev/null || true; }
# ADFA-5051: proot renders some native-build symlinks (e.g. in better-sqlite3/build) as ".l2s." loops
# that `rm -rf` can't recurse into (ELOOP -> "Directory not empty"), but a direct unlink removes them.
# Sweep them first, then remove the tree — so staging is always cleanly removable.
purge_staging() {
    [ -d "$STAGE" ] || return 0
    find "$STAGE" -name '*.l2s.*' -exec rm -f {} + 2>/dev/null || true
    rm -rf "$STAGE" 2>/dev/null || true
}
# ADFA-5051: poll the LIVE API's smoke test until it passes (~30s) or give up (1 = not healthy).
verify_live() {
    _i=1
    while [ "$_i" -le 15 ]; do
        if sh "$SMOKE" "http://127.0.0.1:4000/api" >>"$LOG" 2>&1; then return 0; fi
        sleep 2; _i=$((_i + 1))
    done
    return 1
}
cleanup() { [ -n "${TESTPID:-}" ] && kill "$TESTPID" 2>/dev/null || true; purge_staging; rmdir "$LOCK" 2>/dev/null || true; }
fail() { log "FAIL: $*"; set_status "error"; cleanup; exit 1; }

# Single-flight: mkdir is atomic.
mkdir "$LOCK" 2>/dev/null || { echo "another rebuild is running" >&2; exit 3; }
trap cleanup EXIT
: > "$LOG" 2>/dev/null || true
set_status "running"
log "rebuild start (branch=$BRANCH, clone=$CLONE_DIR)"

[ -d "$SRC" ]  || fail "source $SRC not found"
[ -d "$LIVE" ] || fail "live $LIVE not found (dashboard not installed?)"
[ -f "$SMOKE" ] || fail "smoke test $SMOKE not found"

# 1) refresh the clone to the tip of the branch (deterministic; discards any local drift).
log "git fetch + reset --hard origin/$BRANCH"
git -C "$CLONE_DIR" fetch origin "$BRANCH" >>"$LOG" 2>&1 || fail "git fetch (offline?)"
git -C "$CLONE_DIR" reset --hard "origin/$BRANCH" >>"$LOG" 2>&1 || fail "git reset"

# 2) build in staging. Reuse live node_modules to speed the install; build fresh dist.
log "staging build"
purge_staging; mkdir -p "$STAGE" || fail "mkdir staging"
( cd "$SRC" && tar --exclude=node_modules --exclude=dist -cf - . ) | ( cd "$STAGE" && tar -xf - ) || fail "copy source to staging"
# Warm the install from the live node_modules to speed `yarn install`. Use tar (not cp -a) and EXCLUDE
# proot's ".l2s." loop artifacts so the copy never trips on them (cp -a would ELOOP) and staging stays
# cleanly removable; yarn reconciles anything missing.
if [ -d "$LIVE/node_modules" ]; then
    ( cd "$LIVE" && tar --exclude='*.l2s.*' -cf - node_modules ) | ( cd "$STAGE" && tar -xf - ) || true
fi
# ADFA-4893: stream yarn install/build to BOTH the console and the log — no more staring at a stopped
# screen. POSIX sh has no `pipefail`, so capture the real exit code to a file inside the group before
# the pipe, then check it. (yarn install prints progress; `yarn build` = tsc, silent on success.)
{ ( cd "$STAGE" && yarn install && yarn build ); echo $? > "$STAGE/.buildrc"; } 2>&1 | tee -a "$LOG"
[ "$(cat "$STAGE/.buildrc" 2>/dev/null || echo 1)" = 0 ] || fail "yarn install/build (offline or build error) — live untouched"
[ -f "$STAGE/dist/server.js" ] || fail "no dist/server.js after build — live untouched"

# 3) smoke-test the STAGED build on a temp port (does not touch the live :4000).
log "smoke test staged build on :$TEST_PORT"
( cd "$STAGE" && PORT="$TEST_PORT" node dist/server.js >>"$LOG" 2>&1 ) &
TESTPID=$!
sleep 3
sh "$SMOKE" "http://127.0.0.1:$TEST_PORT/api" >>"$LOG" 2>&1
RC=$?
kill "$TESTPID" 2>/dev/null || true; wait "$TESTPID" 2>/dev/null || true; TESTPID=""
[ "$RC" -eq 0 ] || fail "staged smoke test failed (rc=$RC) — NOT promoting; live untouched"

# 4) promote — swap the dist FIRST and verify it live; only advance source + package.json (and the
#    nginx vhost) AFTER the live check passes. That way a failed verify rolls back the dist and the
#    version/source were NEVER touched, so the box can't report a version it isn't actually running.
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
cleanup
