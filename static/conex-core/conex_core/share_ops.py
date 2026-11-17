"""Share gate: the shared contract for granular wireless Share (K2GO-376).

Share is the NETWORK TWIN of Export. The bundle, the manifest and the import path are the
same; only the carrier changes. The receiver PULLS from the sender's live conex-core (the
machine is ON), so the transfer can close itself: this is the whole point of Share over
Clone, whose standalone rsync daemon is mute on the host side and can only be stopped by
the user. See controller/docs/ADR-content-exchange.md, section "Pillar Share".

Two lifetimes (the refinement that makes large, resumable transfers work; collapsing them
into one short idle TTL was the first-cut error: a multi-hour transfer that pauses would be
reclaimed mid-way):

  CONNECTION (ephemeral, Layer 1): ONE open stream. Its idle TTL and in_use pin belong to the
  CHILD EXPORT OP (export_ops.py), reused per item: a dead socket's op is reclaimed after the
  export idle TTL (~10 min), a live stream is pinned so its temp is never swept under it, and a
  per-item resume is just a re-fetch (the export op re-stages). This gate adds no connection
  TTL of its own; it reuses the export op's.

  SESSION (durable, Layer 2): a TOKEN plus a SCOPED item set plus the per-item delivered state.
  It does NOT expire on the short connection idle. It lives until ONE of:
    : COMPLETE : the receiver signals it has every item (clean success) -> release.
    : CANCEL (DELETE, by host or receiver) : abort; the sender foreground service calls DELETE
      in onDestroy (the app-side closer).
    : a long SAFETY CAP (hours of no activity), so a truly abandoned session is reclaimed; this
      is NOT the ~10-minute connection idle. An active transfer keeps the session fresh.
    : process death : the session is in-memory (v1 decision), so a restart loses it and the
      export op's startup sweep reclaims the child temps on boot.

The session does not reinvent the transfer: each shared item is a lazily created export op, so
staging + the in_use pin + on_close cleanup are the hardened, device-validated K2GO-507 ones.
The session is the GATE over those child ops: it holds the token and the scope, ties the child
ops together, and revokes them all at once on close.

Ownership (one source per fact, so this is not a second owner of anything the export op owns):
  : the session owns the token, the scoped item set, the delivered state and the session's own
    liveness (the safety cap).
  : the child export op owns the staged temp, the stream and the connection liveness (idle TTL
    + in_use pin).
The ``streaming`` set is the session's single truth for "which items have an open stream": it
both single-flights a per-item fetch (so two streams never share one child op's temp) and keeps
the safety cap from reclaiming a session mid-transfer. That is a nested scope of the export
op's own per-op pin, not a duplicated truth.

delivered is set ONLY on a CLEAN item close (the server marks ExportStream.completed after the
full body), never on a mid-stream drop, so a dropped item stays pending and a resume re-fetches
it. The manifest surfaces per-item delivered, so a resuming receiver skips what it already has.
The clean close is signalled; it is never guessed.

The token travels in the ``X-Share-Token`` request header, never the URL, so it stays out of
access logs (it is the only auth). It is opaque, bound to the session (revoking the session
revokes the token) and scoped to the item set. The only surface a token holder reaches is
/v1/share/* scoped to this session's items; the wider /v1 API is never widened.

rsync transport (K2GO-577): when the box runs the share rsync daemon, each item an adapter can
list as files is also a read-only daemon module, named "<sessionId>-<itemRef>", with the session
token as its password. The manifest advertises it per item ({"rsync": {port, module}}), and a
receiver that knows rsync pulls it straight into place (resumable) instead of the HTTP fetch. The
session owns its modules: create adds them, every close (COMPLETE, CANCEL, safety cap) removes
them, and the last close stops the daemon. The receiver reports per-item progress to the gate
(POST .../progress), which keeps the session fresh during a long pull (the daemon itself reports
nothing) and gives the sender its progress view (GET .../progress).

This slice is loopback only: pairing (QR, hotspot, WLAN) is a later presentation slice and is
outside the core, the way plugging in the Ethernet cable is not part of content exchange.
"""

from __future__ import annotations

import secrets
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple, Union

from .registry import Registry
from .rsync_share import Module

Response = Tuple[int, Any]

# How long a session may go with NO activity before the safety cap reclaims it. Hours, not the
# export op's ~10-minute connection idle: a paused transfer that resumes within the window
# survives, and a truly abandoned session is eventually reclaimed. The cap is a backstop; the
# normal closes are COMPLETE and CANCEL.
DEFAULT_SAFETY_CAP_SECONDS = 6 * 3600

# How long a closed session still answers GET .../progress with how it ended (K2GO-577). The
# sender polls about once a second, so minutes are plenty; the cap bounds memory.
ENDED_TTL_SECONDS = 15 * 60
ENDED_MAX = 64


@dataclass
class _Session:
    token: str
    # The scoped item set, as (app, local_ref) pairs. The index is the item's itemRef in the
    # manifest and the fetch URL: an opaque per-session handle, so a Calibre local_ref that
    # contains slashes never lands in a URL path segment.
    items: List[Tuple[str, str]]
    # itemRef -> the child export opId, created lazily on first fetch of that item.
    item_ops: Dict[str, str] = field(default_factory=dict)
    # itemRefs delivered on a CLEAN stream close (full body). Surfaced in the manifest for resume.
    delivered: set = field(default_factory=set)
    # itemRefs with an open (or opening) stream: single-flight + "do not cap mid-transfer".
    streaming: set = field(default_factory=set)
    # Last activity, for the safety cap only (bumped on manifest / fetch / progress / complete).
    last_seen: float = field(default_factory=time.monotonic)
    # itemRef -> the rsync daemon module that serves it (K2GO-577). Empty without the daemon.
    modules: Dict[str, Module] = field(default_factory=dict)
    # itemRef -> the receiver's last progress report {movedBytes, totalBytes, state}.
    progress: Dict[str, dict] = field(default_factory=dict)


class ShareOps:
    """Thread-safe registry of share sessions. One instance lives for the server's lifetime;
    the handler threads call into it concurrently. It wraps the one ExportOps instance and
    delegates every per-item transfer (the connection lifetime) to it."""

    def __init__(self, registry: Registry, export_ops,
                 safety_cap_seconds: int = DEFAULT_SAFETY_CAP_SECONDS, rsync=None):
        self._registry = registry
        self._export = export_ops
        self._cap = safety_cap_seconds
        self._rsync = rsync  # the RsyncDaemon, or None: share over HTTP fetch only
        self._lock = threading.Lock()
        self._sessions: Dict[str, _Session] = {}
        # session_id -> {token, state, items, at}: how a closed session ended, so the sender's
        # last poll learns "completed" instead of a bare 404 (K2GO-577). Dropped after
        # ENDED_TTL_SECONDS; never more than ENDED_MAX entries.
        self._ended: Dict[str, dict] = {}
        # Serializes snapshot + apply, so a stale module list never lands after a newer one (a
        # closed session's module must not keep serving).
        self._sync_lock = threading.Lock()

    # ---- REST-facing operations (return (status, payload), or (200, ExportStream)) ----

    def create(self, items: Any) -> Response:
        """Open a share session over a scoped item set. Returns the sessionId, an opaque token
        bound to the session, and the manifest pointer. Partial is first-class: the set is
        recorded as requested and each item is resolved at manifest / fetch time, so an absent
        item is reported, not fatal."""
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
        session = _Session(token=token, items=scoped)
        session.modules = self._modules_for(session_id, token, scoped)
        with self._lock:
            self._sessions[session_id] = session
        self._sync_daemon()
        # The pointer carries no token: the QR carries the address, the token and this pointer
        # as separate fields (ADR), and the receiver sends the token as the X-Share-Token header.
        return 200, {
            "sessionId": session_id,
            "token": token,
            "manifestUrl": f"/v1/share/{session_id}/manifest",
        }

    def manifest(self, session_id: str, token: Any) -> Response:
        """The bundle manifest over the session's scoped set: per-item identity, size and
        upstream hash when present, each tagged with its itemRef and a ``delivered`` flag.
        Reuses the read-side item JSON, so it is the same manifest shape the disk export builds.

        ``delivered`` is ADVISORY: it means the SENDER streamed this item to completion once (a
        clean stream close), NOT that the receiver persisted it. It is a progress / resume HINT.
        The receiver's resume authority must be its OWN persisted state, so it never skips an item
        it received but failed to save."""
        session = self._resolve(session_id, token)
        if isinstance(session, tuple):
            return session
        found: List[dict] = []
        missing: List[dict] = []
        total = 0
        serving = self._ensure_serving(session)
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
            entry["delivered"] = item_ref in session.delivered
            module = session.modules.get(item_ref)
            if module is not None and serving:
                entry["rsync"] = {"port": self._rsync.port, "module": module.name}
            found.append(entry)
            total += item.bytes
        return 200, {"sessionId": session_id, "items": found, "missing": missing,
                     "totalBytes": total}

    def fetch(self, session_id: str, item_ref: str, token: Any):
        """Stream one shared item. Lazily creates the child export op the first time an item is
        fetched, then drives it: 409 {state: staging} while a staging app (Kolibri) stages (the
        receiver retries this same URL), 409 {state: busy} when the item already has an open
        stream (single-flight), (200, ExportStream) once ready. The stream's on_close releases
        the single-flight slot and, on a clean close, records the item delivered."""
        session = self._resolve(session_id, token)
        if isinstance(session, tuple):
            return session
        target = self._item(session, item_ref)
        if target is None:
            return 404, {"error": "unknown item"}
        app, local_ref = target
        # Single-flight per item: one open stream at a time, so two fetches never stream the same
        # child op's temp (the first close removes it under the second). A second concurrent
        # fetch of the same item gets 409 busy and retries. Claim the slot before touching the
        # export op; the stream's on_close releases it on the 200 path, the finally on every
        # other path.
        with self._lock:
            live = self._sessions.get(session_id)
            if live is None:
                return 404, {"error": "unknown session"}
            if item_ref in live.streaming:
                return 409, {"state": "busy", "itemRef": item_ref}
            live.streaming.add(item_ref)
        keep = False
        try:
            # A child op swept or already finished returns 404; re-prepare and retry once, so a
            # resumed fetch re-stages instead of dead-ending on a stale opId.
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
                self._wrap_close(session_id, item_ref, result)
                keep = True  # the stream now owns the slot; its on_close releases it
                return 200, result
            return 404, {"error": "item not available"}
        finally:
            if not keep:
                self._release_streaming(session_id, item_ref)

    def report_progress(self, session_id: str, token: Any, body: Any) -> Response:
        """The receiver reports one item's progress {itemRef, movedBytes, totalBytes, state}.
        Advisory, like ``delivered``: it feeds the sender's view and keeps the session fresh; a
        state "done" also marks the item delivered."""
        session = self._resolve(session_id, token)
        if isinstance(session, tuple):
            return session
        if not isinstance(body, dict):
            return 400, {"error": "body must be {itemRef, movedBytes, totalBytes, state}"}
        item_ref = body.get("itemRef")
        if not isinstance(item_ref, str) or self._item(session, item_ref) is None:
            return 404, {"error": "unknown item"}
        report = {}
        for key in ("movedBytes", "totalBytes"):
            value = body.get(key)
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                return 400, {"error": f"{key} must be a non-negative integer"}
            report[key] = value
        state = body.get("state")
        if state not in ("running", "done", "error"):
            return 400, {"error": "state must be running, done or error"}
        report["state"] = state
        if state == "running":
            self._ensure_serving(session)  # a Retry pull needs the daemon up
        with self._lock:
            session.progress[item_ref] = report
            if state == "done":
                session.delivered.add(item_ref)
        return 200, {"ok": True}

    def progress(self, session_id: str, token: Any) -> Response:
        """The sender's view: the last progress the receiver reported per item, and the session
        state. "open" while the session lives; after a close, for ENDED_TTL_SECONDS, how it ended
        ("completed", "cancelled" or "expired") with the last items (K2GO-577)."""
        session = self._resolve(session_id, token)
        if isinstance(session, tuple):
            ended = self._ended_view(session_id, token)
            return ended if ended is not None else session
        with self._lock:
            items = self._snapshot(session)
        return 200, {"sessionId": session_id, "state": "open", "items": items}

    def complete(self, session_id: str, token: Any) -> Response:
        """Clean-success close: the receiver has every item it wanted and releases the session.
        This is the receiver-driven close (supersedes any drop-based auto-close). Revokes the
        token and every child op + temp. 404 on an unknown or already-closed session."""
        return self._close(session_id, token, "completed")

    def delete(self, session_id: str, token: Any) -> Response:
        """Cancel (abort) close, by the host or the receiver; also the app-side closer (the
        sender's foreground service calls this in onDestroy). Revokes the token and every child
        op + temp. 404 on an unknown or already-closed session, so a double close (receiver and
        sender) is harmless: the second caller sees the gate already closed."""
        return self._close(session_id, token, "cancelled")

    # ---- lifecycle ----

    def sweep_startup(self) -> None:
        """Drop any in-memory sessions on boot. A fresh process holds none, so this is
        defensive; the on-disk footprint of a session is its child export temps (cxexport-*),
        reclaimed by the export op's own startup sweep, which the server runs on boot. Reusing
        that single sweep avoids a second temp owner."""
        with self._lock:
            self._sessions.clear()
        if self._rsync is not None:
            self._rsync.sweep_startup()

    # ---- token + item resolution ----

    def _resolve(self, session_id: str, token: Any) -> Union[_Session, Response]:
        """Return the live session, bumping last_seen (keeps an active session off the safety
        cap), or an error response: 404 on an unknown or expired session, 401 when no token is
        sent, 403 on a token mismatch."""
        self._sweep_capped()
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return 404, {"error": "unknown session"}
            denied = self._check_token(session, token)
            if denied is not None:
                return denied
            session.last_seen = time.monotonic()
            return session

    def _close(self, session_id: str, token: Any, state: str) -> Response:
        """Resolve + token-check + revoke. The single close primitive behind COMPLETE and
        CANCEL: both revoke the token and the child ops; they differ only in the app's intent,
        which is kept as the ended ``state`` for the sender's last poll."""
        self._sweep_capped()
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return 404, {"error": "unknown session"}
            denied = self._check_token(session, token)
            if denied is not None:
                return denied
            self._sessions.pop(session_id, None)
            self._remember_ended(session_id, session, state)
            child_ops = list(session.item_ops.values())
        for op_id in child_ops:
            self._export.delete(op_id)  # drops each child op + removes its staged temp
        if session.modules:
            self._sync_daemon()  # revoke its rsync modules too (and stop an idle daemon)
        return 200, {"ok": True}

    @staticmethod
    def _snapshot(session: _Session) -> List[dict]:
        """The per-item view, under the lock: every reported item, plus items delivered over the
        HTTP fetch (no progress report) as delivered-only rows."""
        refs = set(session.progress) | set(session.delivered)
        items = []
        for ref in sorted(refs, key=int):
            row = dict(session.progress.get(ref, {}))
            row["itemRef"] = ref
            row["delivered"] = ref in session.delivered
            items.append(row)
        return items

    def _remember_ended(self, session_id: str, session: _Session, state: str) -> None:
        """Record how a session ended (caller holds the lock). Expired entries go first, then
        the oldest when the cap is reached."""
        now = time.monotonic()
        for sid, ended in list(self._ended.items()):
            if now - ended["at"] > ENDED_TTL_SECONDS:
                del self._ended[sid]
        while len(self._ended) >= ENDED_MAX:
            del self._ended[min(self._ended, key=lambda sid: self._ended[sid]["at"])]
        self._ended[session_id] = {"token": session.token, "state": state,
                                   "items": self._snapshot(session), "at": now}

    def _ended_view(self, session_id: str, token: Any) -> Optional[Response]:
        """How a closed session ended, for its own token only; None when unknown or too old."""
        with self._lock:
            ended = self._ended.get(session_id)
            if ended is None or time.monotonic() - ended["at"] > ENDED_TTL_SECONDS:
                return None
            if not isinstance(token, str) or not secrets.compare_digest(token, ended["token"]):
                return None
            return 200, {"sessionId": session_id, "state": ended["state"],
                         "items": [dict(row) for row in ended["items"]]}

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
                # The session closed (CANCEL / cap) while we prepared: drop the orphan op we
                # just made so its temp is not leaked.
                self._export.delete(op_id)
                return 404, {"error": "unknown session"}
            # Single-flight guarantees no concurrent fetch of this same item, so item_ops has no
            # entry for it here; store ours as the one child op for the item.
            session.item_ops[item_ref] = op_id
        return op_id

    def _drop_op(self, session_id: str, item_ref: str, op_id: str) -> None:
        """Forget a child op mapping whose op has vanished, so the next fetch re-prepares."""
        with self._lock:
            session = self._sessions.get(session_id)
            if session is not None and session.item_ops.get(item_ref) == op_id:
                session.item_ops.pop(item_ref, None)

    def _staging_response(self, op_id: str, item_ref: str) -> Response:
        """The item is not ready: report it through the one fetch endpoint, since the gate has
        no separate status route (do not widen the API). 500 on a staging error so the receiver
        stops retrying; 409 {state: staging} with the heartbeat otherwise so it retries and can
        show progress."""
        st_status, st = self._export.status(op_id)
        if st_status == 200 and st.get("state") == "error":
            return 500, {"state": "error", "itemRef": item_ref, "error": st.get("error")}
        progress = {"state": "staging", "itemRef": item_ref}
        if st_status == 200:
            progress["stagedBytes"] = st.get("stagedBytes", 0)
            progress["totalBytes"] = st.get("totalBytes", 0)
        return 409, progress

    def _wrap_close(self, session_id: str, item_ref: str, stream) -> None:
        """Layer the single-flight release + delivered bookkeeping onto the export op's own
        on_close. The server fires on_close in the same try's finally that opens the stream, so
        it always runs, even on a client disconnect. on_open stays the export op's (its per-op
        pin); only on_close needs the session bookkeeping."""
        inner_close = stream.on_close

        def on_close() -> None:
            # The export op's close runs first (drops the child op and removes its temp), then
            # the session records the outcome. A clean close (full body) marks the item
            # delivered; a mid-stream drop leaves it pending so a resume re-fetches it.
            if inner_close:
                inner_close()
            self._on_stream_close(session_id, item_ref, bool(getattr(stream, "completed", False)))

        stream.on_close = on_close

    def _on_stream_close(self, session_id: str, item_ref: str, completed: bool) -> None:
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return
            session.streaming.discard(item_ref)
            session.item_ops.pop(item_ref, None)  # the child op finished; a re-fetch re-prepares
            session.last_seen = time.monotonic()
            if completed:
                session.delivered.add(item_ref)

    def _release_streaming(self, session_id: str, item_ref: str) -> None:
        """Release a single-flight slot claimed by a fetch that did not open a stream (staging,
        error, or absent)."""
        with self._lock:
            session = self._sessions.get(session_id)
            if session is not None:
                session.streaming.discard(item_ref)

    def _sweep_capped(self) -> None:
        now = time.monotonic()
        capped: List[_Session] = []
        with self._lock:
            for session_id, session in list(self._sessions.items()):
                # Never reclaim a session with an open stream: its transfer is live. Otherwise an
                # abandoned session (no activity past the safety cap) is reclaimed.
                if not session.streaming and now - session.last_seen > self._cap:
                    capped.append(self._sessions.pop(session_id))
                    self._remember_ended(session_id, session, "expired")
        for session in capped:
            for op_id in list(session.item_ops.values()):
                self._export.delete(op_id)
        if any(session.modules for session in capped):
            self._sync_daemon()

    # ---- rsync daemon (K2GO-577) ----

    def _modules_for(self, session_id: str, token: str, items) -> Dict[str, Module]:
        """One daemon module per item the adapter can list as files. Empty without the daemon."""
        if self._rsync is None:
            return {}
        modules: Dict[str, Module] = {}
        for index, (app, local_ref) in enumerate(items):
            if not self._registry.has(app):
                continue
            files = self._registry.get(app).share_files(local_ref)
            if files is not None:
                item_ref = str(index)
                modules[item_ref] = Module(name=f"{session_id}-{item_ref}", root=files.root,
                                           files=list(files.files), secret=token)
        return modules

    def _ensure_serving(self, session: _Session) -> bool:
        """True when the daemon serves this session's modules, restarting a daemon that died while
        the session was open (set_modules restarts a dead process)."""
        if self._rsync is None or not session.modules:
            return False
        if not self._rsync.running():
            self._sync_daemon()
        return self._rsync.running()

    def _sync_daemon(self) -> None:
        """Make the daemon serve exactly the modules of the live sessions."""
        if self._rsync is None:
            return
        with self._sync_lock:
            with self._lock:
                modules = [m for session in self._sessions.values()
                           for m in session.modules.values()]
            self._rsync.set_modules(modules)
