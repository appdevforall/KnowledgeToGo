"""Share gate: the shared contract for granular wireless Share (K2GO-376).

Share is the NETWORK TWIN of Export. The bundle, the manifest and the import path are the
same; only the carrier changes. The receiver PULLS from the sender's live cx-core (the
machine is ON), so the transfer can close itself: this is the whole point of Share over
Clone, whose standalone rsync daemon is mute on the host side and can only be stopped by
the user. See controller/docs/ADR-content-exchange.md, section "Pillar Share".

A share session is a TOKEN plus a SCOPED set of items plus ONE shared lifecycle. It does not
reinvent the transfer: each shared item is a lazily created export op (export_ops.py), so the
staging, the in_use pin and the on_close cleanup are the ones already hardened and device
validated for K2GO-507. The session is the GATE over those child ops: it holds the token and
the scope, it ties the child ops together, and it can revoke them all at once.

Ownership (one source per fact, so this is not a second owner of anything the export op
owns):
  : the session owns the token, the scoped item set and the session liveness.
  : the child export op owns the staged temp, the stream and the per-item liveness.
Both track an "is a stream open" pin, but at different scopes and for different cleanup: the
session pin keeps the gate open for a long single-item transfer; the export op pin keeps that
item's temp from being swept. They are nested scopes, not a duplicated truth.

The gate closes on the FIRST of five independent closers (ADR "Gate / token lifecycle"):
  1. TTL + idle   : last_seen bumps on each manifest / fetch, so an active transfer stays
                    alive and an abandoned one expires. This is the jaw Clone lacks.
  2. startup sweep: a fresh process holds no sessions (they live only in memory); the child
                    temps are cxexport-* reclaimed by the export op's own startup sweep.
  3. DELETE       : the explicit "done" closer (the receiver finished, or tapped Finish).
  4. implicit     : when every scoped item has been delivered, the gate auto-closes.
  5. sender end   : APP-SIDE. The sender's foreground share service calls DELETE in its
                    onDestroy to revoke the gate. The core exposes DELETE; it cannot observe
                    the Android service lifecycle, so this closer is documented here, not
                    implemented in the core.

Default = closed: staying open requires active fetches. The token is bound to the session
(revoking the session revokes the token) and scoped to the item set, so there is no separate
credential lifecycle to leak. The only surface a token holder reaches is /v1/share/* scoped
to this session's items; the wider /v1 API is never widened by a share session.

This slice is loopback only: pairing (QR, hotspot, WLAN) is a later presentation slice and is
outside the core, the way plugging in the Ethernet cable is not part of content exchange.
"""

from __future__ import annotations

import secrets
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Tuple, Union

from .export_ops import DEFAULT_TTL_SECONDS, ExportOps
from .registry import Registry

Response = Tuple[int, Any]


@dataclass
class _Session:
    token: str
    # The scoped item set, as (app, local_ref) pairs. The index is the item's itemRef in the
    # manifest and the fetch URL: an opaque per-session handle, so a Calibre local_ref that
    # contains slashes never lands in a URL path segment.
    items: List[Tuple[str, str]]
    # itemRef -> the child export opId, created lazily on first fetch of that item.
    item_ops: Dict[str, str] = field(default_factory=dict)
    # itemRefs whose stream has closed, for the implicit auto-close (closer 4).
    delivered: set = field(default_factory=set)
    last_seen: float = field(default_factory=time.monotonic)
    # Count of open item streams. The TTL sweep never reclaims a session with a stream open,
    # so a single multi-gigabyte item that streams longer than the TTL (and bumps nothing
    # meanwhile) does not expire the gate under its own transfer.
    in_use: int = 0


class ShareOps:
    """Thread-safe registry of share sessions. One instance lives for the server's lifetime;
    the handler threads call into it concurrently. It wraps the one ExportOps instance and
    delegates every per-item transfer to it."""

    def __init__(self, registry: Registry, export_ops: ExportOps,
                 ttl_seconds: int = DEFAULT_TTL_SECONDS):
        self._registry = registry
        self._export = export_ops
        self._ttl = ttl_seconds
        self._lock = threading.Lock()
        self._sessions: Dict[str, _Session] = {}

    # ---- REST-facing operations (return (status, payload), or (200, ExportStream)) ----

    def create(self, items: Any) -> Response:
        """Open a share session over a scoped item set. Returns the sessionId, an opaque
        token bound to the session, and the manifest pointer. Partial is first-class: the set
        is recorded as requested and each item is resolved at manifest / fetch time, so an
        item that is absent is reported, not fatal."""
        if not isinstance(items, list) or not items:
            return 400, {"error": "body must be {items: [{app, localRef}]} with at least one item"}
        scoped: List[Tuple[str, str]] = []
        seen = set()
        for req in items:
            app = req.get("app") if isinstance(req, dict) else None
            local_ref = req.get("localRef") if isinstance(req, dict) else None
            if not isinstance(app, str) or not isinstance(local_ref, str) or not local_ref:
                return 400, {"error": "each item needs a string app and a non-empty localRef"}
            key = (app, local_ref)
            if key not in seen:
                seen.add(key)
                scoped.append(key)
        session_id = secrets.token_hex(16)
        token = secrets.token_urlsafe(32)
        with self._lock:
            self._sessions[session_id] = _Session(token=token, items=scoped)
        # The pointer carries no token: the QR carries the address, the token and this pointer
        # as separate fields (ADR), and the receiver combines them.
        return 200, {
            "sessionId": session_id,
            "token": token,
            "manifestUrl": f"/v1/share/{session_id}/manifest",
        }

    def manifest(self, session_id: str, token: Any) -> Response:
        """The bundle manifest over the session's scoped set: per-item identity, size and
        upstream hash when present, each tagged with its itemRef. Reuses the read-side item
        JSON, so it is the same manifest shape the disk export builds."""
        session = self._resolve(session_id, token)
        if isinstance(session, tuple):
            return session
        found: List[dict] = []
        missing: List[dict] = []
        total = 0
        for index, (app, local_ref) in enumerate(session.items):
            item_ref = str(index)
            if not self._registry.has(app):
                missing.append({"itemRef": item_ref, "app": app, "localRef": local_ref,
                                "reason": "unknown app"})
                continue
            item = self._registry.get(app).get(local_ref)
            if item is None:
                missing.append({"itemRef": item_ref, "app": app, "localRef": local_ref,
                                "reason": "not found"})
                continue
            entry = item.to_json()
            entry["itemRef"] = item_ref
            found.append(entry)
            total += item.bytes
        return 200, {"sessionId": session_id, "items": found, "missing": missing,
                     "totalBytes": total}

    def fetch(self, session_id: str, item_ref: str, token: Any):
        """Stream one shared item. Lazily creates the child export op the first time an item is
        fetched, then drives it: 409 {state: staging} while a staging app (Kolibri) stages, so
        the receiver retries this same URL until it streams; (200, ExportStream) once ready.
        The stream is wrapped so the session pin and the delivered set track with the export
        op's own pin and temp cleanup."""
        session = self._resolve(session_id, token)
        if isinstance(session, tuple):
            return session
        target = self._item(session, item_ref)
        if target is None:
            return 404, {"error": "unknown item"}
        app, local_ref = target
        # A child op that was swept or already finished returns 404; re-prepare and retry once,
        # so a resumed fetch re-stages instead of dead-ending on a stale opId.
        for attempt in (1, 2):
            op_id = self._ensure_op(session_id, item_ref, app, local_ref)
            if isinstance(op_id, tuple):
                return op_id  # prepare failed: 404 absent, 507 no space
            status, result = self._export.fetch(op_id)
            if status == 404 and attempt == 1:
                self._drop_op(session_id, item_ref, op_id)
                continue
            if status == 409:
                return self._staging_response(op_id, item_ref)
            if status != 200:
                return status, result
            self._wrap_stream(session_id, item_ref, result)
            return 200, result
        return 404, {"error": "item not available"}

    def _staging_response(self, op_id: str, item_ref: str) -> Response:
        """The item is not ready: report it through the one fetch endpoint, since the gate has
        no separate status route (do not widen the API). 500 on a staging error so the receiver
        stops retrying; 409 with the staging heartbeat otherwise so the receiver retries and can
        show progress."""
        st_status, st = self._export.status(op_id)
        if st_status == 200 and st.get("state") == "error":
            return 500, {"state": "error", "itemRef": item_ref, "error": st.get("error")}
        progress = {"state": "staging", "itemRef": item_ref}
        if st_status == 200:
            progress["stagedBytes"] = st.get("stagedBytes", 0)
            progress["totalBytes"] = st.get("totalBytes", 0)
        return 409, progress

    def delete(self, session_id: str, token: Any) -> Response:
        """Close the session now (closer 3, the explicit "done"; also the app-side closer 5).
        Revokes the token with the session and drops every child op + its temp. 404 on an
        unknown or already-closed session, so a double close (receiver Finish plus sender
        onDestroy) is harmless: the second caller sees the gate already closed."""
        self._sweep_expired()
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return 404, {"error": "unknown session"}
            denied = self._check_token(session, token)
            if denied is not None:
                return denied
            self._sessions.pop(session_id, None)
            child_ops = list(session.item_ops.values())
        for op_id in child_ops:
            self._export.delete(op_id)  # drops each child op + removes its staged temp
        return 200, {"ok": True}

    # ---- lifecycle ----

    def sweep_startup(self) -> None:
        """Drop any in-memory sessions on boot (closer 2). A fresh process holds none, so this
        is defensive; the on-disk footprint of a session is its child export temps (cxexport-*),
        reclaimed by the export op's own startup sweep, which the server runs on boot. Reusing
        that single sweep avoids a second temp owner."""
        with self._lock:
            self._sessions.clear()

    # ---- token + item resolution ----

    def _resolve(self, session_id: str, token: Any) -> Union[_Session, Response]:
        """Return the live session, bumping last_seen (closer 1), or an error response: 404 on
        an unknown or expired session, 401 when no token is sent, 403 on a token mismatch."""
        self._sweep_expired()
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return 404, {"error": "unknown session"}
            denied = self._check_token(session, token)
            if denied is not None:
                return denied
            session.last_seen = time.monotonic()
            return session

    @staticmethod
    def _check_token(session: _Session, token: Any):
        """None when the token is valid, else the error response. compare_digest is
        constant-time, so a wrong token leaks no timing signal."""
        if not isinstance(token, str) or token == "":
            return 401, {"error": "token required"}
        if not secrets.compare_digest(token, session.token):
            return 403, {"error": "token mismatch"}
        return None

    @staticmethod
    def _item(session: _Session, item_ref: str):
        """Resolve an itemRef (its index in the scoped set) to (app, local_ref), or None."""
        try:
            index = int(item_ref)
        except (TypeError, ValueError):
            return None
        if 0 <= index < len(session.items):
            return session.items[index]
        return None

    def _ensure_op(self, session_id: str, item_ref: str, app: str, local_ref: str):
        """Return the child export opId for this item, preparing one if none exists. Returns an
        error response tuple when prepare fails (absent item or no staging space)."""
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return 404, {"error": "unknown session"}
            op_id = session.item_ops.get(item_ref)
        if op_id is not None:
            return op_id
        status, payload = self._export.prepare(app, local_ref)
        if status != 200:
            return status, payload
        op_id = payload["opId"]
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                # The session closed while we prepared: drop the orphan op we just made.
                self._export.delete(op_id)
                return 404, {"error": "unknown session"}
            # Another concurrent fetch may have prepared first; keep the first and drop ours.
            existing = session.item_ops.get(item_ref)
            if existing is not None:
                self._export.delete(op_id)
                return existing
            session.item_ops[item_ref] = op_id
        return op_id

    def _drop_op(self, session_id: str, item_ref: str, op_id: str) -> None:
        """Forget a child op mapping whose op has vanished, so the next fetch re-prepares."""
        with self._lock:
            session = self._sessions.get(session_id)
            if session is not None and session.item_ops.get(item_ref) == op_id:
                session.item_ops.pop(item_ref, None)

    def _wrap_stream(self, session_id: str, item_ref: str, stream) -> None:
        """Layer the session pin + delivered bookkeeping onto the export op's own on_open /
        on_close, so the two lifecycles stay a matched pair. The server fires on_open at the
        top of streaming and on_close in the same try's finally, so both pins are always
        released, even on a client disconnect."""
        inner_open = stream.on_open
        inner_close = stream.on_close

        def on_open() -> None:
            self._pin(session_id)
            if inner_open:
                inner_open()

        def on_close() -> None:
            # The export op's close runs first (drops the child op and removes its temp), then
            # the session bookkeeping records the item delivered and may auto-close the gate.
            if inner_close:
                inner_close()
            self._unpin(session_id, item_ref)

        stream.on_open = on_open
        stream.on_close = on_close

    def _pin(self, session_id: str) -> None:
        with self._lock:
            session = self._sessions.get(session_id)
            if session is not None:
                session.in_use += 1

    def _unpin(self, session_id: str, item_ref: str) -> None:
        """Release one stream pin, record the item delivered, and auto-close the gate once
        every scoped item is delivered and no stream is open (closer 4). A mid-stream drop also
        marks the item delivered, so the implicit close is a convenience; DELETE (closer 3) and
        the TTL (closer 1) are the robust closers, and a receiver that still needs an item
        re-pairs (the gate is cheap to re-create)."""
        child_ops: List[str] = []
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return
            if session.in_use > 0:
                session.in_use -= 1
            session.item_ops.pop(item_ref, None)  # the child op finished; a re-fetch re-prepares
            session.delivered.add(item_ref)
            session.last_seen = time.monotonic()
            all_refs = {str(i) for i in range(len(session.items))}
            if session.in_use == 0 and session.delivered.issuperset(all_refs):
                self._sessions.pop(session_id, None)
                child_ops = list(session.item_ops.values())
        for op_id in child_ops:
            self._export.delete(op_id)

    def _sweep_expired(self) -> None:
        now = time.monotonic()
        expired: List[_Session] = []
        with self._lock:
            for session_id, session in list(self._sessions.items()):
                # Never reclaim a session with an open stream: its transfer is live.
                if session.in_use == 0 and now - session.last_seen > self._ttl:
                    expired.append(self._sessions.pop(session_id))
        for session in expired:
            for op_id in list(session.item_ops.values()):
                self._export.delete(op_id)
