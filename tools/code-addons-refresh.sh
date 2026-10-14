#!/bin/bash
# ============================================================================
# Name        : tools/code-addons-refresh.sh
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : K2GO-99 / K2GO-449. dash-node-driven refresh of the offline Code on the Go
#               add-ons gallery. Re-mirrors the published gallery and swaps it in on success,
#               LIVE (box up, no runrole). The session setup, the clone-or-baked resolver, and
#               the mirror-into-staging + atomic-swap body live in tools/lib/updater-refresh.sh;
#               this wrapper only supplies the add-ons paths.
#
# Safe: the mirror runs into "<serve>.new"; the live gallery is only replaced after a clean run,
# so a failed or cancelled refresh never serves a half-mirror.
# ============================================================================
set -u
STATUS=/var/run/code-addons-refresh.status
LOG=/var/log/code-addons-refresh.log
PID=/var/run/code-addons-refresh.pid
# Source the shared lib. Define STATUS first so a missing lib still reports 'error' to dash-node.
. "$(dirname "${BASH_SOURCE[0]}")/lib/updater-refresh.sh" || { echo error > "$STATUS" 2>/dev/null; echo "code-addons-refresh: shared lib missing" >&2; exit 1; }

# SERVE must match code_addons_serve_path (roles/code_addons/defaults). The code_addons role is
# ours only (a Knowledge to Go overlay, not an IIAB upstream role), so the mirror lives under the
# overlay in the self-updating clone and, after a bake, under the ansible roles dir.
SERVE=/library/www/code-addons
MIRROR=$(resolve_from_clone \
  /opt/iiab-android/tools/upstream-patches/overlays/roles/code_addons/files/mirror_addons.py \
  /opt/iiab/iiab/roles/code_addons/files/mirror_addons.py)

refresh_begin "$STATUS" "$LOG" "$PID"
{ mirror_and_swap code-addons-refresh "$SERVE" "$MIRROR" "$STATUS" "$LOG"; } >> "$LOG" 2>&1
