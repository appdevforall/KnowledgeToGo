import os
import tempfile
import unittest

from conex_core import VERSION
from conex_core.model import AUTHORITATIVE, Dedup, Identity, ManifestItem
from conex_core.ports import ContentAdapter, ExportStream
from conex_core.registry import Registry
from conex_core.router import dispatch


def _item(app: str, local_ref: str) -> ManifestItem:
    return ManifestItem(
        app=app,
        kind="zim",
        title=local_ref,
        bytes=10,
        identity=Identity(
            local_ref=local_ref,
            dedup=Dedup(line={}, build=None, version="", id_kind=AUTHORITATIVE),
        ),
    )


class FakeAdapter(ContentAdapter):
    def __init__(self, app, items, export=None, verdict="ABSENT", id_kind="authoritative",
                 import_result=None, delete_result=None):
        self.app = app
        self.id_kind = id_kind
        self._items = items
        self._export = export
        self._verdict = verdict
        self._import_result = import_result or {"ok": True, "app": app}
        self._delete_result = delete_result or {"ok": True, "app": app, "freedBytes": 10}

    def is_installed(self):
        return True

    def list(self):
        return self._items

    def check(self, dedup):
        return self._verdict

    def import_item(self, entry, payload_path):
        self.imported = (entry, payload_path)
        return self._import_result

    def delete(self, local_ref):
        self.deleted = local_ref
        return self._delete_result


def _registry():
    return Registry([
        FakeAdapter("kiwix", [_item("kiwix", "a.zim"), _item("kiwix", "b.zim")]),
        FakeAdapter("calibre", [_item("calibre", "book-uuid-1")]),
    ])


class RouterTest(unittest.TestCase):
    def setUp(self):
        self.reg = _registry()

    def test_apps(self):
        status, body = dispatch(self.reg, "GET", "/v1/apps", {})
        self.assertEqual(status, 200)
        self.assertEqual(body, {"apps": ["calibre", "kiwix"], "version": VERSION})

    def test_version(self):
        status, body = dispatch(self.reg, "GET", "/v1/version", {})
        self.assertEqual(status, 200)
        self.assertEqual(body, {"version": VERSION})

    def test_content_all(self):
        status, body = dispatch(self.reg, "GET", "/v1/content", {})
        self.assertEqual(status, 200)
        self.assertEqual(len(body["items"]), 3)

    def test_content_filtered_by_app(self):
        status, body = dispatch(self.reg, "GET", "/v1/content", {"app": "kiwix"})
        self.assertEqual(status, 200)
        self.assertEqual([i["identity"]["localRef"] for i in body["items"]], ["a.zim", "b.zim"])

    def test_content_unknown_app_filter_404(self):
        status, body = dispatch(self.reg, "GET", "/v1/content", {"app": "nope"})
        self.assertEqual(status, 404)

    def test_item_detail(self):
        status, body = dispatch(self.reg, "GET", "/v1/content/calibre/book-uuid-1", {})
        self.assertEqual(status, 200)
        self.assertEqual(body["identity"]["localRef"], "book-uuid-1")

    def test_item_detail_unknown_app(self):
        status, _ = dispatch(self.reg, "GET", "/v1/content/nope/x", {})
        self.assertEqual(status, 404)

    def test_item_detail_missing_item(self):
        status, _ = dispatch(self.reg, "GET", "/v1/content/kiwix/missing.zim", {})
        self.assertEqual(status, 404)

    def test_trailing_slash_tolerated(self):
        status, _ = dispatch(self.reg, "GET", "/v1/apps/", {})
        self.assertEqual(status, 200)

    def test_unknown_get_404_and_unknown_post_404(self):
        status, _ = dispatch(self.reg, "GET", "/v1/unknown", {})
        self.assertEqual(status, 404)
        status, _ = dispatch(self.reg, "POST", "/v1/unknown", {}, {})
        self.assertEqual(status, 404)

    def test_other_method_405(self):
        status, _ = dispatch(self.reg, "DELETE", "/v1/apps", {})
        self.assertEqual(status, 405)


class ExportRouteTest(unittest.TestCase):
    def setUp(self):
        self.reg = _registry()

    def test_export_plan_found_and_missing(self):
        body = {"items": [
            {"app": "kiwix", "localRef": "a.zim"},
            {"app": "kiwix", "localRef": "gone.zim"},
            {"app": "nope", "localRef": "x"},
        ]}
        status, out = dispatch(self.reg, "POST", "/v1/export/plan", {}, body)
        self.assertEqual(status, 200)
        self.assertEqual([i["identity"]["localRef"] for i in out["items"]], ["a.zim"])
        self.assertEqual(out["totalBytes"], 10)
        self.assertEqual(len(out["missing"]), 2)

    def test_export_plan_bad_body(self):
        status, _ = dispatch(self.reg, "POST", "/v1/export/plan", {}, {"nope": 1})
        self.assertEqual(status, 400)


class ExportOpRouteTest(unittest.TestCase):
    """The export long-op routes forward to the ops registry; the op mechanics live in
    test_export_ops. Here we check the routing and the missing-ops guard."""

    def setUp(self):
        self.reg = _registry()

    def test_prepare_bad_body_400(self):
        from conex_core.export_ops import ExportOps
        ops = ExportOps(self.reg, staging_dir="/tmp", ttl_seconds=60)
        status, _ = dispatch(self.reg, "POST", "/v1/export/prepare", {}, {"nope": 1}, ops=ops)
        self.assertEqual(status, 400)

    def test_prepare_without_ops_500(self):
        status, _ = dispatch(self.reg, "POST", "/v1/export/prepare", {},
                             {"app": "kiwix", "localRef": "a.zim"}, ops=None)
        self.assertEqual(status, 500)

    def test_status_fetch_delete_without_ops_500(self):
        for method, path in [("GET", "/v1/export/status/abc"),
                             ("GET", "/v1/export/fetch/abc"),
                             ("DELETE", "/v1/export/abc")]:
            status, _ = dispatch(self.reg, method, path, {}, ops=None)
            self.assertEqual(status, 500)


class ImportRouteTest(unittest.TestCase):
    def test_dedup_check_returns_verdict_and_idkind(self):
        reg = Registry([
            FakeAdapter("kiwix", [], verdict="OLDER_HERE", id_kind="authoritative"),
            FakeAdapter("calibre", [], verdict="SAME_VERSION", id_kind="soft"),
        ])
        body = {"items": [
            {"app": "kiwix", "dedup": {"line": {}}},
            {"app": "calibre", "dedup": {"line": {}}},
            {"app": "nope", "dedup": {"line": {}}},
        ]}
        status, out = dispatch(reg, "POST", "/v1/dedup/check", {}, body)
        self.assertEqual(status, 200)
        self.assertEqual(out["results"][0], {"app": "kiwix", "verdict": "OLDER_HERE", "idKind": "authoritative"})
        self.assertEqual(out["results"][1], {"app": "calibre", "verdict": "SAME_VERSION", "idKind": "soft"})
        self.assertEqual(out["results"][2]["verdict"], "NO_APP")

    def test_import_validate_partial(self):
        reg = Registry([FakeAdapter("kiwix", [], verdict="ABSENT")])
        body = {"items": [
            {"app": "kiwix", "identity": {"localRef": "a.zim", "dedup": {"line": {}}}},
            {"app": "calibre", "identity": {"localRef": "b", "dedup": {}}},
        ]}
        status, out = dispatch(reg, "POST", "/v1/import/validate", {}, body)
        self.assertEqual(status, 200)
        self.assertEqual(out["results"][0]["status"], "importable")
        self.assertEqual(out["results"][0]["verdict"], "ABSENT")
        self.assertEqual(out["results"][1]["status"], "NO_APP")

    def test_import_item_happy_path(self):
        with tempfile.TemporaryDirectory() as root:
            fake = FakeAdapter("kiwix", [], import_result={"ok": True, "app": "kiwix"})
            reg = Registry([fake], import_root=root)
            payload = os.path.join(root, "a.zim")
            body = {"app": "kiwix", "entry": {"identity": {"localRef": "a.zim"}, "bytes": 10}, "payloadPath": payload}
            status, out = dispatch(reg, "POST", "/v1/import/item", {}, body)
            self.assertEqual(status, 200)
            self.assertTrue(out["ok"])
            self.assertEqual(fake.imported[1], payload)

    def test_import_item_no_free_space_preflight(self):
        # The box runs under proot, whose statvfs under-reports free space, so the router must
        # not preflight the disk: even an implausibly large declared size still reaches the
        # adapter. The phone app is the one source of truth for free space (Android StatFs); a
        # real ENOSPC fails cleanly in the adapter (422).
        with tempfile.TemporaryDirectory() as root:
            fake = FakeAdapter("kiwix", [], import_result={"ok": True, "app": "kiwix"})
            reg = Registry([fake], import_root=root)
            payload = os.path.join(root, "a.zim")
            body = {"app": "kiwix",
                    "entry": {"identity": {"localRef": "a.zim"}, "bytes": 10 ** 18},
                    "payloadPath": payload}
            status, out = dispatch(reg, "POST", "/v1/import/item", {}, body)
            self.assertEqual(status, 200)
            self.assertTrue(out["ok"])

    def test_import_item_rejects_payload_outside_area(self):
        with tempfile.TemporaryDirectory() as root:
            reg = Registry([FakeAdapter("kiwix", [])], import_root=root)
            body = {"app": "kiwix", "entry": {"identity": {"localRef": "a.zim"}}, "payloadPath": "/etc/passwd"}
            status, _ = dispatch(reg, "POST", "/v1/import/item", {}, body)
            self.assertEqual(status, 400)

    def test_import_item_adapter_failure_422(self):
        with tempfile.TemporaryDirectory() as root:
            fake = FakeAdapter("kiwix", [], import_result={"ok": False, "reason": "bad archive"})
            reg = Registry([fake], import_root=root)
            body = {"app": "kiwix", "entry": {"identity": {"localRef": "a.zim"}}, "payloadPath": os.path.join(root, "x")}
            status, out = dispatch(reg, "POST", "/v1/import/item", {}, body)
            self.assertEqual(status, 422)
            self.assertFalse(out["ok"])


class DeleteRouteTest(unittest.TestCase):
    def setUp(self):
        self.reg = _registry()

    def test_delete_item_delegates_and_returns_freed(self):
        status, out = dispatch(self.reg, "DELETE", "/v1/content/kiwix/a.zim", {})
        self.assertEqual(status, 200)
        self.assertTrue(out["ok"])
        self.assertEqual(out["freedBytes"], 10)
        self.assertEqual(self.reg.get("kiwix").deleted, "a.zim")

    def test_delete_missing_item_404(self):
        status, _ = dispatch(self.reg, "DELETE", "/v1/content/kiwix/gone.zim", {})
        self.assertEqual(status, 404)

    def test_delete_unknown_app_404(self):
        status, _ = dispatch(self.reg, "DELETE", "/v1/content/nope/x", {})
        self.assertEqual(status, 404)

    def test_delete_adapter_failure_422(self):
        reg = Registry([FakeAdapter(
            "kiwix", [_item("kiwix", "a.zim")],
            delete_result={"ok": False, "reason": "kolibri delete failed"})])
        status, out = dispatch(reg, "DELETE", "/v1/content/kiwix/a.zim", {})
        self.assertEqual(status, 422)
        self.assertFalse(out["ok"])

    def test_delete_non_item_path_405(self):
        status, _ = dispatch(self.reg, "DELETE", "/v1/content", {})
        self.assertEqual(status, 405)


class StorageRouteTest(unittest.TestCase):
    def test_storage_aggregates_disk_and_per_app(self):
        reg = Registry(
            [FakeAdapter("kiwix", [_item("kiwix", "a.zim"), _item("kiwix", "b.zim")]),
             FakeAdapter("calibre", [_item("calibre", "book-1")])],
            content_volume="/library")
        import conex_core.router as r
        orig = r.disk_total_free
        r.disk_total_free = lambda p: (1000, 400)
        try:
            status, out = dispatch(reg, "GET", "/v1/storage", {})
        finally:
            r.disk_total_free = orig
        self.assertEqual(status, 200)
        self.assertEqual(out["volume"], "/library")
        self.assertEqual(out["totalBytes"], 1000)
        self.assertEqual(out["freeBytes"], 400)
        self.assertEqual(out["usedByContentBytes"], 30)  # kiwix 20 + calibre 10
        self.assertEqual(out["perApp"], [{"app": "calibre", "bytes": 10}, {"app": "kiwix", "bytes": 20}])


if __name__ == "__main__":
    unittest.main()
