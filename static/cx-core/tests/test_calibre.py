import os
import sqlite3
import tempfile
import unittest

from cx_core.adapters.calibre import CalibreAdapter, build_book_item
from cx_core.model import SOFT


class BuildBookItemTest(unittest.TestCase):
    def test_soft_identity_with_formats_and_external_id(self):
        item = build_book_item("u-1", "The Hobbit", "Tolkien", "PDF,EPUB", 3000, "9780261103344")
        self.assertEqual(item.app, "calibre")
        self.assertEqual(item.kind, "book")
        self.assertEqual(item.bytes, 3000)
        self.assertEqual(item.identity.local_ref, "u-1")
        self.assertEqual(item.identity.dedup.id_kind, SOFT)
        self.assertEqual(item.identity.dedup.build, "9780261103344")
        self.assertEqual(
            item.identity.dedup.line,
            {"title": "the hobbit", "authors": "tolkien", "formats": ["EPUB", "PDF"], "bytes": 3000},
        )

    def test_no_formats_no_id_no_authors(self):
        item = build_book_item("u-2", "Untitled", None, None, 0, None)
        self.assertIsNone(item.identity.dedup.build)
        self.assertEqual(item.identity.dedup.line["authors"], "")
        self.assertEqual(item.identity.dedup.line["formats"], [])
        self.assertEqual(item.bytes, 0)

    def test_missing_title_falls_back_to_uuid(self):
        item = build_book_item("u-3", None, None, "EPUB", 10, None)
        self.assertEqual(item.title, "u-3")


def _make_db(path):
    con = sqlite3.connect(path)
    con.executescript("""
        CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT, sort TEXT, uuid TEXT);
        CREATE TABLE data (id INTEGER PRIMARY KEY, book INTEGER, format TEXT, uncompressed_size INTEGER, name TEXT);
        CREATE TABLE authors (id INTEGER PRIMARY KEY, name TEXT);
        CREATE TABLE books_authors_link (id INTEGER PRIMARY KEY, book INTEGER, author INTEGER);
        CREATE TABLE identifiers (id INTEGER PRIMARY KEY, book INTEGER, type TEXT, val TEXT);
        INSERT INTO books (id, title, sort, uuid) VALUES (1, 'The Hobbit', 'Hobbit, The', 'u-1');
        INSERT INTO authors (id, name) VALUES (1, 'J.R.R. Tolkien');
        INSERT INTO books_authors_link (book, author) VALUES (1, 1);
        INSERT INTO data (book, format, uncompressed_size, name) VALUES (1, 'EPUB', 1000, 'hobbit'), (1, 'PDF', 2000, 'hobbit');
        INSERT INTO identifiers (book, type, val) VALUES (1, 'isbn', '9780261103344');
    """)
    con.commit()
    con.close()


class CalibreAdapterEndToEndTest(unittest.TestCase):
    def test_list_builds_soft_identity_from_db(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "metadata.db")
            _make_db(db)
            adapter = CalibreAdapter(db)
            self.assertTrue(adapter.is_installed())
            items = adapter.list()
            self.assertEqual(len(items), 1)
            it = items[0]
            self.assertEqual(it.identity.local_ref, "u-1")
            self.assertEqual(it.bytes, 3000)  # EPUB 1000 + PDF 2000
            self.assertEqual(it.identity.dedup.build, "9780261103344")
            self.assertEqual(it.identity.dedup.line["formats"], ["EPUB", "PDF"])
            self.assertEqual(it.identity.dedup.line["authors"], "j.r.r. tolkien")

    def test_get_single_book(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "metadata.db")
            _make_db(db)
            adapter = CalibreAdapter(db)
            self.assertIsNotNone(adapter.get("u-1"))
            self.assertIsNone(adapter.get("nope"))

    def test_absent_db_not_installed(self):
        adapter = CalibreAdapter("/no/such/metadata.db")
        self.assertFalse(adapter.is_installed())
        self.assertEqual(adapter.list(), [])


if __name__ == "__main__":
    unittest.main()
