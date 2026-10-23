import os
import sqlite3
import tempfile
import unittest

import io
import json
import tarfile

from cx_core.adapters.calibre import CalibreAdapter, _content_files, build_book_item
from cx_core.adapters.calibreweb import resolve_calibre_credentials
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


def _make_db(path, book_path="Tolkien/The Hobbit (1)"):
    con = sqlite3.connect(path)
    con.executescript("""
        CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT, sort TEXT, path TEXT, uuid TEXT);
        CREATE TABLE data (id INTEGER PRIMARY KEY, book INTEGER, format TEXT, uncompressed_size INTEGER, name TEXT);
        CREATE TABLE authors (id INTEGER PRIMARY KEY, name TEXT);
        CREATE TABLE books_authors_link (id INTEGER PRIMARY KEY, book INTEGER, author INTEGER);
        CREATE TABLE identifiers (id INTEGER PRIMARY KEY, book INTEGER, type TEXT, val TEXT);
        INSERT INTO authors (id, name) VALUES (1, 'J.R.R. Tolkien');
        INSERT INTO books_authors_link (book, author) VALUES (1, 1);
        INSERT INTO data (book, format, uncompressed_size, name) VALUES (1, 'EPUB', 1000, 'hobbit'), (1, 'PDF', 2000, 'hobbit');
        INSERT INTO identifiers (book, type, val) VALUES (1, 'isbn', '9780261103344');
    """)
    con.execute(
        "INSERT INTO books (id, title, sort, path, uuid) VALUES (1, 'The Hobbit', 'Hobbit, The', ?, 'u-1')",
        (book_path,),
    )
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


class CalibreExportTest(unittest.TestCase):
    def test_open_export_tars_the_book_dir(self):
        import io
        import tarfile

        with tempfile.TemporaryDirectory() as lib:
            db = os.path.join(lib, "metadata.db")
            _make_db(db)
            book_dir = os.path.join(lib, "Tolkien", "The Hobbit (1)")
            os.makedirs(book_dir)
            with open(os.path.join(book_dir, "hobbit.epub"), "wb") as f:
                f.write(b"EPUBDATA")
            with open(os.path.join(book_dir, "cover.jpg"), "wb") as f:
                f.write(b"JPG")

            stream = CalibreAdapter(db).open_export("u-1")
            self.assertIsNotNone(stream)
            self.assertEqual(stream.filename, "u-1.tar")
            blob = b"".join(stream.chunks)
            with tarfile.open(fileobj=io.BytesIO(blob), mode="r") as tar:
                names = sorted(m.name for m in tar.getmembers() if m.isfile())
                self.assertEqual(names, ["u-1/cover.jpg", "u-1/hobbit.epub"])

    def test_open_export_missing_book_or_dir_is_none(self):
        with tempfile.TemporaryDirectory() as lib:
            db = os.path.join(lib, "metadata.db")
            _make_db(db)  # row exists but the book dir was never created
            adapter = CalibreAdapter(db)
            self.assertIsNone(adapter.open_export("u-1"))
            self.assertIsNone(adapter.open_export("no-such-uuid"))

    def test_open_export_rejects_traversal_path(self):
        with tempfile.TemporaryDirectory() as lib:
            db = os.path.join(lib, "metadata.db")
            # a malicious books.path must not escape the library root.
            _make_db(db, book_path="../../etc")
            self.assertIsNone(CalibreAdapter(db).open_export("u-1"))


class CalibreCheckTest(unittest.TestCase):
    def test_soft_match_is_advisory_same_version_else_absent(self):
        with tempfile.TemporaryDirectory() as lib:
            db = os.path.join(lib, "metadata.db")
            _make_db(db)
            adapter = CalibreAdapter(db)
            self.assertEqual(adapter.id_kind, SOFT)
            self.assertEqual(
                adapter.check({"line": {"title": "the hobbit", "authors": "j.r.r. tolkien"}}),
                "SAME_VERSION",
            )
            self.assertEqual(adapter.check({"line": {"title": "other", "authors": "x"}}), "ABSENT")


class ContentFilesTest(unittest.TestCase):
    def test_selects_by_format_else_excludes_sidecars(self):
        with tempfile.TemporaryDirectory() as d:
            for name in ["hobbit.epub", "hobbit.pdf", "cover.jpg", "metadata.opf"]:
                with open(os.path.join(d, name), "wb") as f:
                    f.write(b"x")
            by_format = sorted(os.path.basename(p) for p in _content_files(d, {"epub", "pdf"}))
            self.assertEqual(by_format, ["hobbit.epub", "hobbit.pdf"])
            no_format = sorted(os.path.basename(p) for p in _content_files(d, set()))
            self.assertEqual(no_format, ["hobbit.epub", "hobbit.pdf"])  # cover/opf excluded


class ResolveCredentialsTest(unittest.TestCase):
    def setUp(self):
        self._saved = {k: os.environ.pop(k, None) for k in ("K2GO_CALIBRE_USER", "K2GO_CALIBRE_PASS")}

    def tearDown(self):
        for k, v in self._saved.items():
            if v is not None:
                os.environ[k] = v
            else:
                os.environ.pop(k, None)

    def test_env_wins(self):
        os.environ["K2GO_CALIBRE_USER"] = "envuser"
        os.environ["K2GO_CALIBRE_PASS"] = "envpass"
        self.assertEqual(resolve_calibre_credentials("/no/file"), ("envuser", "envpass"))

    def test_file_then_default(self):
        with tempfile.TemporaryDirectory() as d:
            store = os.path.join(d, "credentials.json")
            with open(store, "w") as f:
                json.dump({"calibre": {"username": "fileuser", "password": "filepass"}}, f)
            self.assertEqual(resolve_calibre_credentials(store), ("fileuser", "filepass"))
            self.assertEqual(resolve_calibre_credentials("/no/such/file"), ("Admin", "changeme"))


class _UploadStubCalibre(CalibreAdapter):
    def _upload_files(self, files):
        self.uploaded = sorted(os.path.basename(f) for f in files)
        return len(files)


def _book_tar(tar_path, uuid, names):
    with tarfile.open(tar_path, "w") as t:
        for name in names:
            data = b"book-bytes"
            ti = tarfile.TarInfo(f"{uuid}/{name}")
            ti.size = len(data)
            t.addfile(ti, io.BytesIO(data))


class CalibreImportTest(unittest.TestCase):
    def test_import_extracts_and_uploads_format_files(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "metadata.db")
            _make_db(db)
            tar_path = os.path.join(d, "book.tar")
            _book_tar(tar_path, "u-1", ["hobbit.epub", "cover.jpg", "metadata.opf"])
            adapter = _UploadStubCalibre(db, staging_dir=os.path.join(d, "staging"))
            entry = {"identity": {"localRef": "u-1", "dedup": {"line": {"formats": ["EPUB"]}}}}
            result = adapter.import_item(entry, tar_path)
            self.assertTrue(result["ok"])
            self.assertEqual(result["uploaded"], 1)
            self.assertEqual(adapter.uploaded, ["hobbit.epub"])

    def test_import_rejects_bad_archive(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "metadata.db")
            _make_db(db)
            tar_path = os.path.join(d, "evil.tar")
            with tarfile.open(tar_path, "w") as t:
                data = b"x"
                ti = tarfile.TarInfo("../escape")
                ti.size = len(data)
                t.addfile(ti, io.BytesIO(data))
            adapter = _UploadStubCalibre(db, staging_dir=os.path.join(d, "staging"))
            result = adapter.import_item({"identity": {"localRef": "u-1"}}, tar_path)
            self.assertFalse(result["ok"])


class _DeleteStubCalibre(CalibreAdapter):
    """Overrides the Calibre-Web seam so delete delegation is testable without a live service."""

    def __init__(self, *args, succeed=True, **kwargs):
        super().__init__(*args, **kwargs)
        self._succeed = succeed

    def _remove_book(self, book_id):
        self.removed = book_id
        return self._succeed


class CalibreBookIdTest(unittest.TestCase):
    def test_resolves_integer_id_from_uuid(self):
        with tempfile.TemporaryDirectory() as lib:
            db = os.path.join(lib, "metadata.db")
            _make_db(db)
            adapter = CalibreAdapter(db)
            self.assertEqual(adapter._book_id("u-1"), 1)
            self.assertIsNone(adapter._book_id("nope"))


class CalibreDeleteTest(unittest.TestCase):
    def test_delete_delegates_to_calibreweb_by_integer_id(self):
        with tempfile.TemporaryDirectory() as lib:
            db = os.path.join(lib, "metadata.db")
            _make_db(db)
            adapter = _DeleteStubCalibre(db)
            result = adapter.delete("u-1")
            self.assertTrue(result["ok"])
            self.assertEqual(adapter.removed, 1)  # the integer library id, not the uuid
            # A book is self-contained, so freedBytes is its known size (EPUB 1000 + PDF 2000).
            self.assertEqual(result["freedBytes"], 3000)

    def test_delete_missing_book_is_not_ok(self):
        with tempfile.TemporaryDirectory() as lib:
            db = os.path.join(lib, "metadata.db")
            _make_db(db)
            result = _DeleteStubCalibre(db).delete("no-such-uuid")
            self.assertFalse(result["ok"])
            self.assertEqual(result["reason"], "item not found")

    def test_delete_calibreweb_failure_is_not_ok(self):
        with tempfile.TemporaryDirectory() as lib:
            db = os.path.join(lib, "metadata.db")
            _make_db(db)
            result = _DeleteStubCalibre(db, succeed=False).delete("u-1")
            self.assertFalse(result["ok"])


if __name__ == "__main__":
    unittest.main()
