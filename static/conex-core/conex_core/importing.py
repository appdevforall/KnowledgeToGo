"""Import-side safety helpers (Pillar 3).

An imported archive came from another box or a disk, so it is UNTRUSTED. Before any
extraction, every member name is validated against path traversal (absolute paths and
".." climbing out), mirroring the repo's D11 guard (ArchiveEntry.escapesRoot). Links are
refused outright: a tar from a trusted exporter never needs them, and a symlink member is
the classic way to escape the destination. payload_path containment keeps the core from
reading an arbitrary path the caller names.
"""

from __future__ import annotations

import os
import tarfile
from typing import Optional

# A sane ceiling on members in one item archive. A legitimate Kolibri channel bundle is
# thousands of files; millions is a decompression-bomb shape, not a real item.
MAX_MEMBERS = 500000


def escapes_root(name: str) -> bool:
    """True if extracting ``name`` could escape the destination root: an absolute path, or
    ".." segments that climb above the root. A leading "./" and an internal "a/../b" that
    stays inside are fine. Pure; mirrors ArchiveEntry.escapesRoot (D11)."""
    if not name:
        return False
    n = name.replace("\\", "/").strip()
    if not n:
        return False
    if n.startswith("/"):
        return True
    depth = 0
    for seg in n.split("/"):
        if seg in ("", "."):
            continue
        if seg == "..":
            depth -= 1
            if depth < 0:
                return True
        else:
            depth += 1
    return False


def is_within_dir(root: str, path: str) -> bool:
    """True when path resolves inside root. Guards the caller-named payload path."""
    root_real = os.path.realpath(root)
    path_real = os.path.realpath(path)
    return path_real == root_real or path_real.startswith(root_real + os.sep)


def safe_extract_tar(
    tar_path: str, dest: str, max_members: int = MAX_MEMBERS, max_bytes: Optional[int] = None
) -> None:
    """Extract a tar into dest after validating every member. Raises ValueError on a member
    that escapes the root or is a link / special file, when the member count exceeds
    ``max_members``, or when the summed ACTUAL member sizes (from the tar metadata, not from
    any caller-supplied figure) exceed ``max_bytes`` (when one is given). This bounds a
    decompression bomb that declares a tiny size but extracts to gigabytes / millions of files.
    Never writes outside dest.

    Free space is NOT checked here. The box runs under proot, whose statvfs under-reports the
    free space on the content volume, so a free-space bound false-rejected legitimate large
    imports on devices with ample room. The phone app is the one source of truth for free space
    (Android StatFs) and gates before an import; a real ENOSPC during extraction fails cleanly
    and the partial work dir is removed by the caller."""
    with tarfile.open(tar_path, "r") as tar:
        members = tar.getmembers()
        if len(members) > max_members:
            raise ValueError(f"archive has too many members ({len(members)} > {max_members})")
        total = 0
        for m in members:
            if escapes_root(m.name):
                raise ValueError(f"unsafe archive member (path traversal): {m.name!r}")
            if m.issym() or m.islnk():
                raise ValueError(f"unsafe archive member (link): {m.name!r}")
            if not (m.isfile() or m.isdir()):
                raise ValueError(f"unsafe archive member (special file): {m.name!r}")
            if m.isfile():
                total += max(0, m.size)
        os.makedirs(dest, exist_ok=True)
        if max_bytes is not None and total > max_bytes:
            raise ValueError(f"archive too large to extract ({total} bytes > {max_bytes} cap)")
        # Members are already validated; the stdlib "data" filter is a second, independent
        # barrier (it also rejects traversal and dangerous members) where available.
        try:
            tar.extractall(dest, filter="data")
        except TypeError:  # Python without the extraction filter argument
            tar.extractall(dest)


def single_child_dir(root: str) -> Optional[str]:
    """If root holds exactly one subdirectory and no files, return it. The export tar wraps
    an item under one top directory (its id), so this finds the real bundle root."""
    try:
        entries = os.listdir(root)
    except OSError:
        return None
    if len(entries) == 1:
        child = os.path.join(root, entries[0])
        if os.path.isdir(child):
            return child
    return None
