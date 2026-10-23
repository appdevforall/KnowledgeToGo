#!/usr/bin/env bash
# =============================================================================
# test-binaries-lifecycle.sh: self-test for binaries-lifecycle.sh (K2GO-506).
# Author: AppDevForAll
#
# Runs the script against fake gh and aws commands and a throwaway git repo, then
# checks what it archives and deletes. Needs bash and git only: no network, no
# secrets. binaries-lifecycle.yml runs it before it touches GitHub or R2.
#
# Usage: test-binaries-lifecycle.sh [path/to/binaries-lifecycle.sh]
# =============================================================================
set -uo pipefail
SCRIPT="$(realpath "${1:-$(dirname "$0")/binaries-lifecycle.sh}")"
ROOT="$(mktemp -d)"
trap 'rm -rf "$ROOT"' EXIT
STUB="$ROOT/stub"; STATE="$ROOT/state"; REPO="$ROOT/repo"
mkdir -p "$STUB" "$STATE" "$REPO/controller"
pass=0; fail=0
check() {
  if [ "$2" = "$3" ]; then echo "PASS $1"; pass=$((pass + 1))
  else echo "FAIL $1: got [$2], want [$3]"; fail=$((fail + 1)); fi
}

# Fake gh: list prints $STATE/releases (newest first), download writes a file, delete logs the tag.
cat > "$STUB/gh" <<'EOF'
#!/usr/bin/env bash
case "$1 $2" in
  "release list") [ -f "$STATE/gh_fail" ] && exit 1; cat "$STATE/releases" ;;
  "release download")
    tag="$3"; shift 3
    while [ $# -gt 0 ]; do case "$1" in --dir) dir="$2"; shift 2 ;; *) shift ;; esac; done
    echo "zip of $tag" > "$dir/termux-binaries-latest.zip" ;;
  "release delete") echo "$3" >> "$STATE/deleted" ;;
  *) echo "fake gh: unexpected $*" >&2; exit 9 ;;
esac
EOF
# Fake aws: head-object succeeds when the key is in $STATE/archived; s3 cp records the key.
cat > "$STUB/aws" <<'EOF'
#!/usr/bin/env bash
[ "$1" = "--endpoint-url" ] && shift 2
case "$1 $2" in
  "s3api head-object")
    while [ $# -gt 0 ]; do case "$1" in --key) key="$2"; shift 2 ;; *) shift ;; esac; done
    grep -qx "$key" "$STATE/archived" 2>/dev/null ;;
  "s3 cp") key="${4#s3://*/}"; echo "$key" >> "$STATE/archived"; echo "$key" >> "$STATE/uploaded" ;;
  *) echo "fake aws: unexpected $*" >&2; exit 9 ;;
esac
EOF
chmod +x "$STUB/gh" "$STUB/aws"
# The workflow runs this inside the real job env: drop its run mode and credentials.
unset DRY_RUN KEEP GH_TOKEN GITHUB_TOKEN AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
export PATH="$STUB:$PATH" STATE GITHUB_REPOSITORY=owner/repo BUCKET_NAME=bucket R2_ACCOUNT_ID=account

# Pins: v0.9.0-beta has no pin file, v1.0.0 pins 02, development pins 03, main pins 09 (CRLF).
cd "$REPO"
git init -q; git config user.email test@example.com; git config user.name test
git config core.autocrlf false
echo x > other.txt; git add .; git commit -qm a; git tag v0.9.0-beta
echo binaries-02 > controller/binary_version.txt; git add .; git commit -qm b; git tag -a v1.0.0 -m b
echo binaries-03 > controller/binary_version.txt; git commit -qam c
git update-ref refs/remotes/origin/development HEAD
printf 'binaries-09\r\n' > controller/binary_version.txt; git commit -qam d
git update-ref refs/remotes/origin/main HEAD

reset() {
  rm -f "$STATE"/*
  for i in 10 09 08 07 06 05 04 03 02 01; do echo "binaries-$i"; done > "$STATE/releases"
}
archive() { for i in "$@"; do echo "binaries/binaries-$i/termux-binaries-latest.zip.sha256" >> "$STATE/archived"; done; }
deleted() { [ -f "$STATE/deleted" ] && tr '\n' ' ' < "$STATE/deleted"; }

# Newest 6 kept, pins kept (v1.0.0, development), unarchived 01 kept: only 04 goes.
reset; archive 04 03 02
bash "$SCRIPT" prune > "$STATE/out" 2>&1; rc=$?
check "prune exits 0" "$rc" "0"
check "prune deletes only the old, archived, unpinned release" "$(deleted)" "binaries-04 "
grep -q "keep (pinned): binaries-02" "$STATE/out"; check "v* tag pin kept" "$?" "0"
grep -q "keep (pinned): binaries-03" "$STATE/out"; check "branch pin kept" "$?" "0"
grep -q "keep (not archived in R2): binaries-01" "$STATE/out"; check "unarchived release kept" "$?" "0"

reset; archive 04
DRY_RUN=1 bash "$SCRIPT" prune > "$STATE/out" 2>&1
check "dry run deletes nothing" "$(deleted)" ""
grep -q "DRY-RUN: would delete binaries-04" "$STATE/out"; check "dry run reports the delete" "$?" "0"

reset; archive 04
KEEP=six bash "$SCRIPT" prune > "$STATE/out" 2>&1; rc=$?
check "invalid KEEP exits non-zero" "$([ "$rc" -ne 0 ] && echo yes)" "yes"
check "invalid KEEP deletes nothing" "$(deleted)" ""

# Sync uploads only what R2 lacks, zip first, and a second run uploads nothing.
reset; archive 10 09 08 07 06 05 04 03 02
bash "$SCRIPT" sync > "$STATE/out" 2>&1; rc=$?
check "sync exits 0" "$rc" "0"
check "sync uploads the zip, then the sha256" "$(tr '\n' ' ' < "$STATE/uploaded")" \
  "binaries/binaries-01/termux-binaries-latest.zip binaries/binaries-01/termux-binaries-latest.zip.sha256 "
bash "$SCRIPT" sync > "$STATE/out" 2>&1
check "second sync uploads nothing" "$(wc -l < "$STATE/uploaded" | tr -d ' ')" "2"

# A failing gh list stops both commands (no silent no-op).
reset; touch "$STATE/gh_fail"
bash "$SCRIPT" sync > "$STATE/out" 2>&1; rc=$?
check "gh failure stops sync" "$([ "$rc" -ne 0 ] && echo yes)" "yes"
bash "$SCRIPT" prune > "$STATE/out" 2>&1; rc=$?
check "gh failure stops prune" "$([ "$rc" -ne 0 ] && echo yes)" "yes"

# Fail closed: an empty pin on main deletes nothing.
: > controller/binary_version.txt; git commit -qam e; git update-ref refs/remotes/origin/main HEAD
reset; archive 04
bash "$SCRIPT" prune > "$STATE/out" 2>&1; rc=$?
check "empty main pin exits non-zero" "$([ "$rc" -ne 0 ] && echo yes)" "yes"
check "empty main pin deletes nothing" "$(deleted)" ""

echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
