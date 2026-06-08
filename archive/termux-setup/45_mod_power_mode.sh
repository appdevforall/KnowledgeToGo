# shellcheck shell=bash
# Module file (no shebang). Bundled by build_bundle.sh
# Power-mode wrapper for proot login:
# - Termux wakelock
# - Persistent notification

LOGIN_POWERMODE="${LOGIN_POWERMODE:-1}"        # 1=enable, 0=disable
# Stable ID for the login session (derive from NOTIF_BASE_ID if available)
LOGIN_NOTIF_ID="${LOGIN_NOTIF_ID:-$((NOTIF_BASE_ID + 75))}"
LOGIN_NOTIF_ACTIVE=0
POWER_MODE_ACQUIRED_WAKELOCK=0

power_mode_login_enter() {
  [[ "${LOGIN_POWERMODE:-1}" -eq 1 ]] || return 0
  POWER_MODE_ACQUIRED_WAKELOCK=0
  LOGIN_NOTIF_ACTIVE=0

  # Wakelock. Avoid double-acquire.
  local before_wl="${WAKELOCK_HELD:-0}"
  if [[ "$before_wl" -ne 1 ]]; then
    acquire_wakelock || true
    [[ "${WAKELOCK_HELD:-0}" -eq 1 ]] && POWER_MODE_ACQUIRED_WAKELOCK=1
  fi

  # Persistent notification
  if ! have termux-notification; then
    warn "Power-mode: termux-notification not available. Install 'termux-api' + Termux:API app."
    return 0
  fi

  # Remove any stale notif first (ignore errors)
  if have termux-notification-remove; then
    termux-notification-remove "$LOGIN_NOTIF_ID" >/dev/null 2>&1 || true
  fi

  local title="Internet-in-a-Box on Android"
  local content="IIAB session active (proot). For screen-off running, please set battery usage to Unrestricted"

  if termux-notification \
      --id "$LOGIN_NOTIF_ID" \
      --ongoing \
      --priority max \
      --title "$title" \
      --content "$content" \
      >/dev/null 2>&1
  then
    LOGIN_NOTIF_ACTIVE=1
    ok "Power-mode: enabled for this login session (persistent notification active)."
  else
    warn "Power-mode: failed to post notification (permission/app missing?)."
    warn "Tip: install Termux:API app and grant notification permission to Termux."
  fi

  return 0
}

power_mode_login_exit() {
  [[ "${LOGIN_POWERMODE:-1}" -eq 1 ]] || return 0
  local did=0

  # Remove persistent notification (best-effort)
  if have termux-notification-remove; then
    termux-notification-remove "$LOGIN_NOTIF_ID" >/dev/null 2>&1 || true
  fi
  if [[ "${LOGIN_NOTIF_ACTIVE:-0}" -eq 1 ]]; then
    LOGIN_NOTIF_ACTIVE=0
    did=1
  fi

  # Release wakelock (only if this script acquired it)
  if [[ "${POWER_MODE_ACQUIRED_WAKELOCK:-0}" -eq 1 ]]; then
    release_wakelock || true
    POWER_MODE_ACQUIRED_WAKELOCK=0
    did=1
  fi

  (( did )) && ok "Power-mode: released (notification removed, wakelock released)."
  return 0
}
