import os
import sqlite3
import tempfile
import unittest

from cx_core.adapters.kolibri import KolibriAdapter, build_channel_item
from cx_core.model import AUTHORITATIVE


class BuildChannelItemTest(unittest.TestCase):
    def test_authoritative_identity(self):
        item = build_channel_item("c1f2b7e6ac9f56a2bb44fa7a48b66dce", "Khan Academy", 14, 5000)
        self.assertEqual(item.app, "kolibri")
        self.assertEqual(item.kind, "channel")
        self.assertEqual(item.title, "Khan Academy")
        self.assertEqual(item.bytes, 5000)
        self.assertEqual(item.identity.local_ref, "c1f2b7e6ac9f56a2bb44fa7a48b66dce")
        self.assertEqual(item.identity.dedup.line, "c1f2b7e6ac9f56a2bb44fa7a48b66dce")
        self.assertEqual(item.identity.dedup.build, "14")
        self.assertEqual(item.identity.dedup.version, 14)
        self.assertEqual(item.identity.dedup.id_kind, AUTHORITATIVE)

    def test_missing_name_falls_back_to_id(self):
        item = build_channel_item("abc", None, None, 0)
        self.assertEqual(item.title, "abc")
        self.assertEqual(item.identity.dedup.build, "0")


def _make_db(path):
    con = sqlite3.connect(path)
    con.executescript("""
        CREATE TABLE content_channelmetadata (id TEXT, name TEXT, version INTEGER);
        CREATE TABLE content_contentnode (id TEXT, channel_id TEXT);
        CREATE TABLE content_file (contentnode_id TEXT, local_file_id TEXT);
        CREATE TABLE content_localfile (id TEXT, file_size INTEGER, available INTEGER);
        INSERT INTO content_channelmetadata VALUES ('C1', 'Chan One', 14);
        INSERT INTO content_contentnode VALUES ('n1', 'C1'), ('n2', 'C1');
        INSERT INTO content_localfile VALUES ('lf1', 100, 1), ('lf2', 200, 0);
        -- lf1 is referenced by two nodes: it must be counted ONCE (DISTINCT).
        INSERT INTO content_file VALUES ('n1', 'lf1'), ('n2', 'lf1'), ('n1', 'lf2');
    """)
    con.commit()
    con.close()


class KolibriAdapterEndToEndTest(unittest.TestCase):
    def test_list_counts_available_distinct_bytes(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "db.sqlite3")
            _make_db(db)
            adapter = KolibriAdapter(db)
            self.assertTrue(adapter.is_installed())
            items = adapter.list()
            self.assertEqual(len(items), 1)
            # only lf1 is available (100), counted once despite two node refs; lf2 not available.
            self.assertEqual(items[0].bytes, 100)
            self.assertEqual(items[0].identity.local_ref, "C1")

    def test_get_single_channel(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "db.sqlite3")
            _make_db(db)
            adapter = KolibriAdapter(db)
            self.assertIsNotNone(adapter.get("C1"))
            self.assertIsNone(adapter.get("missing"))

    def test_absent_db_not_installed(self):
        adapter = KolibriAdapter("/no/such/kolibri/db.sqlite3")
        self.assertFalse(adapter.is_installed())
        self.assertEqual(adapter.list(), [])


if __name__ == "__main__":
    unittest.main()
