# shellcheck shell=bash
# Module file (no shebang). Bundled by build_bundle.sh

# -------------------------
# Environment Pre-patches & Hacks (15_mod_prepatch_env.sh)
# -------------------------
# This module acts as a registry for upstream bugs, weird Android
# edge cases, and environment quirks.
#
# ALL functions must be idempotent and fail silently if not needed.

python_patch_sysconfig_armv8l() {
  # Fixes a bug in Termux Python 3.13 where 'armv8l' architecture throws a KeyError
  # during pip C-extensions compilation (like aiohttp or zeroconf).

  local py=""
  py="$(command -v python 2>/dev/null || command -v python3 2>/dev/null || true)"
  [[ -n "$py" ]] || return 0 # no python; end.

  local sysconfig_file=""
  sysconfig_file="$("$py" -c "import sysconfig; print(sysconfig.__file__)" 2>/dev/null || true)"

  [[ -z "$sysconfig_file" ]] || [[ ! -f "$sysconfig_file" ]] && return 0

  if grep -q '"armv8l": "armeabi_v7a"' "$sysconfig_file"; then
    return 0 # already fixed; end.
  fi

  if grep -q '"armv7l": "armeabi_v7a"' "$sysconfig_file"; then
    log_yel "Patching Python sysconfig to resolve armv8l architecture bug..."
    sed -i 's/"armv7l": "armeabi_v7a",/"armv7l": "armeabi_v7a",\n        "armv8l": "armeabi_v7a",/g' "$sysconfig_file"
  fi
}
