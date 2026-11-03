"""Export long-operation registry (K2GO-507).

Exporting a large item (a multi-gigabyte Kolibri channel) must stage the whole channel before the
first byte can stream, which takes minutes. A single blocking request times out on the client. So
export is a long operation: prepare starts the work and returns an opId at once, the client polls
status for liveness and progress, then fetches the bytes. The front owns the sequence (ADR D1c).

The in-flight set, the lazy TTL sweep, the cancel signal and the one cleanup primitive are shared
with import in OpRegistry (ops_base). This module adds only what export needs: a staged temp per op,
the staged-bytes heartbeat, and the fetch stream.

Every op temp has exactly one cleanup owner across four end states: normal completion (the fetch
stream's on_close drops the op and removes the temp), client abort (DELETE), abandonment (the lazy
TTL sweep), and process death (the startup sweep reclaims every export op temp on disk, since a
fresh process holds no ops).
"""

from __future__ import annotations

import os
import shutil
import tempfile
import threading
import time
from dataclasses import dataclass
from typing import Optional

from .ops_base import ERROR, READY, STAGING, Op, OpRegistry, Response
from .watchdog import dir_size

# The op temp prefix. Op-owned and app-agnostic, and distinct from the import flow's "cximport-"
# temps, so the export startup sweep reclaims only export op temps.
TEMP_PREFIX = "cxexport-"

# Prefixes the startup sweep also reclaims for one release: before this change the Kolibri adapter
# staged into "cxkolibri-" temps, so an orphan from an older build (or a device test) must still be
# swept. The import flow's "cximport-" temps are reclaimed by the import registry's own sweep.
_LEGACY_SWEEP_PREFIXES = ("cxkolibri-",)


@dataclass
class _ExportOp(Op):
    local_ref: str = ""
    temp: Optional[str] = None  # the staging dir for a staging op, None for a direct app


class ExportOps(OpRegistry):
    """Registry of in-flight export operations. One instance lives for the server's lifetime."""

    def __init__(self, registry, staging_dir: str, ttl_seconds: int = 600):
        super().__init__(ttl_seconds)
        self._registry = registry
        self._staging_dir = staging_dir

    # ---- REST-facing operations (return (status, payload)) ----

    def prepare(self, app: str, local_ref: str) -> Response:
        """Resolve the item, then either start a background staging thread (apps that stage) or mark
        the op ready at once (direct apps)."""
        self._sweep_expired()
        if not self._registry.has(app):
            return 404, {"error": f"unknown app: {app}"}
        plan = self._registry.get(app).prepare_export(local_ref)
        if plan is None:
            return 404, {"error": "item not found or not exportable"}
        # No free-space preflight: the box runs under proot, whose statvfs under-reports the free
        # space on the content volume, so a check here false-rejected valid exports. A genuine ENOSPC
        # during staging surfaces as an op error through _run_stage, and the temp is swept.
        op_id = self._new_id()
        if plan.stages:
            try:
                os.makedirs(self._staging_dir, exist_ok=True)
                temp = tempfile.mkdtemp(prefix=TEMP_PREFIX, dir=self._staging_dir)
            except OSError:
                return 507, {"error": "cannot create staging dir"}
            self._put(op_id, _ExportOp(app=app, state=STAGING, total_bytes=plan.total_bytes,
                                       local_ref=local_ref, temp=temp))
            worker = threading.Thread(
                target=self._run_stage, args=(op_id, app, local_ref, temp), daemon=True)
            worker.start()
            return 200, {"opId": op_id, "state": STAGING, "totalBytes": plan.total_bytes}
        self._put(op_id, _ExportOp(app=app, state=READY, total_bytes=plan.total_bytes,
                                   local_ref=local_ref, temp=None))
        return 200, {"opId": op_id, "state": READY, "totalBytes": plan.total_bytes}

    def status(self, op_id: str) -> Response:
        """Report the op state plus the staged-bytes heartbeat. A 404 means the op is unknown
        (swept or never existed), so the client re-prepares."""
        self._sweep_expired()
        with self._lock:
            op = self._ops.get(op_id)
            if op is None:
                return 404, {"error": "unknown op"}
            op.last_seen = time.monotonic()
            state, total, temp, error = op.state, op.total_bytes, op.temp, op.error
        if state == READY:
            staged = total
        elif temp:
            staged = dir_size(temp)  # du outside the lock: the walk may be slow
        else:
            staged = 0
        payload = {"state": state, "stagedBytes": staged, "totalBytes": total}
        if error:
            payload["error"] = error
        return 200, payload

    def fetch(self, op_id: str):
        """Open the byte stream for a ready op. Returns (200, ExportStream); the stream's on_close
        drops the op and removes the temp. 409 when not ready, 404 when unknown."""
        self._sweep_expired()
        with self._lock:
            op = self._ops.get(op_id)
            if op is None:
                return 404, {"error": "unknown op"}
            op.last_seen = time.monotonic()
            if op.state != READY:
                return 409, {"error": f"op not ready: {op.state}"}
            app, local_ref, temp = op.app, op.local_ref, op.temp
        # Open the stream OUTSIDE the lock. The op is not pinned yet: on_open pins it once the server
        # actually begins streaming, so a failure here leaves the op merely fresh and reclaimable by
        # the normal sweep, never stuck in_use.
        try:
            stream = self._registry.get(app).open_fetch(local_ref, temp)
        except Exception:
            self._finish(op_id)
            return 500, {"error": "failed to open export"}
        if stream is None:
            self._finish(op_id)
            return 404, {"error": "item not found or not exportable"}
        stream.on_open = lambda: self._pin(op_id)
        stream.on_close = lambda: self._finish(op_id)
        return 200, stream

    # ---- lifecycle ----

    def sweep_startup(self) -> None:
        """Remove every export op temp on disk. A fresh process holds no ops, so anything under the
        staging dir with an export op prefix is an orphan left by a dead process."""
        try:
            names = os.listdir(self._staging_dir)
        except OSError:
            return
        prefixes = (TEMP_PREFIX,) + _LEGACY_SWEEP_PREFIXES
        for name in names:
            if name.startswith(prefixes):
                shutil.rmtree(os.path.join(self._staging_dir, name), ignore_errors=True)

    def _run_stage(self, op_id: str, app: str, local_ref: str, temp: str) -> None:
        """Stage the item into temp off the request thread, then flip the op to ready or error. If
        the op was dropped meanwhile (DELETE or sweep), the flip is a no-op and the temp was already
        removed by whoever dropped it. The adapter polls should_cancel and stops its staging work
        once the op is gone, so an abort actually cancels the server-side work."""
        should_cancel = self.should_cancel(op_id)
        try:
            ok = self._registry.get(app).stage_export(local_ref, temp, should_cancel)
        except Exception as exc:  # any adapter / CLI failure surfaces as an op error with its detail
            self._transition(op_id, STAGING, ERROR, error=f"stage failed: {exc}")
            return
        if ok:
            self._transition(op_id, STAGING, READY)
        else:
            self._transition(op_id, STAGING, ERROR, error="staging failed")

    def _pin(self, op_id: str) -> None:
        """Mark the op in use for the life of an open fetch stream. The server calls this at the top
        of streaming; _finish (the stream's on_close) drops the op and clears the pin with it."""
        with self._lock:
            op = self._ops.get(op_id)
            if op is not None:
                op.in_use = True

    def _cleanup(self, op: Op) -> None:
        """Remove the op's staging temp, if it owns one."""
        temp = getattr(op, "temp", None)
        if temp:
            shutil.rmtree(temp, ignore_errors=True)
