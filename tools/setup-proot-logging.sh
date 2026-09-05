#!/bin/sh
# tools/setup-proot-logging.sh — K2GO-386 / ADR-386 (Layer 2: contain logs)
#
# Install K2Go's proot-correct log-rotation config for the box, idempotently.
#
# WHY: proot has no systemd and no running cron, so /etc/cron.daily/logrotate NEVER runs — logrotate
# is installed but nothing triggers it, and a service log can grow until the device hits ENOSPC. The
# inherited (Raspberry-Pi-oriented) snippets also lack size caps and use postrotate signals that fail
# under proot (a failed reopen = the deleted-but-open failure). We own our rotation instead.
#
# This script only INSTALLS the config; it does NOT rotate. The rotation itself is triggered on a
# timer by dash-node (static/dashboard/sockets/log-rotate.ts), the box's always-up process. Run this
# inside the proot box (where /etc/logrotate.d exists), at DEPLOY time (rootfs build + rebuild/dev-push).
#
# What it does (all idempotent):
#   1. Move the inherited nginx + php*-fpm snippets OUT of /etc/logrotate.d — we override them, and two
#      snippets listing the same path make logrotate fail with "duplicate log entry".
#   2. Write /etc/logrotate.d/k2go (copytruncate + size-based; proot-correct — no reopen signal).
#   3. Validate the whole config with `logrotate -d` (parse only); on failure ROLL BACK to the prior
#      state (never leave a broken config that would break ALL rotation) and fail loudly.
set -eu

usage() {
  cat <<'USAGE'
setup-proot-logging.sh — install K2Go-owned log rotation for the proot box (K2GO-386 / ADR-386).

Usage: sh setup-proot-logging.sh [-h|--help]

Takes no arguments. Run inside the proot box, at DEPLOY time (rootfs build + rebuild/dev-push).
It overrides the inherited nginx/php-fpm logrotate snippets, installs /etc/logrotate.d/k2go
(copytruncate + size-based), validates with `logrotate -d`, and rolls back on failure. Idempotent.
dash-node triggers the rotation itself on a 10-min timer; this script only installs the config.
USAGE
}
case "${1:-}" in -h|--help) usage; exit 0 ;; esac

LR_D="/etc/logrotate.d"
OVERRIDDEN="/etc/logrotate.d.k2go-overridden"   # OUTSIDE LR_D, so logrotate never reads it
K2GO_CONF="$LR_D/k2go"
# Resolve logrotate by absolute path (a deploy shell may have a reduced PATH without /usr/sbin).
LOGROTATE="$(command -v logrotate 2>/dev/null || echo /usr/sbin/logrotate)"

[ -d "$LR_D" ] || { echo "[k2go-logging] $LR_D not found (not inside the box?) — nothing to do" >&2; exit 0; }
[ -x "$LOGROTATE" ] || { echo "[k2go-logging] logrotate not installed ($LOGROTATE) — skipping" >&2; exit 0; }

# --- snapshot for rollback (restore EXACTLY the pre-run state on validation failure) --------------
PREV_K2GO_BAK=""
if [ -f "$K2GO_CONF" ]; then PREV_K2GO_BAK="$(mktemp)"; cp -f "$K2GO_CONF" "$PREV_K2GO_BAK"; fi
MOVED=""   # basenames this run moved aside, so rollback restores only those

rollback() {
  if [ -n "$PREV_K2GO_BAK" ]; then cp -f "$PREV_K2GO_BAK" "$K2GO_CONF"; else rm -f "$K2GO_CONF"; fi
  for base in $MOVED; do mv -f "$OVERRIDDEN/$base" "$LR_D/$base" 2>/dev/null || true; done
}

# 1) Override the inherited snippets for services we now own. nginx is a fixed name; php*-fpm is a
#    glob so a php-version bump (php8.5-fpm) is covered too — it MUST match the config's php*-fpm.log
#    glob below, or the un-moved snippet would collide with our block.
mkdir -p "$OVERRIDDEN"
for snip in "$LR_D/nginx" "$LR_D"/php*-fpm; do
  [ -f "$snip" ] || continue
  base="$(basename "$snip")"
  mv -f "$snip" "$OVERRIDDEN/$base"
  MOVED="$MOVED $base"
  echo "[k2go-logging] overrode inherited snippet: $base (moved to $OVERRIDDEN)"
done

# 2) Write our config, only if it changed (idempotent).
NEW="$(mktemp)"
cat > "$NEW" <<'EOF'
# K2GO-386 / ADR-386 — K2Go-owned log rotation for the proot box. DO NOT edit by hand;
# managed by tools/setup-proot-logging.sh.
#
# proot has no systemd/cron: dash-node triggers `logrotate` on a timer. Every block uses
# copytruncate (no daemon reopen signal — a failed reopen under proot is the deleted-but-open
# failure) and rotates on SIZE (our threat is a file that grows, not a schedule). This overrides
# the inherited nginx/php-fpm snippets (moved to /etc/logrotate.d.k2go-overridden) and adds the
# ones that never shipped (calibre-web, dash-node). kiwix writes no dedicated log; kolibri
# self-rotates under its KOLIBRI_HOME — both are deliberately left alone.
/var/log/php*-fpm.log
/var/log/nginx/*.log
/var/log/calibre-web.log
/var/log/dash-node.log
/var/log/dash-rebuild.log
{
    su root root
    size 100M
    rotate 3
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
}
EOF

if [ -f "$K2GO_CONF" ] && cmp -s "$NEW" "$K2GO_CONF"; then
  echo "[k2go-logging] $K2GO_CONF already up to date"
  rm -f "$NEW"
else
  cp -f "$NEW" "$K2GO_CONF"
  chmod 0644 "$K2GO_CONF"
  rm -f "$NEW"
  echo "[k2go-logging] installed $K2GO_CONF"
fi

# 3) Validate the WHOLE effective config (parse only; does NOT rotate). On failure, roll back to the
#    pre-run state so a broken config never breaks all rotation, then fail loudly.
if "$LOGROTATE" -d /etc/logrotate.conf >/dev/null 2>&1; then
  echo "[k2go-logging] logrotate config validates OK"
  rm -f "$PREV_K2GO_BAK"
else
  echo "[k2go-logging] ERROR: logrotate config failed validation — rolling back:" >&2
  "$LOGROTATE" -d /etc/logrotate.conf 2>&1 | tail -20 >&2
  rollback
  rm -f "$PREV_K2GO_BAK"
  exit 1
fi
