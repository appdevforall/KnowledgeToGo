import os
import tempfile
import unittest
import uuid

from cx_core.adapters.kiwix import (
    KiwixAdapter,
    build_item,
    is_safe_local_ref,
    parse_zim_filename,
    read_zim_uuid,
)
from cx_core.model import AUTHORITATIVE, SOFT


def _zim_header(u: uuid.UUID) -> bytes:
    # magic uint32 LE + 4 version bytes + 16-byte uuid at offset 8.
    return (0x044D495A).to_bytes(4, "little") + b"\x06\x00\x00\x00" + u.bytes


class ReadZimUuidTest(unittest.TestCase):
    def test_reads_uuid_from_valid_header(self):
        u = uuid.UUID("abcdabcd-1234-5678-9abc-def012345678")
        self.assertEqual(read_zim_uuid(_zim_header(u)), str(u))

    def test_rejects_bad_magic(self):
        bad = b"\x00\x00\x00\x00" + b"\x00" * 20
        self.assertIsNone(read_zim_uuid(bad))

    def test_rejects_short_header(self):
        self.assertIsNone(read_zim_uuid(b"\x5a\x49\x4d\x04"))


class ParseZimFilenameTest(unittest.TestCase):
    def test_simple_name(self):
        self.assertEqual(
            parse_zim_filename("devdocs_en_angular_2026-05.zim"),
            ("devdocs", "en", "angular", "2026-05"),
        )

    def test_multi_token_flavour(self):
        self.assertEqual(
            parse_zim_filename("wikipedia_en_all_maxi_2023-10.zim"),
            ("wikipedia", "en", "all_maxi", "2023-10"),
        )

    def test_full_date(self):
        self.assertEqual(
            parse_zim_filename("gutenberg_en_all_2024-01-15.zim"),
            ("gutenberg", "en", "all", "2024-01-15"),
        )

    def test_no_date_token(self):
        self.assertEqual(parse_zim_filename("something_odd.zim"), ("something", "odd", "", ""))

    def test_off_convention_name_keeps_stable_creator(self):
        self.assertEqual(parse_zim_filename("weird.zim"), ("weird", "", "", ""))


class BuildItemTest(unittest.TestCase):
    def test_builds_authoritative_identity(self):
        item = build_item("devdocs_en_angular_2026-05.zim", 2411725, "the-uuid")
        self.assertEqual(item.app, "kiwix")
        self.assertEqual(item.kind, "zim")
        self.assertEqual(item.bytes, 2411725)
        self.assertEqual(item.identity.local_ref, "devdocs_en_angular_2026-05.zim")
        self.assertEqual(item.identity.dedup.build, "the-uuid")
        self.assertEqual(item.identity.dedup.version, "2026-05")
        self.assertEqual(item.identity.dedup.id_kind, AUTHORITATIVE)
        self.assertEqual(
            item.identity.dedup.line,
            {"creator": "devdocs", "lang": "en", "flavour": "angular"},
        )
        self.assertIsNone(item.integrity)

    def test_missing_uuid_degrades_to_soft(self):
        item = build_item("devdocs_en_angular_2026-05.zim", 2411725, None)
        self.assertIsNone(item.identity.dedup.build)
        self.assertEqual(item.identity.dedup.id_kind, SOFT)


class IsSafeLocalRefTest(unittest.TestCase):
    def test_plain_filename_allowed(self):
        self.assertTrue(is_safe_local_ref("wikipedia_en_all_2023-10.zim"))

    def test_traversal_and_separators_rejected(self):
        for bad in ["../x.zim", "a/b.zim", "a\\b.zim", "/etc/passwd", "..", ".", ""]:
            self.assertFalse(is_safe_local_ref(bad), bad)


class KiwixAdapterEndToEndTest(unittest.TestCase):
    def test_list_reads_installed_zims_with_uuid_and_size(self):
        u = uuid.UUID("11111111-2222-3333-4444-555555555555")
        with tempfile.TemporaryDirectory() as d:
            name = "wikipedia_en_all_maxi_2023-10.zim"
            payload = _zim_header(u) + b"\x00" * 100  # header + filler body
            with open(os.path.join(d, name), "wb") as f:
                f.write(payload)
            # a non-zim file must be ignored
            with open(os.path.join(d, "readme.txt"), "w") as f:
                f.write("x")

            adapter = KiwixAdapter(d)
            self.assertTrue(adapter.is_installed())
            items = adapter.list()
            self.assertEqual(len(items), 1)
            it = items[0]
            self.assertEqual(it.identity.local_ref, name)
            self.assertEqual(it.identity.dedup.build, str(u))
            self.assertEqual(it.bytes, len(payload))

            # get() finds it by local_ref, and misses cleanly.
            got = adapter.get(name)
            self.assertIsNotNone(got)
            self.assertEqual(got.identity.dedup.build, str(u))
            self.assertIsNone(adapter.get("nope.zim"))

    def test_bad_header_zim_lists_soft_with_no_build(self):
        with tempfile.TemporaryDirectory() as d:
            # a .zim whose header is too short / not a ZIM: still listed, but degraded.
            with open(os.path.join(d, "broken_en_x_2020-01.zim"), "wb") as f:
                f.write(b"not a zim")
            items = KiwixAdapter(d).list()
            self.assertEqual(len(items), 1)
            self.assertIsNone(items[0].identity.dedup.build)
            self.assertEqual(items[0].identity.dedup.id_kind, SOFT)

    def test_get_rejects_traversal_without_touching_fs(self):
        with tempfile.TemporaryDirectory() as d:
            # a readable ZIM one level up must NOT be reachable through a traversal ref.
            with open(os.path.join(d, "secret_en_x_2020-01.zim"), "wb") as f:
                f.write(_zim_header(uuid.uuid4()))
            sub = os.path.join(d, "content")
            os.mkdir(sub)
            adapter = KiwixAdapter(sub)
            self.assertIsNone(adapter.get("../secret_en_x_2020-01.zim"))
            self.assertIsNone(adapter.get("..\\secret_en_x_2020-01.zim"))

    def test_absent_dir_is_not_installed(self):
        adapter = KiwixAdapter("/no/such/dir/for/cx-core")
        self.assertFalse(adapter.is_installed())
        self.assertEqual(adapter.list(), [])


if __name__ == "__main__":
    unittest.main()
