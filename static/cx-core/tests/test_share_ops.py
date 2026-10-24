"""Share gate tests (K2GO-376): the shared contract for granular wireless Share.

Everything runs on loopback, like the export op tests: a share session wraps the export op,
so these cover the gate (token, scope, the five closers) and the per-item transfer reuse. The
app forks (sender / receiver) build against this contract.
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


def _share(adapters, staging, ttl=60):
    reg = Registry(adapters, import_root=staging, content_volume=staging)
    ops = ExportOps(reg, staging_dir=staging, ttl_seconds=ttl)
    share = ShareOps(reg, ops, ttl_seconds=ttl)
    return share, ops, reg


def _create(share, items):
    status, body = share.create(items)
    assert status == 200, (status, body)
    return body["sessionId"], body["token"]


def _drain(stream):
    """Drive a stream as the server would: on_open, consume the chunks, on_close."""
    if stream.on_open:
        stream.on_open()
    try:
        return b"".join(stream.chunks)
    finally:
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


class CreateTest(unittest.TestCase):
    def test_create_returns_session_token_and_pointer(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            status, body = share.create([{"app": "kiwix", "localRef": "a.zim"}])
            self.assertEqual(status, 200)
            self.assertIn("sessionId", body)
            self.assertTrue(body["token"])
            self.assertEqual(body["manifestUrl"], f"/v1/share/{body['sessionId']}/manifest")
            # The pointer carries no token: the token travels as a separate field.
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
            self.assertEqual(share.create([{"app": "kiwix"}])[0], 400)  # no localRef
            self.assertEqual(share.create([{"localRef": "a.zim"}])[0], 400)  # no app
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
    def test_manifest_lists_scoped_items_with_itemref_and_total(self):
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
        # The item was in scope at create but is not exportable now: prepare returns None.
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


class AutoCloseTest(unittest.TestCase):
    def test_delivering_every_item_auto_closes_the_gate(self):
        # Closer 4 (implicit progress): once every scoped item has been delivered, the gate
        # closes itself, so a later manifest is 404.
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10, "b.zim": 7})], d)
            sid, token = _create(share, [
                {"app": "kiwix", "localRef": "a.zim"},
                {"app": "kiwix", "localRef": "b.zim"},
            ])
            _drain(share.fetch(sid, "0", token)[1])
            self.assertEqual(share.manifest(sid, token)[0], 200)  # one item left, still open
            _drain(share.fetch(sid, "1", token)[1])
            self.assertEqual(share.manifest(sid, token)[0], 404)  # all delivered: auto-closed


class DeleteTest(unittest.TestCase):
    def test_delete_closes_and_revokes_child_temp(self):
        # Closer 3 (explicit done). Fetch one of two items so the gate does not auto-close,
        # leave a staged child op, then DELETE and confirm the gate and the temp are gone.
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()
            gate.set()
            share, ops, _reg = _share([_StagingFake({"c0": 20, "c1": 30}, gate=gate)], d)
            sid, token = _create(share, [
                {"app": "kolibri", "localRef": "c0"},
                {"app": "kolibri", "localRef": "c1"},
            ])
            # Create a ready-but-unconsumed child op for item 1 (so a temp exists on disk).
            share.fetch(sid, "1", token)  # starts staging
            op_id = share._sessions[sid].item_ops["1"]
            end = time.time() + 5
            while ops._ops[op_id].state != "ready" and time.time() < end:
                time.sleep(0.01)
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
        # The receiver Finish and the sender onDestroy may both DELETE; the second sees 404.
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            sid, token = _create(share, [{"app": "kiwix", "localRef": "a.zim"}])
            self.assertEqual(share.delete(sid, token)[0], 200)
            self.assertEqual(share.delete(sid, token)[0], 404)


class SweepTest(unittest.TestCase):
    def test_ttl_sweep_reclaims_abandoned_session_and_temp(self):
        # Closer 1 (TTL + idle): an abandoned session expires and takes its child temp with it.
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()
            gate.set()
            share, ops, _reg = _share([_StagingFake({"chan": 40}, gate=gate)], d, ttl=60)
            sid, token = _create(share, [{"app": "kolibri", "localRef": "chan"}])
            share.fetch(sid, "0", token)  # starts staging, leaves a child op + temp
            op_id = share._sessions[sid].item_ops["0"]
            end = time.time() + 5
            while ops._ops[op_id].state != "ready" and time.time() < end:
                time.sleep(0.01)
            temp = ops._ops[op_id].temp
            share._sessions[sid].last_seen -= 120  # older than the TTL
            share._sweep_expired()
            self.assertNotIn(sid, share._sessions)
            self.assertFalse(os.path.exists(temp))

    def test_ttl_sweep_never_reclaims_a_session_with_an_open_stream(self):
        # A single large item can stream longer than the TTL without a new request to bump
        # last_seen; the gate must survive under its own transfer (in_use).
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10, "b.zim": 7})], d, ttl=60)
            sid, token = _create(share, [
                {"app": "kiwix", "localRef": "a.zim"},
                {"app": "kiwix", "localRef": "b.zim"},
            ])
            status, stream = share.fetch(sid, "0", token)
            self.assertEqual(status, 200)
            stream.on_open()                               # the server pins at top of streaming
            share._sessions[sid].last_seen -= 120
            share._sweep_expired()
            self.assertIn(sid, share._sessions)            # pinned: survives the sweep
            b"".join(stream.chunks)
            stream.on_close()                              # unpins; item 1 undelivered, stays open
            self.assertIn(sid, share._sessions)
            self.assertEqual(share._sessions[sid].in_use, 0)

    def test_startup_sweep_clears_in_memory_sessions(self):
        with tempfile.TemporaryDirectory() as d:
            share, _ops, _reg = _share([_DirectFake({"a.zim": 10})], d)
            sid, _token = _create(share, [{"app": "kiwix", "localRef": "a.zim"}])
            self.assertIn(sid, share._sessions)
            share.sweep_startup()
            self.assertEqual(share._sessions, {})

    def test_boot_reclaims_a_leftover_share_session_and_its_temp(self):
        # Closer 2 (process death): sessions live only in memory, so a fresh process holds
        # none; the session's on-disk footprint is its child export temps, reclaimed by the
        # export op's startup sweep. This mirrors exactly what serve() runs on boot.
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()
            gate.set()
            share, ops, _reg = _share([_StagingFake({"chan": 40}, gate=gate)], d, ttl=60)
            sid, token = _create(share, [{"app": "kolibri", "localRef": "chan"}])
            share.fetch(sid, "0", token)  # leaves a staged cxexport-* temp on disk
            op_id = share._sessions[sid].item_ops["0"]
            end = time.time() + 5
            while ops._ops[op_id].state != "ready" and time.time() < end:
                time.sleep(0.01)
            temp = ops._ops[op_id].temp
            self.assertTrue(os.path.isdir(temp))
            # Simulate a restart: a new process, new registries on the same staging dir.
            new_share, new_ops, _ = _share([_StagingFake({"chan": 40})], d, ttl=60)
            new_ops.sweep_startup()     # reclaims the orphan temp
            new_share.sweep_startup()   # holds no sessions
            self.assertFalse(os.path.exists(temp))
            self.assertEqual(new_share._sessions, {})


class ShareRouteTest(unittest.TestCase):
    """The share routes forward to the ShareOps registry; the gate mechanics live above. Here
    we check the routing, the token-via-query wiring, and the missing-share guard."""

    def setUp(self):
        self._d = tempfile.TemporaryDirectory()
        self.share, self.ops, self.reg = _share([_DirectFake({"a.zim": 10, "b.zim": 7})], self._d.name)

    def tearDown(self):
        self._d.cleanup()

    def test_post_creates_and_get_manifest_and_fetch_and_delete(self):
        status, body = dispatch(self.reg, "POST", "/v1/share", {},
                                {"items": [{"app": "kiwix", "localRef": "a.zim"},
                                           {"app": "kiwix", "localRef": "b.zim"}]},
                                ops=self.ops, share=self.share)
        self.assertEqual(status, 200)
        sid, token = body["sessionId"], body["token"]

        status, man = dispatch(self.reg, "GET", f"/v1/share/{sid}/manifest",
                               {"token": token}, ops=self.ops, share=self.share)
        self.assertEqual(status, 200)
        self.assertEqual(len(man["items"]), 2)

        status, stream = dispatch(self.reg, "GET", f"/v1/share/{sid}/fetch/0",
                                  {"token": token}, ops=self.ops, share=self.share)
        self.assertEqual(status, 200)
        self.assertIsInstance(stream, ExportStream)
        self.assertEqual(len(_drain(stream)), 10)

        status, out = dispatch(self.reg, "DELETE", f"/v1/share/{sid}",
                               {"token": token}, ops=self.ops, share=self.share)
        self.assertEqual(status, 200)
        self.assertTrue(out["ok"])

    def test_manifest_without_token_401(self):
        sid, _token = _create(self.share, [{"app": "kiwix", "localRef": "a.zim"}])
        status, _ = dispatch(self.reg, "GET", f"/v1/share/{sid}/manifest", {},
                             ops=self.ops, share=self.share)
        self.assertEqual(status, 401)

    def test_share_routes_without_share_500(self):
        for method, path, body in [
            ("POST", "/v1/share", {"items": [{"app": "kiwix", "localRef": "a.zim"}]}),
            ("GET", "/v1/share/abc/manifest", None),
            ("GET", "/v1/share/abc/fetch/0", None),
            ("DELETE", "/v1/share/abc", None),
        ]:
            status, _ = dispatch(self.reg, method, path, {}, body, ops=self.ops, share=None)
            self.assertEqual(status, 500, path)


class ShareHttpTest(unittest.TestCase):
    """End-to-end over a real socket: POST create, GET manifest, GET fetch (chunked stream
    decoded by a real client), DELETE, all with the token as a query parameter."""

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
    _CLOSE = {"Connection": "close"}

    def _json(self, method, path, body=None):
        c = self._conn()
        headers = dict(self._CLOSE)
        if body is not None:
            headers["Content-Type"] = "application/json"
        c.request(method, path, body=json.dumps(body) if body is not None else None, headers=headers)
        resp = c.getresponse()
        status = resp.status
        out = resp.read()
        c.close()
        return status, (json.loads(out) if out else None)

    def test_round_trip_over_http(self):
        status, created = self._json(
            "POST", "/v1/share",
            {"items": [{"app": "kiwix", "localRef": "a.zim"}, {"app": "kiwix", "localRef": "b.zim"}]})
        self.assertEqual(status, 200)
        sid, token = created["sessionId"], created["token"]
        self.assertEqual(created["manifestUrl"], f"/v1/share/{sid}/manifest")

        status, man = self._json("GET", f"/v1/share/{sid}/manifest?token={token}")
        self.assertEqual(status, 200)
        self.assertEqual(man["totalBytes"], len(self.payload) + 7)
        self.assertEqual([i["itemRef"] for i in man["items"]], ["0", "1"])

        c = self._conn()
        c.request("GET", f"/v1/share/{sid}/fetch/0?token={token}", headers=self._CLOSE)
        resp = c.getresponse()
        self.assertEqual(resp.status, 200)
        got = resp.read()
        c.close()
        self.assertEqual(got, self.payload)

        # One of two items fetched: the gate is still open, so an explicit DELETE closes it.
        status, out = self._json("DELETE", f"/v1/share/{sid}?token={token}")
        self.assertEqual(status, 200)
        self.assertTrue(out["ok"])

        status, _ = self._json("GET", f"/v1/share/{sid}/manifest?token={token}")
        self.assertEqual(status, 404)  # closed

    def test_manifest_wrong_token_over_http_403(self):
        _, created = self._json(
            "POST", "/v1/share", {"items": [{"app": "kiwix", "localRef": "a.zim"}]})
        sid = created["sessionId"]
        status, _ = self._json("GET", f"/v1/share/{sid}/manifest?token=wrong")
        self.assertEqual(status, 403)


if __name__ == "__main__":
    unittest.main()
