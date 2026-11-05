#!/bin/bash
# =============================================================================
# make-manifest.sh : K2GO-548. Write ninja_manifest.json and cacert.pem for a jniLibs tree.
# Author    : AppDevForAll
# Copyright : Copyright (c) 2026 AppDevForAll
#
# Usage: make-manifest.sh <out-dir>   (expects <out-dir>/jniLibs/<abi>/*.so and *.so.meta)
# One place for the bundle metadata: build_static.sh calls it after a serial build, and the
# workflow's assemble job calls it once after the per-ABI builds.
# =============================================================================
set -e
OUTPUT_DIR="${1:?usage: make-manifest.sh <out-dir>}"

echo ">> [IIAB] Generating Supply Chain Manifest and Certificates..."

echo "   -> Fetching latest cacert.pem..."
curl -sS -o "$OUTPUT_DIR/cacert.pem" https://curl.se/ca/cacert.pem

MANIFEST_FILE="$OUTPUT_DIR/ninja_manifest.json"
echo "   -> Generating $MANIFEST_FILE..."

cat <<EOF > "$MANIFEST_FILE"
{
  "build_date": "$(date -u +'%Y-%m-%dT%H:%M:%SZ')",
  "builder_info": "IIAB build_static.sh",
  "binaries": {
EOF

FIRST_ARCH=1
for arch_dir in "$OUTPUT_DIR/jniLibs"/*; do
    if [ ! -d "$arch_dir" ]; then continue; fi
    arch_name=$(basename "$arch_dir")

    if [ $FIRST_ARCH -eq 0 ]; then echo "    ," >> "$MANIFEST_FILE"; fi
    FIRST_ARCH=0

    echo "    \"$arch_name\": {" >> "$MANIFEST_FILE"

    FIRST_BIN=1
    for bin_file in "$arch_dir"/*.so; do
        if [ ! -f "$bin_file" ]; then continue; fi
        bin_name=$(basename "$bin_file")

        sha256=$(sha256sum "$bin_file" | awk '{print $1}')
        size=$(stat -c%s "$bin_file" 2>/dev/null || stat -f%z "$bin_file")

        # Read sidecar metadata if it exists
        BIN_VER="unknown"
        BIN_DATE="unknown"
        META_FILE="${bin_file}.meta"

        if [ -f "$META_FILE" ]; then
            source "$META_FILE"
            BIN_VER="$VERSION"
            BIN_DATE="$BUILD_DATE"
        else
            # Fallback to physical file modification date
            BIN_DATE=$(date -u -r "$bin_file" +'%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || echo "unknown")
        fi

        if [ $FIRST_BIN -eq 0 ]; then echo "      ," >> "$MANIFEST_FILE"; fi
        FIRST_BIN=0

        cat <<EOF >> "$MANIFEST_FILE"
      "$bin_name": {
        "version": "$BIN_VER",
        "build_date": "$BIN_DATE",
        "sha256": "$sha256",
        "size_bytes": $size
      }
EOF
    done
    echo "    }" >> "$MANIFEST_FILE"
done

cat <<EOF >> "$MANIFEST_FILE"
  }
}
EOF
