#!/usr/bin/env python3
# Contract test: the kiwix role picks the kiwix-tools build from the userland arch.
#
# A 32-bit library on a 64-bit phone reports machine=aarch64. If kiwix_arch follows the kernel,
# the role installs the 64-bit build, which a 32-bit library cannot run. Our carry patch
# (tools/upstream-patches/patches/0009-kiwix-32bit-userland-arch.patch) keys kiwix_arch on
# dpkg_arch first (armhf -> armv6). This test reads the REAL patched roles/kiwix/defaults/main.yml
# (set KIWIX_DEFAULTS_YML; the pre-build workflow does) and SKIPs when the var is unset.
import os
import re
import sys

path = os.environ.get("KIWIX_DEFAULTS_YML", "")
if not path or not os.path.isfile(path):
    print("SKIP test_kiwix_arch_contract: set KIWIX_DEFAULTS_YML to the patched roles/kiwix/defaults/main.yml")
    sys.exit(0)

with open(path, encoding="utf-8") as handle:
    src = handle.read()

CHECKS = [
    (r"(?m)^kiwix_dpkg_arch_dict:\s*\n(?:[ \t]+\S.*\n)*?[ \t]+armhf:[ \t]*armv6\b",
     "kiwix_dpkg_arch_dict maps armhf to armv6"),
    (r"(?m)^kiwix_arch:.*kiwix_dpkg_arch_dict\.get\(\s*dpkg_arch\.stdout",
     "kiwix_arch is looked up by dpkg_arch.stdout first"),
    (r"(?m)^kiwix_tar_gz:.*\{\{\s*kiwix_arch\s*\}\}",
     "kiwix_tar_gz is built from kiwix_arch"),
]

missing = [desc for pattern, desc in CHECKS if re.search(pattern, src) is None]
if missing:
    print("KIWIX ARCH CONTRACT DRIFT in " + path + ":")
    for desc in missing:
        print("  MISSING: " + desc)
    print("A 32-bit library on a 64-bit phone would download the 64-bit kiwix-tools build.")
    sys.exit(1)

print("OK test_kiwix_arch_contract")
