#!/bin/bash
# ============================================================================
# Name        : tools/code-assets-refresh.sh
# Author      : AppDevForAll
# Copyright   : Copyright (c) 2026 AppDevForAll
# Description : K2GO-437 / K2GO-449. dash-node-driven refresh of the offline Code on the Go
#               build assets. Re-mirrors the published release set and swaps it in on success,
#               LIVE (box up, no runrole). The session setup, the clone-or-baked resolver, and
#               the mirror-into-staging + atomic-swap body live in tools/lib/updater-refresh.sh;
#               this wrapper only supplies the build-assets paths.
#
# Safe: the mirror runs into "<serve>.new"; the live tree is only replaced after a clean run,
# so a failed or cancelled refresh never serves a half-mirror.
# ============================================================================
set -u
STATUS=/var/run/code-assets-refresh.status
LOG=/var/log/code-assets-refresh.log
PID=/var/run/code-assets-refresh.pid
# Source the shared lib. Define STATUS first so a missing lib still reports 'error' to dash-node.
. "$(dirname "${BASH_SOURCE[0]}")/lib/updater-refresh.sh" || { echo error > "$STATUS" 2>/dev/null; echo "code-assets-refresh: shared lib missing" >&2; exit 1; }

# SERVE must match code_assets_serve_path (roles/code_assets/defaults). The code_assets role is
# ours only (a Knowledge to Go overlay, not an IIAB upstream role), so the mirror lives under the
# overlay in the self-updating clone and, after a bake, under the ansible roles dir.
SERVE=/library/www/code-assets
MIRROR=$(resolve_from_clone \
  /opt/iiab-android/tools/upstream-patches/overlays/roles/code_assets/files/mirror_code_assets.py \
  /opt/iiab/iiab/roles/code_assets/files/mirror_code_assets.py)

refresh_begin "$STATUS" "$LOG" "$PID"
{ mirror_and_swap code-assets-refresh "$SERVE" "$MIRROR" "$STATUS" "$LOG"; } >> "$LOG" 2>&1
