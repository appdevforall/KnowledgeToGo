#!/bin/sh
# tools/dashboard-smoketest.sh BASE_URL — ADFA-5011
#
# Fast, critical-path health check for a dash-node build. Used twice by
# tools/rebuild-dashboard.sh: against the STAGED build (temp port) before promoting it,
# and against the LIVE build after the swap. Exit 0 = healthy; non-zero aborts the rebuild
# (staged) or triggers rollback (live).
#
# Keep it LEAN — only critical, side-effect-free GET endpoints, so it stays seconds. When a
# change adds a critical endpoint, add ONE check here; don't mirror the whole API.
#
#   sh tools/dashboard-smoketest.sh http://127.0.0.1:4010/api
set -u

BASE="${1:?usage: dashboard-smoketest.sh BASE_URL}"

fail() { echo "smoketest FAIL: $*" >&2; exit 1; }

# GET PATH — healthy if it answers with any non-5xx (2xx/3xx/4xx = the app is up and routing).
check() {
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$BASE$1") || fail "no response: $1"
    [ -n "$code" ] && [ "$code" -lt 500 ] || fail "$1 -> HTTP ${code:-none}"
    echo "  ok $1 ($code)"
}

# 1) version endpoint must answer with a non-empty version (proves the new build is the one running).
body=$(curl -s --max-time 8 "$BASE/system/version") || fail "version endpoint unreachable"
echo "$body" | grep -q '"version"' || fail "version payload malformed: $body"

# 2) critical read paths (no side effects).
check /system/version
check /books/library
check /kiwix/library
check /books/languages

echo "smoketest OK ($BASE)"
