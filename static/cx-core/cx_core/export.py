"""Shared export streaming helpers (Pillar 2, output side).

The core STAGES and STREAMS one item; the front moves the bytes and owns the whole
operation (ADR D1c). These helpers are read-only on the source: they copy bytes out,
they never mutate an app's data.

The tar is streamed file by file (mode "w|", non-seekable), so a large item is never
buffered whole in memory and no second full copy is written to disk.
"""

from __future__ import annotations

import os
import queue
import tarfile
import threading
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


_DONE = object()


def tar_stream(root: str, arcname: str = ".", chunk: int = CHUNK) -> Iterator[bytes]:
    """Stream a tar of a directory tree. tarfile writes into a bounded queue that the
    consumer drains, so a producer that gets ahead BLOCKS: the memory held is a few of
    tarfile's write blocks (tens of KB), never a whole member. So a multi-gigabyte file
    inside the tree streams without being buffered. Read-only on root."""
    q: "queue.Queue" = queue.Queue(maxsize=8)
    error: dict = {}

    class _QueueSink:
        def write(self, data: bytes) -> int:
            if data:
                q.put(bytes(data))  # blocks when full: backpressure bounds memory
            return len(data)

    def produce() -> None:
        try:
            tar = tarfile.open(fileobj=_QueueSink(), mode="w|")
            try:
                for full, arc in _entries(root, arcname):
                    tar.add(full, arcname=arc, recursive=False)
            finally:
                tar.close()
        except BaseException as exc:  # surfaced to the consumer below
            error["exc"] = exc
        finally:
            q.put(_DONE)

    worker = threading.Thread(target=produce, daemon=True)
    worker.start()
    while True:
        item = q.get()
        if item is _DONE:
            break
        yield item
    worker.join()
    if "exc" in error:
        raise error["exc"]
