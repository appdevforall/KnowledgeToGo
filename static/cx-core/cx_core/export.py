"""Shared export streaming helpers (Pillar 2, output side).

The core STAGES and STREAMS one item; the front moves the bytes and owns the whole
operation (ADR D1c). These helpers are read-only on the source: they copy bytes out,
they never mutate an app's data.

The tar is streamed file by file (mode "w|", non-seekable), so a large item is never
buffered whole in memory and no second full copy is written to disk.
"""

from __future__ import annotations

import os
import tarfile
from typing import Iterator, List, Optional, Tuple

CHUNK = 1024 * 1024


def file_stream(path: str, chunk: int = CHUNK) -> Iterator[bytes]:
    """Yield a file's bytes in chunks. Opens lazily (on first iteration), read-only."""
    with open(path, "rb") as f:
        while True:
            block = f.read(chunk)
            if not block:
                return
            yield block


def resolve_within(root: str, rel: str) -> Optional[str]:
    """Join a relative path onto root and return its real path only when it stays inside
    root. Guards a multi-segment relative path (e.g. a Calibre book path) against
    traversal. Pure except for realpath resolution."""
    if not rel:
        return None
    root_real = os.path.realpath(root)
    target = os.path.realpath(os.path.join(root_real, rel))
    if target == root_real or target.startswith(root_real + os.sep):
        return target
    return None


class _ChunkSink:
    """A write-only file object that collects what tarfile writes, so a streaming tar can
    be drained chunk by chunk between members."""

    def __init__(self) -> None:
        self._parts: List[bytes] = []

    def write(self, data: bytes) -> int:
        self._parts.append(bytes(data))
        return len(data)

    def drain(self) -> bytes:
        out = b"".join(self._parts)
        self._parts = []
        return out


def _entries(root: str, arcname: str) -> List[Tuple[str, str]]:
    """(absolute path, archive name) pairs for root and everything under it, sorted so the
    archive is deterministic. Directories are included so empty ones survive."""
    pairs: List[Tuple[str, str]] = [(root, arcname)]
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for name in sorted(dirnames) + sorted(filenames):
            full = os.path.join(dirpath, name)
            arc = os.path.join(arcname, os.path.relpath(full, root))
            pairs.append((full, arc))
    return pairs


def tar_stream(root: str, arcname: str = ".", chunk: int = CHUNK) -> Iterator[bytes]:
    """Stream a tar of a directory tree, member by member, draining the buffer between each
    so nothing larger than one member is held in memory. Read-only on root."""
    sink = _ChunkSink()
    tar = tarfile.open(fileobj=sink, mode="w|")
    try:
        for full, arc in _entries(root, arcname):
            tar.add(full, arcname=arc, recursive=False)
            data = sink.drain()
            if data:
                yield data
    finally:
        tar.close()
    tail = sink.drain()
    if tail:
        yield tail
