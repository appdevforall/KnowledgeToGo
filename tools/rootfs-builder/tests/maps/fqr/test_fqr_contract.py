#!/usr/bin/env python3
# K2GO-567 / K2GO-566: contract test for the upstream maps FQR format.
#
# The in-app "Downloaded regions" list and delete (K2GO-566) depend on tile-extract.py keeping the
# per-region "<name>.fqr/" directory layout, with meta.json {"bbox":[...]} inside, and the
# "extract"/"delete" commands. Upstream changed this once with no notice (it dropped the central
# extracts.json catalog), which silently broke the feature. This test fails fast when that contract
# drifts again, BEFORE a rootfs bake ships the break.
#
# It checks the REAL tile-extract.py source (the copy the bake will use), not a frozen fixture.
# Point TILE_EXTRACT_PY at it; the pre-build workflow sets it. The test SKIPs when the var is unset,
# so it is safe to run in any environment.
import os
import re
import sys

script = os.environ.get("TILE_EXTRACT_PY", "")
if not script or not os.path.isfile(script):
    print("SKIP test_fqr_contract: set TILE_EXTRACT_PY to the upstream tile-extract.py[.j2]")
    sys.exit(0)

with open(script, encoding="utf-8") as handle:
    src = handle.read()

# Each marker is something the dash-node enumerator (listFqrRegions) or the app/delete path needs.
REQUIRED = [
    (r"\*\.fqr", 'regions are "<name>.fqr/" directories (the "*.fqr" glob)'),
    (r"meta\.json", "each region has a meta.json metadata file"),
    (r"['\"]?bbox['\"]?", 'meta.json carries a "bbox"'),
    (r"['\"]extract['\"]", 'the "extract" command'),
    (r"['\"]delete['\"]", 'the "delete" command'),
]

missing = [desc for pattern, desc in REQUIRED if re.search(pattern, src) is None]
if missing:
    print("FQR CONTRACT DRIFT in " + script + ":")
    for desc in missing:
        print("  MISSING: " + desc)
    print("The in-app FQR list/delete (K2GO-566) assume this contract. Update them together,")
    print("then update this test. Do NOT ship a bake until they match.")
    sys.exit(1)

print("OK test_fqr_contract: FQR contract markers present in " + script)
sys.exit(0)
