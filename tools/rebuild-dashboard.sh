#!/bin/sh
# tools/rebuild-dashboard.sh [CLONE_DIR] — ADFA-5011
#
# Rebuild ONLY the dash-node REST API from the on-device clone, without a rootfs rebuild.
# Blue-green + verify-before-swap so the live API is never left in a broken state:
#
#   1. git fetch + reset --hard origin/<branch>      (deterministic; the box clone isn't edited)
#   2. build in a STAGING dir (yarn install + build)
#   3. smoke-test the STAGED build on a temp port     (tools/dashboard-smoketest.sh)
#   4. only if it passes: back up live dist, atomically swap staged dist in, sync source + nginx,
#      restart dash-node + nginx, and re-verify LIVE
#   5. if the live check fails: roll back to the backed-up dist and restart
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

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG" 2>/dev/null; }
set_status() { echo "$1" > "$STATUS" 2>/dev/null || true; }
cleanup() { [ -n "${TESTPID:-}" ] && kill "$TESTPID" 2>/dev/null || true; rm -rf "$STAGE"; rmdir "$LOCK" 2>/dev/null || true; }
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
rm -rf "$STAGE"; mkdir -p "$STAGE" || fail "mkdir staging"
( cd "$SRC" && tar --exclude=node_modules --exclude=dist -cf - . ) | ( cd "$STAGE" && tar -xf - ) || fail "copy source to staging"
[ -d "$LIVE/node_modules" ] && cp -a "$LIVE/node_modules" "$STAGE/node_modules"
( cd "$STAGE" && yarn install >>"$LOG" 2>&1 && yarn build >>"$LOG" 2>&1 ) || fail "yarn install/build (offline or build error) — live untouched"
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

# 4) promote: back up live dist, swap staged in, sync source + nginx, restart.
log "staged build passed — promoting"
rm -rf "$BACKUP"
[ -d "$LIVE/dist" ] && cp -a "$LIVE/dist" "$BACKUP"
# Sync source (so the next build matches) — additive tar after dropping pure-source subdirs;
# runtime state (node_modules, *.sqlite3 job storage, books/catalog.db) is left in place.
for d in sockets views public test; do rm -rf "$LIVE/$d"; done
( cd "$STAGE" && tar --exclude=node_modules --exclude=dist -cf - . ) | ( cd "$LIVE" && tar -xf - ) || fail "sync source to live"
cp -a "$STAGE/node_modules/." "$LIVE/node_modules/" 2>/dev/null || true
# The dist swap is the near-atomic, restart-critical step (dash-node runs dist/server.js).
rm -rf "$LIVE/dist" && cp -a "$STAGE/dist" "$LIVE/dist" || fail "dist swap"
# nginx reads /etc/nginx/conf.d, not /library/dashboard, so mirror the vhost.
[ -f "$LIVE/dash-node-nginx.conf" ] && { cp -f "$LIVE/dash-node-nginx.conf" "$NGINX_CONF_DIR/dash-node-nginx.conf"; chmod 0600 "$NGINX_CONF_DIR/dash-node-nginx.conf"; }

log "restart dash-node + nginx"
/usr/local/bin/pdsm restart dash-node >>"$LOG" 2>&1 || log "warn: pdsm restart dash-node returned non-zero"
/usr/local/bin/pdsm restart nginx     >>"$LOG" 2>&1 || log "warn: pdsm restart nginx returned non-zero"

# 5) verify LIVE; roll back the dist if it doesn't come up.
log "verifying live :4000"
ok=0
i=1
while [ "$i" -le 15 ]; do
    if sh "$SMOKE" "http://127.0.0.1:4000/api" >>"$LOG" 2>&1; then ok=1; break; fi
    sleep 2; i=$((i + 1))
done
if [ "$ok" -eq 1 ]; then
    log "live OK — rebuild complete"
    rm -rf "$BACKUP"
    set_status "done"
else
    log "live check FAILED after swap — rolling back dist"
    if [ -d "$BACKUP" ]; then
        rm -rf "$LIVE/dist" && cp -a "$BACKUP" "$LIVE/dist"
        /usr/local/bin/pdsm restart dash-node >>"$LOG" 2>&1 || true
        log "rolled back to previous dist"
    fi
    set_status "error"
fi
cleanup
