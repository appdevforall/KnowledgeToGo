import unittest

from cx_core.model import AUTHORITATIVE, Dedup, Identity, ManifestItem
from cx_core.ports import ContentAdapter, ExportStream
from cx_core.registry import Registry
from cx_core.router import dispatch


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
    def __init__(self, app, items, export=None):
        self.app = app
        self._items = items
        self._export = export

    def is_installed(self):
        return True

    def list(self):
        return self._items

    def open_export(self, local_ref):
        return self._export


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
        self.assertEqual(body, {"apps": ["calibre", "kiwix"]})

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

    def test_export_item_streams(self):
        stream = ExportStream("a.zim", "application/octet-stream", [b"abc"])
        reg = Registry([FakeAdapter("kiwix", [_item("kiwix", "a.zim")], export=stream)])
        status, payload = dispatch(reg, "GET", "/v1/export/item/kiwix/a.zim", {})
        self.assertEqual(status, 200)
        self.assertIs(payload, stream)

    def test_export_item_not_exportable_404(self):
        status, _ = dispatch(self.reg, "GET", "/v1/export/item/kiwix/a.zim", {})
        self.assertEqual(status, 404)  # FakeAdapter has no export stream

    def test_export_item_unknown_app_404(self):
        status, _ = dispatch(self.reg, "GET", "/v1/export/item/nope/x", {})
        self.assertEqual(status, 404)


if __name__ == "__main__":
    unittest.main()
