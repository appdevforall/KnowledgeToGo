"""Share gate tests (K2GO-376): the shared contract for granular wireless Share.

Everything runs on loopback, like the export op tests: a share session wraps the export op,
so these cover the gate (token in a header, the scope, the two lifetimes, single-flight,
clean-close delivery) and the per-item transfer reuse. The app forks build against this.
"""

import http.client
import json
import os
import tempfile
import threading
import time
import unittest
from http.server import ThreadingHTTPServer

from cx_core.export_ops import ExportOps
from cx_core.model import AUTHORITATIVE, Dedup, Identity, ManifestItem
from cx_core.ports import ContentAdapter, ExportPlan, ExportStream
from cx_core.registry import Registry
from cx_core.router import dispatch
from cx_core.server import make_handler
from cx_core.share_ops import ShareOps


def _mitem(app, local_ref, total):
    return ManifestItem(
        app=app, kind="zim", title=local_ref, bytes=total,
        identity=Identity(local_ref, Dedup(line={}, build=None, version="", id_kind=AUTHORITATIVE)))


class _DirectFake(ContentAdapter):
    """A direct app (Kiwix / Calibre): nothing to stage, ready at once. ``items`` maps a
    local_ref to its byte size."""

    app = "kiwix"

    def __init__(self, items):
        self._items = dict(items)

    def is_installed(self):
        return True

    def list(self):
        return [_mitem(self.app, ref, total) for ref, total in self._items.items()]

    def prepare_export(self, local_ref):
        if local_ref not in self._items:
            return None
        return ExportPlan(False, self._items[local_ref], local_ref, "application/octet-stream")

    def open_fetch(self, local_ref, staged_dir=None):
        if local_ref not in self._items:
            return None
        return ExportStream(local_ref, "application/octet-stream", [b"x" * self._items[local_ref]])


class _StagingFake(ContentAdapter):
    """A staging app (Kolibri): stage_export writes into dest, optionally gated so a test can
    observe the staging state before the op flips to ready."""

    app = "kolibri"

    def __init__(self, items, gate=None, fail=False):
        self._items = dict(items)
        self._gate = gate
        self._fail = fail

    def is_installed(self):
        return True

    def list(self):
        return [_mitem(self.app, ref, total) for ref, total in self._items.items()]

    def prepare_export(self, local_ref):
        if local_ref not in self._items:
            return None
        return ExportPlan(True, self._items[local_ref], f"{local_ref}.tar", "application/x-tar")

    def stage_export(self, local_ref, dest, should_cancel=None):
        if self._gate is not None:
            self._gate.wait(5)
        if self._fail:
            return False
        with open(os.path.join(dest, "part.bin"), "wb") as f:
            f.write(b"x" * self._items[local_ref])
        return True

    def open_fetch(self, local_ref, staged_dir=None):
        path = os.path.join(staged_dir or "", "part.bin")
        data = b""
        if os.path.isfile(path):
            with open(path, "rb") as f:
                data = f.read()
        return ExportStream(f"{local_ref}.tar", "application/x-tar", [data])


def _share(adapters, staging, cap=3600, conn_ttl=600):
    """Build a share gate over an export op. ``cap`` is the Layer-2 session safety cap; the
    ``conn_ttl`` is the Layer-1 export-op (connection) idle TTL: two separate timeouts."""
    reg = Registry(adapters, import_root=staging, content_volume=staging)
    ops = ExportOps(reg, staging_dir=staging, ttl_seconds=conn_ttl)
    share = ShareOps(reg, ops, safety_cap_seconds=cap)
    return share, ops, reg


def _create(share, items):
    status, body = share.create(items)
    assert status == 200, (status, body)
    return body["sessionId"], body["token"]


def _drain(stream):
    """Drive a stream as the server would on a CLEAN close: on_open, consume the chunks, mark
    completed (the server sets this after the full body), on_close."""
    if stream.on_open:
        stream.on_open()
    try:
        data = b"".join(stream.chunks)
        stream.completed = True
        return data
    finally:
        if stream.on_close:
            stream.on_close()


def _drain_drop(stream):
    """Drive a stream as a MID-STREAM client drop: open, then close without a full read, so
    ``completed`` stays False and the item is not counted delivered."""
    if stream.on_open:
        stream.on_open()
    if stream.on_close:
        stream.on_close()


def _fetch_ready(share, sid, item_ref, token, timeout=5):
    end = time.time() + timeout
    while time.time() < end:
        status, result = share.fetch(sid, item_ref, token)
        if status == 200:
            return result
        if status != 409:
            raise AssertionError(f"unexpected fetch status {status}: {result}")
        time.sleep(0.01)
    raise AssertionError("item never became ready")


def _wait_ready(ops, op_id, timeout=5):
    end = time.time() + timeout
    while ops._ops[op_id].state != "ready" and time.time() < end:
        time.sleep(0.01)


class CreateTest(unittest.TestCase):
    def test_create_returns_session_token_and_pointer(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            status, body = share.create([{"app": "kiwix", "localRef": "a.zim"}])
            self.assertEqual(status, 200)
            self.assertIn("sessionId", body)
            self.assertTrue(body["token"])
            self.assertEqual(body["manifestUrl"], f"/v1/share/{body['sessionId']}/manifest")
            # The pointer carries no token: the token travels as the X-Share-Token header.
            self.assertNotIn(body["token"], body["manifestUrl"])

    def test_distinct_sessions_get_distinct_tokens(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            a = share.create([{"app": "kiwix", "localRef": "a.zim"}])[1]
            b = share.create([{"app": "kiwix", "localRef": "a.zim"}])[1]
            self.assertNotEqual(a["sessionId"], b["sessionId"])
            self.assertNotEqual(a["token"], b["token"])

    def test_empty_or_bad_body_400(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            self.assertEqual(share.create([])[0], 400)
            self.assertEqual(share.create("nope")[0], 400)
            self.assertEqual(share.create([{"app": "kiwix"}])[0], 400)
            self.assertEqual(share.create([{"localRef": "a.zim"}])[0], 400)
            self.assertEqual(share.create([{"app": "kiwix", "localRef": ""}])[0], 400)

    def test_duplicate_items_are_deduped(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            sid, token = _create(share, [
                {"app": "kiwix", "localRef": "a.zim"},
                {"app": "kiwix", "localRef": "a.zim"},
            ])
            _, body = share.manifest(sid, token)
            self.assertEqual(len(body["items"]), 1)


class ManifestTest(unittest.TestCase):
    def test_manifest_lists_scoped_items_with_itemref_total_and_delivered(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10, "b.zim": 25})], d)
            sid, token = _create(share, [
                {"app": "kiwix", "localRef": "a.zim"},
                {"app": "kiwix", "localRef": "b.zim"},
            ])
            status, body = share.manifest(sid, token)
            self.assertEqual(status, 200)
            self.assertEqual([i["itemRef"] for i in body["items"]], ["0", "1"])
            self.assertEqual([i["identity"]["localRef"] for i in body["items"]], ["a.zim", "b.zim"])
            self.assertEqual([i["delivered"] for i in body["items"]], [False, False])
            self.assertEqual(body["totalBytes"], 35)
            self.assertEqual(body["missing"], [])

    def test_manifest_reports_missing_items_partial(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            sid, token = _create(share, [
                {"app": "kiwix", "localRef": "a.zim"},
                {"app": "kiwix", "localRef": "gone.zim"},
                {"app": "nope", "localRef": "x"},
            ])
            _, body = share.manifest(sid, token)
            self.assertEqual([i["identity"]["localRef"] for i in body["items"]], ["a.zim"])
            reasons = {m["reason"] for m in body["missing"]}
            self.assertEqual(reasons, {"not found", "unknown app"})
            self.assertEqual(body["totalBytes"], 10)

    def test_manifest_token_enforcement(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            sid, token = _create(share, [{"app": "kiwix", "localRef": "a.zim"}])
            self.assertEqual(share.manifest(sid, None)[0], 401)       # no token
            self.assertEqual(share.manifest(sid, "")[0], 401)         # empty token
            self.assertEqual(share.manifest(sid, "wrong")[0], 403)    # mismatch
            self.assertEqual(share.manifest("deadbeef", token)[0], 404)  # unknown session
            self.assertEqual(share.manifest(sid, token)[0], 200)      # correct


class FetchTest(unittest.TestCase):
    def test_fetch_direct_item_streams_bytes(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10, "b.zim": 7})], d)
            sid, token = _create(share, [
                {"app": "kiwix", "localRef": "a.zim"},
                {"app": "kiwix", "localRef": "b.zim"},
            ])
            status, stream = share.fetch(sid, "0", token)
            self.assertEqual(status, 200)
            self.assertEqual(len(_drain(stream)), 10)

    def test_fetch_token_enforcement(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            sid, token = _create(share, [{"app": "kiwix", "localRef": "a.zim"}])
            self.assertEqual(share.fetch(sid, "0", None)[0], 401)
            self.assertEqual(share.fetch(sid, "0", "wrong")[0], 403)
            self.assertEqual(share.fetch("deadbeef", "0", token)[0], 404)

    def test_fetch_unknown_itemref_404(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            sid, token = _create(share, [{"app": "kiwix", "localRef": "a.zim"}])
            self.assertEqual(share.fetch(sid, "9", token)[0], 404)   # out of range
            self.assertEqual(share.fetch(sid, "x", token)[0], 404)   # not an index

    def test_fetch_absent_item_404(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            sid, token = _create(share, [{"app": "kiwix", "localRef": "gone.zim"}])
            self.assertEqual(share.fetch(sid, "0", token)[0], 404)

    def test_fetch_staging_item_409_then_streams(self):
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()  # hold staging open
            share, _ops, _reg = _share([_StagingFake({"chan": 120}, gate=gate)], d)
            sid, token = _create(share, [{"app": "kolibri", "localRef": "chan"}])
            status, body = share.fetch(sid, "0", token)
            self.assertEqual(status, 409)
            self.assertEqual(body["state"], "staging")
            gate.set()
            stream = _fetch_ready(share, sid, "0", token)
            self.assertEqual(len(_drain(stream)), 120)

    def test_fetch_staging_error_surfaces_500(self):
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()
            gate.set()  # let staging run, but it fails
            share, _ops, _reg = _share([_StagingFake({"chan": 50}, gate=gate, fail=True)], d)
            sid, token = _create(share, [{"app": "kolibri", "localRef": "chan"}])
            end = time.time() + 5
            seen = None
            while time.time() < end:
                status, body = share.fetch(sid, "0", token)
                if status == 500:
                    seen = body
                    break
                time.sleep(0.01)
            self.assertIsNotNone(seen, "staging error never surfaced")
            self.assertEqual(seen["state"], "error")

    def test_single_flight_rejects_a_second_concurrent_fetch_of_one_item(self):
        # Two open streams on one item would share the child op's temp, and the first close would
        # remove it under the second. The gate single-flights: a second fetch of a busy item gets
        # 409 busy until the first stream closes.
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            sid, token = _create(share, [{"app": "kiwix", "localRef": "a.zim"}])
            status1, stream1 = share.fetch(sid, "0", token)   # claims the slot
            self.assertEqual(status1, 200)
            status2, body2 = share.fetch(sid, "0", token)     # same item still open
            self.assertEqual(status2, 409)
            self.assertEqual(body2["state"], "busy")
            _drain(stream1)                                   # closes the first stream: releases
            status3, stream3 = share.fetch(sid, "0", token)   # free again
            self.assertEqual(status3, 200)
            _drain(stream3)


class CompleteTest(unittest.TestCase):
    def test_complete_closes_cleanly(self):
        # Closer: COMPLETE (the receiver has every item). Supersedes any drop-based auto-close.
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10, "b.zim": 7})], d)
            sid, token = _create(share, [
                {"app": "kiwix", "localRef": "a.zim"},
                {"app": "kiwix", "localRef": "b.zim"},
            ])
            _drain(share.fetch(sid, "0", token)[1])
            _drain(share.fetch(sid, "1", token)[1])
            status, body = share.complete(sid, token)
            self.assertEqual(status, 200)
            self.assertTrue(body["ok"])
            self.assertEqual(share.manifest(sid, token)[0], 404)  # released

    def test_complete_token_enforcement_and_double_complete(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            sid, token = _create(share, [{"app": "kiwix", "localRef": "a.zim"}])
            self.assertEqual(share.complete(sid, None)[0], 401)
            self.assertEqual(share.complete(sid, "wrong")[0], 403)
            self.assertEqual(share.complete("deadbeef", token)[0], 404)
            self.assertEqual(share.complete(sid, token)[0], 200)
            self.assertEqual(share.complete(sid, token)[0], 404)  # already closed


class DeliveredTest(unittest.TestCase):
    def test_delivered_only_on_clean_close_dropped_item_resumes(self):
        # delivered is set on a clean close only. A dropped item stays pending and re-fetches
        # (the resume model): the gate does not close on a drop.
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10, "b.zim": 7})], d)
            sid, token = _create(share, [
                {"app": "kiwix", "localRef": "a.zim"},
                {"app": "kiwix", "localRef": "b.zim"},
            ])
            _drain(share.fetch(sid, "0", token)[1])        # clean -> delivered
            _drain_drop(share.fetch(sid, "1", token)[1])   # drop  -> not delivered
            _, man = share.manifest(sid, token)
            delivered = {i["itemRef"]: i["delivered"] for i in man["items"]}
            self.assertTrue(delivered["0"])
            self.assertFalse(delivered["1"])
            self.assertEqual(share.manifest(sid, token)[0], 200)  # a dropped item did not close it
            # Resume: re-fetch the dropped item, this time cleanly.
            status, stream = share.fetch(sid, "1", token)
            self.assertEqual(status, 200)
            _drain(stream)
            _, man2 = share.manifest(sid, token)
            self.assertTrue({i["itemRef"]: i["delivered"] for i in man2["items"]}["1"])


class CancelTest(unittest.TestCase):
    def test_delete_cancels_and_revokes_child_temp(self):
        # Closer: CANCEL (DELETE). Fetch starts a staging child op (leaves a temp on disk); the
        # cancel revokes the session, the token and the child temp.
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()
            gate.set()
            share, ops, _reg = _share([_StagingFake({"c0": 20, "c1": 30}, gate=gate)], d)
            sid, token = _create(share, [
                {"app": "kolibri", "localRef": "c0"},
                {"app": "kolibri", "localRef": "c1"},
            ])
            share.fetch(sid, "1", token)  # 409 staging, but creates the child op + temp
            op_id = share._sessions[sid].item_ops["1"]
            _wait_ready(ops, op_id)
            temp = ops._ops[op_id].temp
            self.assertTrue(os.path.isdir(temp))
            status, body = share.delete(sid, token)
            self.assertEqual(status, 200)
            self.assertTrue(body["ok"])
            self.assertNotIn(sid, share._sessions)
            self.assertFalse(os.path.exists(temp))         # child temp revoked
            self.assertEqual(share.manifest(sid, token)[0], 404)

    def test_delete_token_enforcement(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            sid, token = _create(share, [{"app": "kiwix", "localRef": "a.zim"}])
            self.assertEqual(share.delete(sid, None)[0], 401)
            self.assertEqual(share.delete(sid, "wrong")[0], 403)
            self.assertIn(sid, share._sessions)  # still open after the rejected closes
            self.assertEqual(share.delete(sid, token)[0], 200)

    def test_double_delete_is_404_already_closed(self):
        # The receiver and the sender onDestroy may both DELETE; the second sees 404.
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            sid, token = _create(share, [{"app": "kiwix", "localRef": "a.zim"}])
            self.assertEqual(share.delete(sid, token)[0], 200)
            self.assertEqual(share.delete(sid, token)[0], 404)


class SafetyCapTest(unittest.TestCase):
    def test_session_survives_past_the_connection_idle(self):
        # The point of the two lifetimes: a paused transfer older than the connection idle TTL
        # (10 min) but younger than the session safety cap (hours) must NOT be reclaimed.
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d, cap=3600, conn_ttl=60)
            sid, token = _create(share, [{"app": "kiwix", "localRef": "a.zim"}])
            share._sessions[sid].last_seen -= 120  # older than conn idle 60, younger than cap 3600
            share._sweep_capped()
            self.assertIn(sid, share._sessions)
            self.assertEqual(share.manifest(sid, token)[0], 200)

    def test_safety_cap_reclaims_abandoned_session_and_temp(self):
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()
            gate.set()
            share, ops, _reg = _share([_StagingFake({"chan": 40}, gate=gate)], d, cap=3600)
            sid, token = _create(share, [{"app": "kolibri", "localRef": "chan"}])
            share.fetch(sid, "0", token)  # 409 staging, leaves a child op + temp
            op_id = share._sessions[sid].item_ops["0"]
            _wait_ready(ops, op_id)
            temp = ops._ops[op_id].temp
            share._sessions[sid].last_seen -= 4000  # abandoned past the cap
            share._sweep_capped()
            self.assertNotIn(sid, share._sessions)
            self.assertFalse(os.path.exists(temp))

    def test_safety_cap_never_reclaims_a_session_with_an_open_stream(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10, "b.zim": 7})], d, cap=3600)
            sid, token = _create(share, [
                {"app": "kiwix", "localRef": "a.zim"},
                {"app": "kiwix", "localRef": "b.zim"},
            ])
            status, stream = share.fetch(sid, "0", token)  # item 0 now streaming
            self.assertEqual(status, 200)
            share._sessions[sid].last_seen -= 4000         # past the cap
            share._sweep_capped()
            self.assertIn(sid, share._sessions)            # streaming: survives
            _drain(stream)                                 # closes: clears streaming
            share._sessions[sid].last_seen -= 4000
            share._sweep_capped()
            self.assertNotIn(sid, share._sessions)         # now abandoned: reclaimed

    def test_startup_sweep_clears_in_memory_sessions(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            sid, _token = _create(share, [{"app": "kiwix", "localRef": "a.zim"}])
            self.assertIn(sid, share._sessions)
            share.sweep_startup()
            self.assertEqual(share._sessions, {})

    def test_boot_reclaims_a_leftover_share_session_and_its_temp(self):
        # Process death: sessions live only in memory, so a fresh process holds none; the
        # session's on-disk footprint is its child export temps, reclaimed by the export op's
        # startup sweep. This mirrors exactly what serve() runs on boot.
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()
            gate.set()
            share, ops, _reg = _share([_StagingFake({"chan": 40}, gate=gate)], d)
            sid, token = _create(share, [{"app": "kolibri", "localRef": "chan"}])
            share.fetch(sid, "0", token)  # leaves a staged cxexport-* temp on disk
            op_id = share._sessions[sid].item_ops["0"]
            _wait_ready(ops, op_id)
            temp = ops._ops[op_id].temp
            self.assertTrue(os.path.isdir(temp))
            # Simulate a restart: a new process, new registries on the same staging dir.
            new_share, new_ops, _ = _share([_StagingFake({"chan": 40})], d)
            new_ops.sweep_startup()     # reclaims the orphan temp
            new_share.sweep_startup()   # holds no sessions
            self.assertFalse(os.path.exists(temp))
            self.assertEqual(new_share._sessions, {})


class ShareRouteTest(unittest.TestCase):
    """The share routes forward to the ShareOps registry; the gate mechanics live above. Here
    we check the routing, the token-via-param wiring (the server reads the X-Share-Token header
    and passes it as dispatch's token), and the missing-share guard."""

    def setUp(self):
        self._d = tempfile.TemporaryDirectory()
        self.share, self.ops, self.reg = _share([_DirectFake({"a.zim": 10, "b.zim": 7})], self._d.name)

    def tearDown(self):
        self._d.cleanup()

    def test_post_create_manifest_fetch_complete(self):
        status, body = dispatch(self.reg, "POST", "/v1/share", {},
                                {"items": [{"app": "kiwix", "localRef": "a.zim"},
                                           {"app": "kiwix", "localRef": "b.zim"}]},
                                ops=self.ops, share=self.share)
        self.assertEqual(status, 200)
        sid, token = body["sessionId"], body["token"]

        status, man = dispatch(self.reg, "GET", f"/v1/share/{sid}/manifest", {},
                               ops=self.ops, share=self.share, token=token)
        self.assertEqual(status, 200)
        self.assertEqual(len(man["items"]), 2)

        status, stream = dispatch(self.reg, "GET", f"/v1/share/{sid}/fetch/0", {},
                                  ops=self.ops, share=self.share, token=token)
        self.assertEqual(status, 200)
        self.assertIsInstance(stream, ExportStream)
        self.assertEqual(len(_drain(stream)), 10)

        status, out = dispatch(self.reg, "POST", f"/v1/share/{sid}/complete", {}, None,
                               ops=self.ops, share=self.share, token=token)
        self.assertEqual(status, 200)
        self.assertTrue(out["ok"])

    def test_delete_route_cancels(self):
        sid, token = _create(self.share, [{"app": "kiwix", "localRef": "a.zim"}])
        status, out = dispatch(self.reg, "DELETE", f"/v1/share/{sid}", {},
                               ops=self.ops, share=self.share, token=token)
        self.assertEqual(status, 200)
        self.assertTrue(out["ok"])

    def test_manifest_without_token_401(self):
        sid, _token = _create(self.share, [{"app": "kiwix", "localRef": "a.zim"}])
        status, _ = dispatch(self.reg, "GET", f"/v1/share/{sid}/manifest", {},
                             ops=self.ops, share=self.share, token=None)
        self.assertEqual(status, 401)

    def test_share_routes_without_share_500(self):
        for method, path, body in [
            ("POST", "/v1/share", {"items": [{"app": "kiwix", "localRef": "a.zim"}]}),
            ("POST", "/v1/share/abc/complete", None),
            ("GET", "/v1/share/abc/manifest", None),
            ("GET", "/v1/share/abc/fetch/0", None),
            ("DELETE", "/v1/share/abc", None),
        ]:
            status, _ = dispatch(self.reg, method, path, {}, body, ops=self.ops, share=None,
                                 token="t")
            self.assertEqual(status, 500, path)


class PreHeaderDropTest(unittest.TestCase):
    """A client that disconnects DURING the response header write must still fire the stream's
    on_close, so the single-flight slot is released and the safety cap can reclaim the session.
    The server writes the headers inside the same try whose finally runs on_close; this checks
    that guarantee through the real handler (white-box, because a real-socket drop timed exactly
    at the header write is not deterministic)."""

    def test_pre_header_disconnect_releases_slot_so_cap_reclaims(self):
        with tempfile.TemporaryDirectory() as d:
            share, ops, reg = _share([_DirectFake({"a.zim": 10})], d, cap=3600)
            sid, token = _create(share, [{"app": "kiwix", "localRef": "a.zim"}])
            status, stream = share.fetch(sid, "0", token)  # claims the single-flight slot
            self.assertEqual(status, 200)
            self.assertIn("0", share._sessions[sid].streaming)

            # A handler whose header write fails, as if the client dropped during the headers.
            handler_cls = make_handler(reg, ops, share)

            class _FailHeaders(handler_cls):
                def send_response(self, *_a, **_k):
                    raise BrokenPipeError("client gone during headers")

            handler = _FailHeaders.__new__(_FailHeaders)  # skip the socket __init__
            handler.close_connection = False
            with self.assertRaises(BrokenPipeError):
                handler._stream(200, stream)

            # on_close ran in the finally despite the header failure: the slot is released and the
            # item is NOT delivered (the body never streamed).
            self.assertNotIn("0", share._sessions[sid].streaming)
            _, man = share.manifest(sid, token)
            self.assertFalse(man["items"][0]["delivered"])
            # With the slot released, the cap reclaims the otherwise-abandoned session.
            share._sessions[sid].last_seen -= 4000
            share._sweep_capped()
            self.assertNotIn(sid, share._sessions)


class ShareHttpTest(unittest.TestCase):
    """End-to-end over a real socket: POST create, GET manifest, GET fetch (chunked stream
    decoded by a real client), POST complete, all with the token in the X-Share-Token header."""

    def setUp(self):
        self._d = tempfile.TemporaryDirectory()
        self.payload = b"x" * 4096
        share, ops, reg = _share([_DirectFake({"a.zim": len(self.payload), "b.zim": 7})], self._d.name)
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(reg, ops, share))
        self.port = self.httpd.server_address[1]
        self._t = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self._t.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self._d.cleanup()

    def _conn(self):
        return http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)

    # Connection: close on each request so the server does not keep a worker thread blocked on
    # the next request line, which otherwise logs a reset when the suite tears the server down.
    def _req(self, method, path, body=None, token=None):
        c = self._conn()
        headers = {"Connection": "close"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if token is not None:
            headers["X-Share-Token"] = token
        c.request(method, path, body=json.dumps(body) if body is not None else None, headers=headers)
        resp = c.getresponse()
        status = resp.status
        out = resp.read()
        c.close()
        return status, (json.loads(out) if out else None)

    def test_round_trip_over_http(self):
        status, created = self._req(
            "POST", "/v1/share",
            {"items": [{"app": "kiwix", "localRef": "a.zim"}, {"app": "kiwix", "localRef": "b.zim"}]})
        self.assertEqual(status, 200)
        sid, token = created["sessionId"], created["token"]
        self.assertEqual(created["manifestUrl"], f"/v1/share/{sid}/manifest")

        status, man = self._req("GET", f"/v1/share/{sid}/manifest", token=token)
        self.assertEqual(status, 200)
        self.assertEqual(man["totalBytes"], len(self.payload) + 7)
        self.assertEqual([i["itemRef"] for i in man["items"]], ["0", "1"])

        c = self._conn()
        c.request("GET", f"/v1/share/{sid}/fetch/0", headers={"Connection": "close", "X-Share-Token": token})
        resp = c.getresponse()
        self.assertEqual(resp.status, 200)
        got = resp.read()
        c.close()
        self.assertEqual(got, self.payload)

        # A clean fetch marks the item delivered in the manifest (resume skips it).
        status, man2 = self._req("GET", f"/v1/share/{sid}/manifest", token=token)
        delivered = {i["itemRef"]: i["delivered"] for i in man2["items"]}
        self.assertTrue(delivered["0"])
        self.assertFalse(delivered["1"])

        status, out = self._req("POST", f"/v1/share/{sid}/complete", token=token)
        self.assertEqual(status, 200)
        self.assertTrue(out["ok"])

        status, _ = self._req("GET", f"/v1/share/{sid}/manifest", token=token)
        self.assertEqual(status, 404)  # released

    def test_token_in_query_is_not_accepted(self):
        # FIX: the token must NOT be read from the URL (it would land in access logs). A query
        # token with no header is unauthenticated.
        _, created = self._req("POST", "/v1/share", {"items": [{"app": "kiwix", "localRef": "a.zim"}]})
        sid, token = created["sessionId"], created["token"]
        status, _ = self._req("GET", f"/v1/share/{sid}/manifest?token={token}")  # no header
        self.assertEqual(status, 401)

    def test_manifest_wrong_token_header_403(self):
        _, created = self._req("POST", "/v1/share", {"items": [{"app": "kiwix", "localRef": "a.zim"}]})
        sid = created["sessionId"]
        status, _ = self._req("GET", f"/v1/share/{sid}/manifest", token="wrong")
        self.assertEqual(status, 403)


if __name__ == "__main__":
    unittest.main()
