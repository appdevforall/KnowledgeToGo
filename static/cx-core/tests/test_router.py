import unittest

from cx_core.model import AUTHORITATIVE, Dedup, Identity, ManifestItem
from cx_core.ports import ContentAdapter
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
    def __init__(self, app, items):
        self.app = app
        self._items = items

    def is_installed(self):
        return True

    def list(self):
        return self._items


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

    def test_non_get_405(self):
        status, _ = dispatch(self.reg, "GET", "/v1/unknown", {})
        self.assertEqual(status, 404)
        status, _ = dispatch(self.reg, "POST", "/v1/apps", {})
        self.assertEqual(status, 405)


if __name__ == "__main__":
    unittest.main()
