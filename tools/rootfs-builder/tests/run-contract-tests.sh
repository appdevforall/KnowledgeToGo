#!/usr/bin/env bash
# K2GO-567: run every contract test (test_*.py) under this tests/ tree. Native, no root.
# Each test SKIPs (exit 0) when its upstream-artifact env var is unset; the pre-build workflow
# sets those vars (for example TILE_EXTRACT_PY) so the tests actually run and gate the bake.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
rc=0
while IFS= read -r t; do
    echo "== $t"
    python3 "$t" || rc=1
done < <(find "$HERE" -type f -name 'test_*.py' | sort)
if [ "$rc" -ne 0 ]; then
    echo "CONTRACT TESTS FAILED"
fi
exit "$rc"
