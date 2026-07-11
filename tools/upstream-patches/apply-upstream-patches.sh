#!/usr/bin/env bash
#
# apply-upstream-patches.sh — apply AppDevForAll's in-flight changes to the
# IIAB project checkout (/opt/iiab/iiab) that are proposed upstream but not yet
# merged. Idempotent: a patch that is already present (e.g. upstream merged it,
# or a previous run applied it) is detected and skipped, never applied twice.
#
# Design & conventions: see README.md in this folder.
#
# Usage (run inside the proot guest, after /opt/iiab/iiab exists and BEFORE the
# Ansible roles run):
#   tools/upstream-patches/apply-upstream-patches.sh [--root DIR] [--dry-run]
#
# Exit codes: 0 = all patches applied or already-present; 1 = one or more
# patches could not be applied (context drift → needs regeneration). We fail
# loudly rather than bake a half-patched tree.

set -euo pipefail

ROOT="/opt/iiab/iiab"     # the IIAB project checkout to patch
DRY_RUN=0
STRIP=1                   # patches are generated relative to the repo root → -p1

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH_DIR="${SELF_DIR}/patches"
OVERLAY_DIR="${SELF_DIR}/overlays"   # optional: whole-file replacements

log()  { printf '[upstream-patches] %s\n' "$*"; }
warn() { printf '[upstream-patches] WARN: %s\n' "$*" >&2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root)    ROOT="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) warn "unknown arg: $1"; exit 2 ;;
  esac
done

[[ -d "$ROOT" ]] || { warn "target root not found: $ROOT (nothing to patch)"; exit 0; }
command -v patch >/dev/null 2>&1 || { warn "'patch' not installed in the guest"; exit 1; }

rc=0
applied=0 skipped=0 failed=0

# --- 1) Unified-diff patches (the primary mechanism) ------------------------
if [[ -d "$PATCH_DIR" ]]; then
  # Deterministic order: NNNN- numeric prefix. *.patch only (templates use .txt).
  shopt -s nullglob
  for f in "$PATCH_DIR"/*.patch; do
    name="$(basename "$f")"
    # Already present? (upstream merged it, or a prior run applied it.) A clean
    # REVERSE apply proves the change is already in the tree → skip.
    if patch -p"$STRIP" -d "$ROOT" -R --dry-run < "$f" >/dev/null 2>&1; then
      log "skip (already present): $name"
      skipped=$((skipped+1))
      continue
    fi
    # Not present → does it apply cleanly forward?
    if patch -p"$STRIP" -d "$ROOT" --dry-run < "$f" >/dev/null 2>&1; then
      if [[ "$DRY_RUN" -eq 1 ]]; then
        log "would apply: $name"
      else
        patch -p"$STRIP" -d "$ROOT" < "$f" >/dev/null
        log "applied: $name"
      fi
      applied=$((applied+1))
    else
      warn "could NOT apply (context drift / partially applied): $name"
      warn "  → regenerate against the current /opt/iiab/iiab, or drop if superseded."
      failed=$((failed+1)); rc=1
    fi
  done
  shopt -u nullglob
fi

# --- 2) Optional whole-file overlays ----------------------------------------
# For cases better expressed as a full-file replacement than a diff (e.g.
# replacing a file that is itself a .patch). Mirror the tree under overlays/
# rooted at the repo root; a file is copied only if it differs (idempotent).
if [[ -d "$OVERLAY_DIR" ]]; then
  while IFS= read -r -d '' src; do
    rel="${src#"$OVERLAY_DIR"/}"
    dst="$ROOT/$rel"
    if [[ -f "$dst" ]] && cmp -s "$src" "$dst"; then
      log "skip overlay (identical): $rel"; skipped=$((skipped+1)); continue
    fi
    if [[ "$DRY_RUN" -eq 1 ]]; then
      log "would overlay: $rel"
    else
      mkdir -p "$(dirname "$dst")"
      cp -a "$src" "$dst"
      log "overlay: $rel"
    fi
    applied=$((applied+1))
  done < <(find "$OVERLAY_DIR" -type f ! -name '.gitkeep' -print0 2>/dev/null)
fi

log "summary: applied=${applied} skipped=${skipped} failed=${failed} (root=${ROOT})"
exit "$rc"
