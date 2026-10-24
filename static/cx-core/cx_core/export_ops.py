"""Export long-operation registry (K2GO-507).

Exporting a large item (a multi-gigabyte Kolibri channel) must stage the whole channel
before the first byte can stream, which takes minutes. A single blocking request times out
on the client. So export becomes a long operation: prepare starts the work and returns an
opId at once, the client polls status for liveness and progress, then fetches the bytes.
The front owns the sequence (ADR D1c); this registry owns the in-flight set and the temp
lifecycle, uniform for every app. The adapters only report a plan, stage into a given
directory and open the stream: the orchestration is here, not in an adapter.

Single source of truth: the op state and the staged bytes are owned by the core. The op
registry is in-memory, so a cx-core restart loses the ops; this is acceptable because the
front recovers by RE-PREPARE and the startup sweep reclaims any orphan temp left by a dead
process. The filesystem is the source for staged bytes; the registry is the source for the
in-flight set. No durable op state, no second owner.

Every op temp has exactly one cleanup owner across the four end states:
  1. normal completion : the fetch stream's on_close drops the op and removes the temp.
  2. client abort       : DELETE drops the op and removes the temp.
  3. abandonment        : a lazy TTL sweep (run at the head of each op request) removes ops
                          whose last_seen is older than the timeout, so an abandoned prepare
                          never orphans a multi-gigabyte temp while the service keeps running.
  4. process death      : a startup sweep removes every op temp left on disk, since a fresh
                          process holds no ops and anything matching the temp prefix is an
                          orphan.
The TTL sweep is lazy (no background timer to own): it needs no thread lifecycle, and the
startup sweep is the backstop for a temp left with no further requests coming.
"""

from __future__ import annotations

import os
import secrets
import shutil
import tempfile
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Dict, Optional, Tuple

from .export import has_room

Response = Tuple[int, Any]

# The op temp prefix. Op-owned and app-agnostic (the op layer creates the temp, not the
# adapter), and distinct from the import flow's "cximport-" temps, so the startup sweep
# reclaims only export op temps and never touches an in-flight import.
TEMP_PREFIX = "cxexport-"

# Prefixes the startup sweep also reclaims for one release: before this change the Kolibri
# adapter staged into "cxkolibri-" temps, so an orphan from an older build (or a device test)
# must still be swept. Not used for the import flow's "cximport-" temps, which self-clean.
_LEGACY_SWEEP_PREFIXES = ("cxkolibri-",)

# How long an op may go without a status or fetch before the lazy sweep reclaims it. The
# client polls status throughout a staging export, so a live op keeps bumping last_seen;
# the timeout only catches an abandoned op.
DEFAULT_TTL_SECONDS = 600

# Op states.
STAGING = "staging"
READY = "ready"
ERROR = "error"


@dataclass
class _Op:
    app: str
    local_ref: str
    temp: Optional[str]  # the staging dir for a staging op, None for a direct app
    state: str
    total_bytes: int
    error: Optional[str] = None
    last_seen: float = field(default_factory=time.monotonic)
    # True while a fetch stream is open on this op. The TTL sweep never reclaims an in-use op,
    # so a multi-gigabyte fetch that streams longer than the TTL (and bumps nothing meanwhile)
    # is not swept out from under tar_stream. Set by fetch() when the stream opens, cleared by
    # dropping the op in the stream's on_close. Lost on process death, but then the op itself
    # is gone and the startup sweep reclaims the orphan temp.
    in_use: bool = False


def _dir_size(path: str) -> int:
    """Sum of the file sizes under path (a staged tree), or 0 when it is gone. This is the
    staging heartbeat: it grows as the export CLI writes, so the client sees progress."""
    total = 0
    for dirpath, _dirnames, filenames in os.walk(path):
        for name in filenames:
            try:
                total += os.path.getsize(os.path.join(dirpath, name))
            except OSError:
                pass
    return total


class ExportOps:
    """Thread-safe registry of in-flight export operations. One instance lives for the
    server's lifetime; the handler threads call into it concurrently."""

    def __init__(self, registry, staging_dir: str, ttl_seconds: int = DEFAULT_TTL_SECONDS):
        self._registry = registry
        self._staging_dir = staging_dir
        self._ttl = ttl_seconds
        self._lock = threading.Lock()
        self._ops: Dict[str, _Op] = {}

    # ---- REST-facing operations (return (status, payload)) ----

    def prepare(self, app: str, local_ref: str) -> Response:
        """Resolve the item, preflight the staging disk, and either start a background
        staging thread (apps that stage) or mark the op ready at once (direct apps)."""
        self._sweep_expired()
        if not self._registry.has(app):
            return 404, {"error": f"unknown app: {app}"}
        plan = self._registry.get(app).prepare_export(local_ref)
        if plan is None:
            return 404, {"error": "item not found or not exportable"}
        if plan.stages and not has_room(self._staging_dir, plan.total_bytes):
            return 507, {"error": "insufficient space to stage export"}
        op_id = secrets.token_hex(16)
        if plan.stages:
            try:
                os.makedirs(self._staging_dir, exist_ok=True)
                temp = tempfile.mkdtemp(prefix=TEMP_PREFIX, dir=self._staging_dir)
            except OSError:
                return 507, {"error": "cannot create staging dir"}
            op = _Op(app, local_ref, temp, STAGING, plan.total_bytes)
            with self._lock:
                self._ops[op_id] = op
            worker = threading.Thread(
                target=self._run_stage, args=(op_id, app, local_ref, temp), daemon=True)
            worker.start()
            return 200, {"opId": op_id, "state": STAGING, "totalBytes": plan.total_bytes}
        op = _Op(app, local_ref, None, READY, plan.total_bytes)
        with self._lock:
            self._ops[op_id] = op
        return 200, {"opId": op_id, "state": READY, "totalBytes": plan.total_bytes}

    def status(self, op_id: str) -> Response:
        """Report the op state plus the staged-bytes heartbeat. A 404 means the op is
        unknown (swept or never existed), so the client re-prepares."""
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
            staged = _dir_size(temp)  # du outside the lock: the walk may be slow
        else:
            staged = 0
        payload = {"state": state, "stagedBytes": staged, "totalBytes": total}
        if error:
            payload["error"] = error
        return 200, payload

    def fetch(self, op_id: str):
        """Open the byte stream for a ready op. Returns (200, ExportStream) for the server
        to stream; the stream's on_close drops the op and removes the temp. 409 when the op
        is not ready, 404 when it is unknown."""
        self._sweep_expired()
        with self._lock:
            op = self._ops.get(op_id)
            if op is None:
                return 404, {"error": "unknown op"}
            op.last_seen = time.monotonic()
            if op.state != READY:
                return 409, {"error": f"op not ready: {op.state}"}
            app, local_ref, temp = op.app, op.local_ref, op.temp
        # Open the stream OUTSIDE the lock. The op is not pinned yet: on_open pins it once the
        # server actually begins streaming, so a failure here (or before streaming starts)
        # leaves the op merely fresh and reclaimable by the normal sweep, never stuck in_use.
        try:
            stream = self._registry.get(app).open_fetch(local_ref, temp)
        except Exception:
            self._finish(op_id)
            return 500, {"error": "failed to open export"}
        if stream is None:
            # The item vanished between prepare and fetch: clean up and report absent.
            self._finish(op_id)
            return 404, {"error": "item not found or not exportable"}
        stream.on_open = lambda: self._pin(op_id)
        stream.on_close = lambda: self._finish(op_id)
        return 200, stream

    def delete(self, op_id: str) -> Response:
        """Client abort: drop the op and remove its temp. Idempotent, so a repeat or an
        unknown op is still 200 {ok: True}."""
        self._sweep_expired()
        self._finish(op_id)
        return 200, {"ok": True}

    # ---- lifecycle ----

    def sweep_startup(self) -> None:
        """Remove every export op temp on disk. The server calls this once on start: a fresh
        process holds no ops, so anything under the staging dir with the op prefix is an
        orphan left by a dead process."""
        try:
            names = os.listdir(self._staging_dir)
        except OSError:
            return
        prefixes = (TEMP_PREFIX,) + _LEGACY_SWEEP_PREFIXES
        for name in names:
            if name.startswith(prefixes):
                shutil.rmtree(os.path.join(self._staging_dir, name), ignore_errors=True)

    def _run_stage(self, op_id: str, app: str, local_ref: str, temp: str) -> None:
        """Stage the item into temp off the request thread, then flip the op to ready or
        error. If the op was dropped meanwhile (DELETE or sweep), the flip is a no-op and the
        temp was already removed by whoever dropped it. The adapter polls ``should_cancel``
        and stops its staging work (terminates the subprocess) once the op is gone, so an
        abort actually cancels the server-side work instead of letting it run on into a
        deleted dir."""
        def should_cancel() -> bool:
            with self._lock:
                return op_id not in self._ops

        try:
            ok = self._registry.get(app).stage_export(local_ref, temp, should_cancel)
        except Exception as exc:  # any adapter / CLI failure surfaces as an op error
            self._set_error(op_id, f"stage failed: {exc}")
            return
        if ok:
            self._set_state(op_id, STAGING, READY)
        else:
            self._set_error(op_id, "staging failed")

    def _set_state(self, op_id: str, expect: str, new: str) -> None:
        with self._lock:
            op = self._ops.get(op_id)
            if op is not None and op.state == expect:
                op.state = new
                op.last_seen = time.monotonic()

    def _set_error(self, op_id: str, message: str) -> None:
        with self._lock:
            op = self._ops.get(op_id)
            if op is not None and op.state == STAGING:
                op.state = ERROR
                op.error = message
                op.last_seen = time.monotonic()

    def _pin(self, op_id: str) -> None:
        """Mark the op in use for the life of an open fetch stream. The server calls this at the
        top of streaming; _finish (the stream's on_close) drops the op and clears the pin with
        it. Keeping the pin here, not in fetch(), means a pre-stream failure never leaves it
        set."""
        with self._lock:
            op = self._ops.get(op_id)
            if op is not None:
                op.in_use = True

    def _finish(self, op_id: str) -> None:
        """Drop the op and remove its temp. The one cleanup primitive, idempotent."""
        with self._lock:
            op = self._ops.pop(op_id, None)
        if op is not None and op.temp:
            shutil.rmtree(op.temp, ignore_errors=True)

    def _sweep_expired(self) -> None:
        now = time.monotonic()
        stale = []
        with self._lock:
            for op_id, op in list(self._ops.items()):
                # Never reclaim an op with an open fetch stream: its temp is in use.
                if not op.in_use and now - op.last_seen > self._ttl:
                    stale.append(self._ops.pop(op_id))
        for op in stale:
            if op.temp:
                shutil.rmtree(op.temp, ignore_errors=True)
