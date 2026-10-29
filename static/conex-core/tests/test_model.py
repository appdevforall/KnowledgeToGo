import unittest

from conex_core.model import (
    AUTHORITATIVE,
    Dedup,
    Identity,
    ManifestItem,
    normalize_text,
)


class NormalizeTextTest(unittest.TestCase):
    def test_trims_lowercases_and_collapses_whitespace(self):
        self.assertEqual(normalize_text("  The   Hobbit\tPart 1 "), "the hobbit part 1")

    def test_empty_and_none_safe(self):
        self.assertEqual(normalize_text(""), "")
        self.assertEqual(normalize_text(None), "")


class ManifestItemJsonTest(unittest.TestCase):
    def test_to_json_shape_matches_contract(self):
        item = ManifestItem(
            app="kiwix",
            kind="zim",
            title="wikipedia_en_all_maxi_2023-10",
            bytes=42,
            identity=Identity(
                local_ref="wikipedia_en_all_maxi_2023-10.zim",
                dedup=Dedup(
                    line={"creator": "wikipedia", "lang": "en", "flavour": "all_maxi"},
                    build="11111111-2222-3333-4444-555555555555",
                    version="2023-10",
                    id_kind=AUTHORITATIVE,
                ),
            ),
        )
        self.assertEqual(
            item.to_json(),
            {
                "app": "kiwix",
                "kind": "zim",
                "title": "wikipedia_en_all_maxi_2023-10",
                "bytes": 42,
                "identity": {
                    "localRef": "wikipedia_en_all_maxi_2023-10.zim",
                    "dedup": {
                        "line": {"creator": "wikipedia", "lang": "en", "flavour": "all_maxi"},
                        "build": "11111111-2222-3333-4444-555555555555",
                        "version": "2023-10",
                        "idKind": "authoritative",
                    },
                },
                "integrity": None,
                "payload": None,
                "requires": [],
            },
        )


if __name__ == "__main__":
    unittest.main()
