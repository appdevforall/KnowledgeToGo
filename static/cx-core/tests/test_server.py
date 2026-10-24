import http.client
import json
import os
import tempfile
import threading
import unittest
import uuid
from http.server import ThreadingHTTPServer

from cx_core import VERSION
from cx_core.adapters.kiwix import KiwixAdapter
from cx_core.export_ops import ExportOps
from cx_core.registry import Registry
from cx_core.server import make_handler


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


if __name__ == "__main__":
    unittest.main()
