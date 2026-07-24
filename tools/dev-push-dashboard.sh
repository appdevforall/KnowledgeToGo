#!/bin/sh
# tools/dev-push-dashboard.sh — ADFA-4839
#
# Dev helper to push an updated dashboard into an INSTALLED rootfs and restart the
# service, WITHOUT the ~2h rootfs rebuild. Run it from INSIDE the proot (where pdsm and
# /library exist), after `git pull` in your clone.
#
#   sh tools/dev-push-dashboard.sh [CLONE_DIR]
#
# CLONE_DIR defaults to the repo this script lives in. It copies static/dashboard into
# /library/dashboard (preserving node_modules), rebuilds (yarn build) and restarts
# dash-node. Mirrors what the bootstrap's install_iiaboa_dashboard does.
set -eu

CLONE_DIR="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
SRC="$CLONE_DIR/static/dashboard"
DEST="/library/dashboard"

[ -d "$SRC" ] || { echo "ERROR: $SRC not found (is CLONE_DIR right?)" >&2; exit 1; }
[ -d "$DEST" ] || { echo "ERROR: $DEST not found (is the system installed and are you inside the proot?)" >&2; exit 1; }

echo "[dev-push] copying $SRC -> $DEST (excluding node_modules)..."
# tar is proot-safe (rsync's fchmodat2 isn't translated); exclude node_modules + dist so
# we never clobber the installed native better-sqlite3 build.
( cd "$SRC" && tar --exclude=node_modules --exclude=dist -cf - . ) | ( cd "$DEST" && tar -xf - )

echo "[dev-push] installing deps + building (TS -> dist/)..."
( cd "$DEST" && yarn install && yarn build )

echo "[dev-push] restarting dash-node + nginx..."
/usr/local/bin/pdsm restart dash-node
/usr/local/bin/pdsm restart nginx

echo "[dev-push] done. Health:"
/usr/local/pdsm/services-available/dash-node health || true
