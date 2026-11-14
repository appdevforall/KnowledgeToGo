import http.client
import json
import os
import tempfile
import threading
import unittest
import uuid
from http.server import ThreadingHTTPServer

from conex_core import VERSION
from conex_core.adapters.kiwix import KiwixAdapter
from conex_core.export_ops import ExportOps
from conex_core.registry import Registry
from conex_core.server import make_handler
from conex_core.share_ops import ShareOps


def _zim(path, body_len=500):
    hdr = (0x044D495A).to_bytes(4, "little") + b"\x06\x00\x00\x00" + uuid.uuid4().bytes
    data = hdr + os.urandom(body_len)
    with open(path, "wb") as f:
        f.write(data)
    return data


class ServerHttpTest(unittest.TestCase):
    """End-to-end over a real socket with http.client, so the chunked export stream is
    decoded by a real HTTP client: a wrong HTTP version would leak the chunk-size lines into
    the body and the reassembled bytes would not match the source."""

    def setUp(self):
        self._dir = tempfile.TemporaryDirectory()
        self.zim_name = "wikipedia_en_all_2024-01.zim"
        self.zim_bytes = _zim(os.path.join(self._dir.name, self.zim_name), body_len=200000)
        reg = Registry([KiwixAdapter(self._dir.name)])
        ops = ExportOps(reg, staging_dir=self._dir.name, ttl_seconds=60)
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(reg, ops))
        self.port = self.httpd.server_address[1]
        self._t = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self._t.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self._dir.cleanup()

    def _conn(self):
        return http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)

    def test_export_longop_stream_matches_source(self):
        # Prepare -> status (ready, direct app) -> fetch over a real socket, so the chunked
        # stream is decoded by a real client (K2GO-507 flow, Kiwix = direct).
        c = self._conn()
        body = json.dumps({"app": "kiwix", "localRef": self.zim_name})
        c.request("POST", "/v1/export/prepare", body=body,
                  headers={"Content-Type": "application/json"})
        prep = json.loads(c.getresponse().read())
        c.close()
        self.assertEqual(prep["state"], "ready")
        self.assertEqual(prep["totalBytes"], len(self.zim_bytes))
        op_id = prep["opId"]

        c = self._conn()
        c.request("GET", f"/v1/export/status/{op_id}")
        st = json.loads(c.getresponse().read())
        c.close()
        self.assertEqual(st["state"], "ready")
        self.assertEqual(st["stagedBytes"], len(self.zim_bytes))

        c = self._conn()
        c.request("GET", f"/v1/export/fetch/{op_id}")
        resp = c.getresponse()
        self.assertEqual(resp.status, 200)
        got = resp.read()
        c.close()
        self.assertEqual(got, self.zim_bytes)

        # The op is dropped after fetch (on_close), so a second status is 404.
        c = self._conn()
        c.request("GET", f"/v1/export/status/{op_id}")
        self.assertEqual(c.getresponse().status, 404)
        c.close()

    def test_version_over_http(self):
        c = self._conn()
        c.request("GET", "/v1/version")
        out = json.loads(c.getresponse().read())
        c.close()
        self.assertEqual(out, {"version": VERSION})

    def test_error_response_is_logged(self):
        # Every >= 400 response leaves a box-side trace (K2GO-376 observability): a failed
        # import used to vanish because conex-core logged nothing.
        with self.assertLogs("conex_core", level="WARNING") as cm:
            c = self._conn()
            c.request("GET", "/v1/content/nope/x")
            self.assertEqual(c.getresponse().status, 404)
            c.close()
        self.assertTrue(any("/v1/content/nope/x" in m and "404" in m for m in cm.output), cm.output)

    def test_error_log_omits_url_query(self):
        # The error log strips the query string, so a share token a client puts in the URL
        # (?token=...) never lands in /var/log/conex-core.log.
        with self.assertLogs("conex_core", level="WARNING") as cm:
            c = self._conn()
            c.request("GET", "/v1/content/nope/x?token=SECRETTOKEN123")
            self.assertEqual(c.getresponse().status, 404)
            c.close()
        joined = "\n".join(cm.output)
        self.assertNotIn("SECRETTOKEN123", joined, joined)
        self.assertIn("/v1/content/nope/x", joined)

    def test_export_plan_over_http(self):
        c = self._conn()
        payload = json.dumps({"items": [{"app": "kiwix", "localRef": self.zim_name}]})
        c.request("POST", "/v1/export/plan", body=payload, headers={"Content-Type": "application/json"})
        resp = c.getresponse()
        out = json.loads(resp.read())
        c.close()
        self.assertEqual(resp.status, 200)
        self.assertEqual(out["totalBytes"], len(self.zim_bytes))

    def test_apps_json_over_http(self):
        c = self._conn()
        c.request("GET", "/v1/apps")
        resp = c.getresponse()
        out = json.loads(resp.read())
        c.close()
        self.assertEqual(out, {"apps": ["kiwix"], "version": VERSION})

    def test_delete_item_over_http(self):
        c = self._conn()
        c.request("DELETE", f"/v1/content/kiwix/{self.zim_name}")
        resp = c.getresponse()
        out = json.loads(resp.read())
        c.close()
        self.assertEqual(resp.status, 200)
        self.assertTrue(out["ok"])
        self.assertEqual(out["freedBytes"], len(self.zim_bytes))
        self.assertFalse(os.path.exists(os.path.join(self._dir.name, self.zim_name)))

    def test_storage_over_http(self):
        c = self._conn()
        c.request("GET", "/v1/storage")
        resp = c.getresponse()
        out = json.loads(resp.read())
        c.close()
        self.assertEqual(resp.status, 200)
        # All content lives in the one ZIM, so the per-app total accounts for it.
        self.assertEqual(out["usedByContentBytes"], len(self.zim_bytes))
        self.assertEqual(out["perApp"], [{"app": "kiwix", "bytes": len(self.zim_bytes)}])


class _RaisingAdapter:
    """An adapter whose list() raises, to exercise the handler's uncaught-error path."""
    app = "boom"
    id_kind = "authoritative"

    def is_installed(self):
        return True

    def list(self):
        raise RuntimeError("boom in list")


class ServerErrorPathTest(unittest.TestCase):
    def test_uncaught_exception_returns_500_and_logs(self):
        # An uncaught handler error becomes a logged 500 with a JSON body, not a dropped
        # connection the front reads as an opaque network error (K2GO-376 observability).
        reg = Registry([_RaisingAdapter()])
        ops = ExportOps(reg, staging_dir=tempfile.mkdtemp(), ttl_seconds=60)
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(reg, ops))
        port = httpd.server_address[1]
        t = threading.Thread(target=httpd.serve_forever, daemon=True)
        t.start()
        try:
            with self.assertLogs("conex_core", level="ERROR") as cm:
                c = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
                c.request("GET", "/v1/content")
                resp = c.getresponse()
                body = json.loads(resp.read())
                c.close()
            self.assertEqual(resp.status, 500)
            self.assertEqual(body, {"error": "internal error"})
            self.assertTrue(any("unhandled error" in m for m in cm.output), cm.output)
        finally:
            httpd.shutdown()
            httpd.server_close()



class ShareRangeHttpTest(unittest.TestCase):
    """A cut ZIM resumes over the share gate: Range bytes=N- gets 206 with the tail (K2GO-577)."""

    def setUp(self):
        self._dir = tempfile.TemporaryDirectory()
        self.name = "wikipedia_en_all_2024-01.zim"
        self.data = _zim(os.path.join(self._dir.name, self.name), body_len=300000)
        reg = Registry([KiwixAdapter(self._dir.name)])
        ops = ExportOps(reg, staging_dir=self._dir.name, ttl_seconds=60)
        share = ShareOps(reg, ops)
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(reg, ops, share))
        self.port = self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        status, created = share.create([{"app": "kiwix", "localRef": self.name}])
        self.sid, self.token = created["sessionId"], created["token"]
        self.share = share

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self._dir.cleanup()

    def _fetch(self, range_header=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        headers = {"Connection": "close", "X-Share-Token": self.token}
        if range_header:
            headers["Range"] = range_header
        c.request("GET", f"/v1/share/{self.sid}/fetch/0", headers=headers)
        resp = c.getresponse()
        out = (resp.status, resp.getheader("Content-Range"), resp.getheader("Accept-Ranges"),
               resp.read())
        c.close()
        return out

    def test_open_range_resumes_with_206_and_marks_delivered(self):
        cut = 123457
        status, content_range, accept, body = self._fetch(f"bytes={cut}-")
        self.assertEqual(status, 206)
        self.assertEqual(content_range, f"bytes {cut}-{len(self.data) - 1}/{len(self.data)}")
        self.assertEqual(accept, "bytes")
        self.assertEqual(body, self.data[cut:])
        _, man = self.share.manifest(self.sid, self.token)
        self.assertTrue(man["items"][0]["delivered"])

    def test_no_or_unsupported_range_sends_the_whole_file(self):
        for header in (None, "bytes=0-10", f"bytes={len(self.data)}-"):
            status, content_range, _, body = self._fetch(header)
            self.assertEqual((status, content_range), (200, None), header)
            self.assertEqual(body, self.data)

if __name__ == "__main__":
    unittest.main()
