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
# inside the proot box (where /etc/logrotate.d exists); it is safe to run on every dash-node start.
#
# What it does (all idempotent):
#   1. Move the inherited nginx + php-fpm snippets OUT of /etc/logrotate.d — we override them, and two
#      snippets listing the same path make logrotate fail with "duplicate log entry".
#   2. Write /etc/logrotate.d/k2go (copytruncate + size-based; proot-correct — no reopen signal).
#   3. Validate the whole config with `logrotate -d` (parse only); fail loudly if broken.
set -eu

LR_D="/etc/logrotate.d"
OVERRIDDEN="/etc/logrotate.d.k2go-overridden"   # OUTSIDE LR_D, so logrotate never reads it
K2GO_CONF="$LR_D/k2go"

[ -d "$LR_D" ] || { echo "[k2go-logging] $LR_D not found (not inside the box?) — nothing to do" >&2; exit 0; }
command -v logrotate >/dev/null 2>&1 || { echo "[k2go-logging] logrotate not installed — skipping" >&2; exit 0; }

# 1) Override the inherited snippets for services we now own (moved aside, reversible).
mkdir -p "$OVERRIDDEN"
for snip in nginx php8.4-fpm; do
  if [ -f "$LR_D/$snip" ]; then
    mv -f "$LR_D/$snip" "$OVERRIDDEN/$snip"
    echo "[k2go-logging] overrode inherited snippet: $snip (moved to $OVERRIDDEN)"
  fi
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

# 3) Validate (parse only; does NOT rotate). Fail loudly so a broken config never ships silently.
if logrotate -d /etc/logrotate.conf >/dev/null 2>&1; then
  echo "[k2go-logging] logrotate config validates OK"
else
  echo "[k2go-logging] ERROR: logrotate config failed validation:" >&2
  logrotate -d /etc/logrotate.conf 2>&1 | tail -20 >&2
  exit 1
fi
