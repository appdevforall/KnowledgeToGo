#!/usr/bin/env python3
# K2GO-567 / K2GO-566: contract test for the upstream maps FQR format and the tile-extract.py CLI.
#
# The in-app FQR download, size estimate, list and delete depend on tile-extract.py: the per-region
# "<name>.fqr/" layout with meta.json {"bbox":[...]}, and the CLI and messages that dash-node
# (static/dashboard/sockets/maps.socket.ts) drives and reads. Upstream changed the layout once with
# no notice (it dropped the central extracts.json), which silently broke the feature. This test fails
# fast when that contract drifts again, BEFORE a rootfs bake ships the break.
#
# It checks the REAL tile-extract.py source (the copy the bake will use, with our upstream patches
# applied), not a frozen fixture. Point TILE_EXTRACT_PY at it; the pre-build workflow sets it. The
# test SKIPs when the var is unset, so it is safe to run in any environment.
import os
import re
import sys

script = os.environ.get("TILE_EXTRACT_PY", "")
if not script or not os.path.isfile(script):
    print("SKIP test_fqr_contract: set TILE_EXTRACT_PY to the upstream tile-extract.py[.j2]")
    sys.exit(0)

with open(script, encoding="utf-8") as handle:
    src = handle.read()

# The region layout: the dash-node enumerator (listFqrRegions) reads it.
LAYOUT = [
    (r"\*\.fqr", 'regions are "<name>.fqr/" directories (the "*.fqr" glob)'),
    (r"meta\.json", "each region has a meta.json metadata file"),
    (r"['\"]?bbox['\"]?", 'meta.json carries a "bbox"'),
    (r"['\"]extract['\"]", 'the "extract" command'),
    (r"['\"]delete['\"]", 'the "delete" command'),
]

# The CLI (iiab#4574) that dash-node uses: hasModernCli() looks for "--yes" and "--bbox".
CLI = [
    (r'"--yes"', 'the --yes option (no questions; hasModernCli marker)'),
    (r'"--bbox"', 'the --bbox option (hasModernCli marker)'),
    (r'"--progress", choices=\["bar", "json"\]', 'the --progress json option (the JSON progress lines)'),
    (r'"--threads"', 'the --threads option'),
    (r"full-region\.\{extract_name\}\.pmtiles\*", "delete removes the files of an unfinished download"),
    (r"sys\.exit\(3\)", "exit 3 = paused (the .resume journal is saved)"),
]

# The messages dash-node classifies (LOCK_BUSY_RE, NET_ERR_RE, parseEstimate).
MESSAGES = [
    (r"another Full Quality Region download or delete is running", "the lock message (LOCK_BUSY_RE)"),
    (r"a region with this name, or an overlapping region, now exists", "the conflict message (REGION_CONFLICT_RE)"),
    (r"Cannot download the map data catalog", "the catalog network error (NET_ERR_RE)"),
    (r"from any of the available mirrors", "the mirror network error (NET_ERR_RE)"),
    (r"New region will be \{display\(transfer_total\)\} downloaded, ", "the size prompt, transfer part (parseEstimate)"),
    (r"on disk\. This will", "the size prompt, disk part (parseEstimate)"),
    (r"leave about \{display\([^)]*\)\} free space", "the size prompt, free space part (parseEstimate)"),
]

missing = [desc for pattern, desc in LAYOUT + CLI + MESSAGES if re.search(pattern, src) is None]
if missing:
    print("FQR CONTRACT DRIFT in " + script + ":")
    for desc in missing:
        print("  MISSING: " + desc)
    print("The in-app FQR download, estimate, list and delete (dash-node maps.socket.ts) assume this")
    print("contract. Update them together, then update this test. Do NOT ship a bake until they match.")
    sys.exit(1)

print("OK test_fqr_contract: FQR contract markers present in " + script)
sys.exit(0)
