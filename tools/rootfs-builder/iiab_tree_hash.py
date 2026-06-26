#!/usr/bin/env python3
# iiab-tree-sha256-v1 — reference implementation of the rootfs integrity digest.
# Canonical spec (FROZEN): docs/ROOTFS_MANIFEST.md
#
# Usage:
#   iiab_tree_hash.py <tarfile|-> [exclude_member_name]
#     <tarfile>  path to a .tar or .tar.gz (seekable), or "-" to stream stdin
#     exclude    in-archive member name to skip (the integrity member); default none
#
# Streams member content (no whole-file buffering) so it scales to multi-GB /
# 80 GB rootfs images. Exit 3 if an unhashable member type is encountered.
# Copyright (c) 2026 AppDevForAll.
import sys, hashlib, tarfile

ALGO = b"iiab-tree-sha256-v1"

def norm(name):
    n = name.replace("\\", "/")
    if n.startswith("./"):
        n = n[2:]
    while n.startswith("/"):
        n = n[1:]
    while n.endswith("/"):
        n = n[:-1]
    return n

def _open(tar_path):
    # Stream from stdin ("-") with on-the-fly decompression auto-detect (r|*),
    # or open a named file seekably (r:gz / r:).
    if tar_path == "-":
        return tarfile.open(fileobj=sys.stdin.buffer, mode="r|*")
    mode = "r:gz" if tar_path.endswith(".gz") else "r:"
    return tarfile.open(tar_path, mode=mode)

def treehash(tar_path, exclude_name=""):
    digests = []
    with _open(tar_path) as tf:
        for m in tf:
            name = norm(m.name)
            if exclude_name and name == norm(exclude_name):
                continue
            h = hashlib.sha256()
            if m.isdir():
                t = b'd'; tail = b''
            elif m.isreg():
                t = b'f'; tail = None
            elif m.issym():
                t = b'l'; tail = m.linkname.encode("utf-8") + b'\x00'          # raw target
            elif m.islnk():
                t = b'h'; tail = norm(m.linkname).encode("utf-8") + b'\x00'    # normalized target
            else:
                sys.stderr.write("UNHASHABLE member type for %r (type=%r)\n" % (m.name, m.type))
                sys.exit(3)
            h.update(name.encode("utf-8")); h.update(b'\x00'); h.update(t); h.update(b'\x00')
            if t == b'f':
                f = tf.extractfile(m)
                while True:
                    c = f.read(1 << 20)
                    if not c:
                        break
                    h.update(c)
            else:
                h.update(tail)
            digests.append(h.digest())
    digests.sort()
    final = hashlib.sha256(); final.update(ALGO); final.update(b'\x00')
    for d in digests:
        final.update(d)
    return final.hexdigest()

if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.stderr.write("usage: iiab_tree_hash.py <tarfile|-> [exclude_member_name]\n")
        sys.exit(2)
    print(treehash(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else ""))
