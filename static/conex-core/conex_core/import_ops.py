"""Import long-operation registry (K2GO-376).

Importing a channel runs the Kolibri CLI, which takes 35 to 141 s for a real channel. A single
blocking POST times out at the dash-node / nginx relay (its default read timeout is 60 s). So
import is a long operation, like export: start returns an opId at once, the client polls status for
liveness and progress, and a DELETE cancels. Each poll is a short request, so the relay read
timeout never trips.

This reuses the shared OpRegistry (ops_base): the in-flight set, the lazy TTL sweep, the cancel
signal and cleanup. Import adds only its own fields (the ingest result) and the background worker
that runs the adapter's import under the liveness watchdog. Import does not own a staging temp: the
adapter's own work temp ("cximport-") self-cleans on completion, and the startup sweep reclaims any
such orphan a process that died mid-import left behind.
"""

from __future__ import annotations

import os
import shutil
import threading
import time
from dataclasses import dataclass
from typing import Any, Optional

from .importing import is_within_dir
from .ops_base import DONE, ERROR, RUNNING, Op, OpRegistry, Response

# The import work-temp prefix, created by the adapters' import_item. The startup sweep reclaims an
# orphan left by a process that died mid-import (at startup nothing is in-flight).
TEMP_PREFIX = "cximport-"


@dataclass
class _ImportOp(Op):
    moved_bytes: int = 0
    result: Optional[dict] = None
    code: Optional[str] = None  # a structural error code, e.g. "no_space"


def _entry_bytes(entry: Any) -> int:
    """The item's declared size, the progress denominator. 0 when absent or unparsable."""
    try:
        return max(0, int(entry.get("bytes", 0)))
    except (AttributeError, TypeError, ValueError):
        return 0


class ImportOps(OpRegistry):
    """Registry of in-flight import operations. One instance lives for the server's lifetime; the
    handler threads call in concurrently."""

    def __init__(self, registry, import_root: str, ttl_seconds: int = 600):
        super().__init__(ttl_seconds)
        self._registry = registry
        self._import_root = import_root

    # ---- REST-facing operations (return (status, payload)) ----

    def start(self, app: str, entry: Any, payload_path: str) -> Response:
        """Validate the request, start the ingest off the request thread, and return the opId at
        once. The same containment rule as the old synchronous import: the payload must live inside
        the import area, never an arbitrary path."""
        self._sweep_expired()
        if not self._registry.has(app):
            return 404, {"error": f"unknown app: {app}"}
        if not isinstance(entry, dict) or not isinstance(payload_path, str) or not payload_path:
            return 400, {"error": "entry and payloadPath are required"}
        if not is_within_dir(self._import_root, payload_path):
            return 400, {"error": "payloadPath is outside the import area"}
        total = _entry_bytes(entry)
        op_id = self._new_id()
        self._put(op_id, _ImportOp(app=app, state=RUNNING, total_bytes=total))
        worker = threading.Thread(
            target=self._run_import, args=(op_id, app, entry, payload_path, total), daemon=True)
        worker.start()
        return 200, {"opId": op_id, "state": RUNNING, "totalBytes": total}

    def status(self, op_id: str) -> Response:
        """Report the op state, the moved-bytes heartbeat, and the result once done. A 404 means the
        op is unknown (swept or never existed), so the client re-starts."""
        self._sweep_expired()
        with self._lock:
            op = self._ops.get(op_id)
            if op is None:
                return 404, {"error": "unknown op"}
            op.last_seen = time.monotonic()
            payload = {"state": op.state, "movedBytes": op.moved_bytes, "totalBytes": op.total_bytes}
            if op.error:
                payload["error"] = op.error
            if op.code:
                payload["code"] = op.code
            if op.state == DONE and op.result:
                payload["result"] = op.result
        return 200, payload

    # ---- lifecycle ----

    def sweep_startup(self) -> None:
        """Remove every import work-temp on disk. A fresh process holds no import ops, so any
        cximport- dir under the import area is an orphan left by a process that died mid-import."""
        try:
            names = os.listdir(self._import_root)
        except OSError:
            return
        for name in names:
            if name.startswith(TEMP_PREFIX):
                shutil.rmtree(os.path.join(self._import_root, name), ignore_errors=True)

    def _run_import(self, op_id: str, app: str, entry: Any, payload_path: str, total: int) -> None:
        """Ingest the item off the request thread, under the liveness watchdog, then flip the op to
        done (with the result) or error (with the detail, and a structural code when the adapter set
        one, e.g. no_space). If the op was dropped meanwhile (DELETE or sweep), the flip is a no-op;
        the adapter saw should_cancel and stopped its CLI."""
        should_cancel = self.should_cancel(op_id)

        def on_progress(moved: int) -> None:
            # The watchdog reports cumulative syscall bytes, which for an import exceed the payload
            # (read the tar, then write the content), so clamp to the known total: a monotonic bar
            # that fills and holds, never past 100%.
            self._touch(op_id, moved_bytes=min(moved, total) if total else moved)

        try:
            result = self._registry.get(app).import_item(
                entry, payload_path, should_cancel=should_cancel, on_progress=on_progress)
        except Exception as exc:  # any adapter failure surfaces as an op error with its detail
            self._transition(op_id, RUNNING, ERROR, error=f"import failed: {exc}")
            return
        if isinstance(result, dict) and result.get("ok"):
            self._transition(op_id, RUNNING, DONE, result=result, moved_bytes=total)
        else:
            reason = result.get("reason", "import failed") if isinstance(result, dict) else "import failed"
            code = result.get("code") if isinstance(result, dict) else None
            self._transition(op_id, RUNNING, ERROR, error=reason, code=code)
