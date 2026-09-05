#!/bin/sh
# tools/dev-push-dashboard.sh — ADFA-4839
#
# Dev helper to push an updated dashboard into an INSTALLED rootfs and restart the
# service, WITHOUT the ~2h rootfs rebuild. Run it from INSIDE the proot (where pdsm and
# /library exist), after `git pull` in your clone.
#
#   sh tools/dev-push-dashboard.sh [CLONE_DIR]
#
# CLONE_DIR defaults to the repo this script lives in. It syncs static/dashboard into
# /library/dashboard (preserving node_modules), deploys the nginx vhost, rebuilds
# (yarn build) and restarts dash-node + nginx. Mirrors install_iiaboa_dashboard.
#
# ADFA-4933: the copy now PROPAGATES DELETIONS. tar is additive, so files removed in the
# repo (e.g. retired socket handlers / the web UI) used to linger at the destination and
# break the build. We drop the pure-source subdirs first, force a clean dist rebuild, and
# deploy dash-node-nginx.conf to /etc/nginx/conf.d (nginx does not read /library/dashboard).
set -eu

usage() {
  cat <<'USAGE'
dev-push-dashboard.sh — push an updated dashboard from a local clone into the INSTALLED rootfs and
restart the service, WITHOUT the ~2h rootfs rebuild (ADFA-4839). Run from INSIDE the proot.

Usage: sh dev-push-dashboard.sh [CLONE_DIR]
  CLONE_DIR  clone to deploy from (default: the repo this script lives in)

Syncs static/dashboard -> /library/dashboard (preserving node_modules), installs log rotation,
deploys the nginx vhost, rebuilds (yarn build), and restarts dash-node + nginx.
USAGE
}
case "${1:-}" in -h|--help) usage; exit 0 ;; esac

CLONE_DIR="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
SRC="$CLONE_DIR/static/dashboard"
DEST="/library/dashboard"
NGINX_CONF_DIR="/etc/nginx/conf.d"

[ -d "$SRC" ] || { echo "ERROR: $SRC not found (is CLONE_DIR right?)" >&2; exit 1; }
[ -d "$DEST" ] || { echo "ERROR: $DEST not found (is the system installed and are you inside the proot?)" >&2; exit 1; }

echo "[dev-push] syncing $SRC -> $DEST (preserving node_modules + runtime state)..."
# tar is proot-safe (rsync's fchmodat2 isn't translated) but additive. First drop the
# pure-source subdirs so removed files don't linger; these hold source only, so runtime
# state (node_modules, dist, and *.sqlite3 job storage at the dashboard root) is untouched.
for d in sockets views public test; do rm -rf "$DEST/$d"; done
( cd "$SRC" && tar --exclude=node_modules --exclude=dist -cf - . ) | ( cd "$DEST" && tar -xf - )

echo "[dev-push] clean rebuild (TS -> dist/)..."
# Drop stale compiled output so sources removed in the repo don't survive as .js in dist/.
rm -rf "$DEST/dist"
( cd "$DEST" && yarn install && yarn build )

echo "[dev-push] deploying nginx vhost to $NGINX_CONF_DIR..."
# The vhost ships inside the dashboard dir, but nginx reads $NGINX_CONF_DIR; the bootstrap
# copies it at install time, so a dev push must mirror that or nginx keeps the old routes.
cp -f "$DEST/dash-node-nginx.conf" "$NGINX_CONF_DIR/dash-node-nginx.conf"
chmod 0600 "$NGINX_CONF_DIR/dash-node-nginx.conf"

echo "[dev-push] configuring log rotation (setup-proot-logging)..."
sh "$CLONE_DIR/tools/setup-proot-logging.sh" || echo "[dev-push] warn: log-rotation setup failed (non-fatal)"

echo "[dev-push] restarting dash-node + nginx..."
/usr/local/bin/pdsm restart dash-node
/usr/local/bin/pdsm restart nginx

echo "[dev-push] done. Health:"
/usr/local/pdsm/services-available/dash-node health || true
