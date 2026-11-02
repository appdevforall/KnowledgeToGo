"""Shared registry for in-flight long operations (K2GO-376, generalizing K2GO-507).

Export and import are both long operations: a request starts one and returns an opId, the client
polls status for liveness and progress, and a DELETE cancels. The in-flight set, the lazy TTL
abandonment sweep, the cancel signal and the one cleanup primitive are identical for both, so they
live here once. Export adds a staged temp and a fetch stream; import adds a result. Neither
re-implements the registry.

One source of truth: op state is owned by the core and is in-memory, so a restart drops the ops and
the client recovers (export re-prepares, import re-starts); each kind's startup sweep reclaims any
temp an earlier process left on disk. No durable op state, no second owner.

Every op is reclaimed by exactly one of four paths: normal completion (the subclass drops it),
client abort (DELETE), abandonment (the lazy TTL sweep at the head of each request), and process
death (the startup sweep). The TTL sweep is lazy, so there is no background timer to own.
"""

from __future__ import annotations

import secrets
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Dict, Optional, Tuple

Response = Tuple[int, Any]

# Op states. Export moves staging -> ready; import moves running -> done; both can reach error.
STAGING = "staging"
READY = "ready"
RUNNING = "running"
DONE = "done"
ERROR = "error"

# How long an op may go without a status poll before the lazy sweep reclaims it. A live op bumps
# last_seen on every poll, so the timeout only catches an abandoned op.
DEFAULT_TTL_SECONDS = 600


@dataclass
class Op:
    """Base op record. Subclasses add their kind's own fields (export: a temp; import: a result)."""
    app: str
    state: str
    total_bytes: int = 0
    error: Optional[str] = None
    last_seen: float = field(default_factory=time.monotonic)
    # True while a fetch stream is open (export only): the TTL sweep never reclaims an in-use op, so
    # a multi-gigabyte fetch that outlasts the TTL is not swept out from under the stream.
    in_use: bool = False


class OpRegistry:
    """Thread-safe registry of in-flight operations with a lazy TTL sweep. One instance per kind;
    handler threads call in concurrently. A subclass overrides _cleanup to release an op's on-disk
    resource, and builds its own Op subclass."""

    def __init__(self, ttl_seconds: int = DEFAULT_TTL_SECONDS):
        self._ttl = ttl_seconds
        self._lock = threading.Lock()
        self._ops: Dict[str, Op] = {}

    def _new_id(self) -> str:
        return secrets.token_hex(16)

    def _put(self, op_id: str, op: Op) -> None:
        with self._lock:
            self._ops[op_id] = op

    def should_cancel(self, op_id: str):
        """A closure the background worker polls: True once the op is gone (a DELETE or a sweep),
        so the worker stops its child instead of running on into a dropped op."""
        def _gone() -> bool:
            with self._lock:
                return op_id not in self._ops
        return _gone

    def _transition(self, op_id: str, expect: str, new_state: str, **fields: Any) -> None:
        """Move an op from ``expect`` to ``new_state`` and set any extra fields, under the lock. A
        no-op if the op is gone or already moved on (its id is unique, so this only guards against a
        late worker flipping an op that a DELETE already dropped)."""
        with self._lock:
            op = self._ops.get(op_id)
            if op is not None and op.state == expect:
                op.state = new_state
                for name, value in fields.items():
                    setattr(op, name, value)
                op.last_seen = time.monotonic()

    def _touch(self, op_id: str, **fields: Any) -> None:
        """Set fields on an op without changing its state (e.g. a progress update), under the lock."""
        with self._lock:
            op = self._ops.get(op_id)
            if op is not None:
                for name, value in fields.items():
                    setattr(op, name, value)

    def delete(self, op_id: str) -> Response:
        """Client abort: drop the op (its worker sees should_cancel and stops) and clean up. A
        repeat or an unknown op is still 200 {ok: True}."""
        self._sweep_expired()
        self._finish(op_id)
        return 200, {"ok": True}

    def _finish(self, op_id: str) -> None:
        """The one cleanup primitive, idempotent: drop the op and release its resource."""
        with self._lock:
            op = self._ops.pop(op_id, None)
        if op is not None:
            self._cleanup(op)

    def _cleanup(self, op: Op) -> None:
        """Release an op's on-disk resource. The base op owns none; a subclass overrides."""

    def _sweep_expired(self) -> None:
        now = time.monotonic()
        stale = []
        with self._lock:
            for op_id in list(self._ops):
                op = self._ops[op_id]
                # Never reclaim an in-use op (an open fetch stream): its temp is being read.
                if not op.in_use and now - op.last_seen > self._ttl:
                    stale.append(self._ops.pop(op_id))
        for op in stale:
            self._cleanup(op)
