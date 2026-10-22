import os
import sqlite3
import tempfile
import unittest

import io
import tarfile

from cx_core.adapters.kolibri import (
    KolibriAdapter,
    build_channel_item,
    export_commands,
    import_commands,
)
from cx_core.model import AUTHORITATIVE

VALID_ID = "c1f2b7e6ac9f56a2bb44fa7a48b66dce"


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


def _make_db(path, cid="C1"):
    con = sqlite3.connect(path)
    con.executescript("""
        CREATE TABLE content_channelmetadata (id TEXT, name TEXT, version INTEGER);
        CREATE TABLE content_contentnode (id TEXT, channel_id TEXT);
        CREATE TABLE content_file (contentnode_id TEXT, local_file_id TEXT);
        CREATE TABLE content_localfile (id TEXT, file_size INTEGER, available INTEGER);
        INSERT INTO content_localfile VALUES ('lf1', 100, 1), ('lf2', 200, 0);
    """)
    con.execute("INSERT INTO content_channelmetadata VALUES (?, 'Chan One', 14)", (cid,))
    con.execute("INSERT INTO content_contentnode VALUES ('n1', ?), ('n2', ?)", (cid, cid))
    # lf1 is referenced by two nodes: it must be counted ONCE (DISTINCT).
    con.execute("INSERT INTO content_file VALUES ('n1', 'lf1'), ('n2', 'lf1'), ('n1', 'lf2')")
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


class ExportCommandsTest(unittest.TestCase):
    def test_builds_the_two_delegated_commands(self):
        self.assertEqual(
            export_commands(VALID_ID, "/tmp/dest", "/usr/bin/kolibri"),
            [
                ["/usr/bin/kolibri", "manage", "exportchannel", VALID_ID, "/tmp/dest"],
                ["/usr/bin/kolibri", "manage", "exportcontent", VALID_ID, "/tmp/dest"],
            ],
        )


class ImportCommandsTest(unittest.TestCase):
    def test_builds_the_two_disk_import_commands(self):
        self.assertEqual(
            import_commands(VALID_ID, "/src/dir", "/usr/bin/kolibri"),
            [
                ["/usr/bin/kolibri", "manage", "importchannel", "disk", VALID_ID, "/src/dir"],
                ["/usr/bin/kolibri", "manage", "importcontent", "disk", VALID_ID, "/src/dir"],
            ],
        )


class KolibriCheckTest(unittest.TestCase):
    def setUp(self):
        self._d = tempfile.TemporaryDirectory()
        self.db = os.path.join(self._d.name, "db.sqlite3")
        _make_db(self.db, cid=VALID_ID)  # version 14
        self.adapter = KolibriAdapter(self.db)

    def tearDown(self):
        self._d.cleanup()

    def test_same_version(self):
        self.assertEqual(self.adapter.check({"line": VALID_ID, "version": 14}), "SAME_VERSION")

    def test_older_here_when_incoming_newer(self):
        self.assertEqual(self.adapter.check({"line": VALID_ID, "version": 20}), "OLDER_HERE")

    def test_newer_here_when_incoming_older(self):
        self.assertEqual(self.adapter.check({"line": VALID_ID, "version": 5}), "NEWER_HERE")

    def test_absent_when_no_such_channel(self):
        self.assertEqual(self.adapter.check({"line": "f" * 32, "version": 1}), "ABSENT")


class _ImportStubKolibri(KolibriAdapter):
    def _stage_import(self, channel_id, source):
        self.import_channel = channel_id
        self.import_source = source
        return True


def _bundle_tar(tar_path, channel_id):
    with tarfile.open(tar_path, "w") as t:
        for rel, data in [
            (f"{channel_id}/content/databases/{channel_id}.sqlite3", b"DB"),
            (f"{channel_id}/content/storage/a/b/abc.mp4", b"MP4"),
        ]:
            ti = tarfile.TarInfo(rel)
            ti.size = len(data)
            t.addfile(ti, io.BytesIO(data))


class KolibriImportTest(unittest.TestCase):
    def test_import_extracts_and_delegates_to_kolibri(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "db.sqlite3")
            _make_db(db, cid=VALID_ID)
            tar_path = os.path.join(d, "chan.tar")
            _bundle_tar(tar_path, VALID_ID)
            adapter = _ImportStubKolibri(db, staging_dir=os.path.join(d, "staging"))
            result = adapter.import_item({"identity": {"localRef": VALID_ID}}, tar_path)
            self.assertTrue(result["ok"])
            self.assertEqual(adapter.import_channel, VALID_ID)
            # single_child_dir found the inner <channel_id> bundle root.
            self.assertEqual(os.path.basename(adapter.import_source), VALID_ID)

    def test_import_rejects_bad_archive(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "db.sqlite3")
            _make_db(db, cid=VALID_ID)
            tar_path = os.path.join(d, "evil.tar")
            with tarfile.open(tar_path, "w") as t:
                data = b"x"
                ti = tarfile.TarInfo("../escape")
                ti.size = len(data)
                t.addfile(ti, io.BytesIO(data))
            adapter = _ImportStubKolibri(db, staging_dir=os.path.join(d, "staging"))
            result = adapter.import_item({"identity": {"localRef": VALID_ID}}, tar_path)
            self.assertFalse(result["ok"])


class _StubbedKolibri(KolibriAdapter):
    """Overrides the CLI seam so the export assembly + streaming is testable without a live
    Kolibri: it writes a tiny Kolibri-shaped export tree into dest."""

    def _stage_export(self, channel_id, dest):
        self.staged_dest = dest
        dbs = os.path.join(dest, "content", "databases")
        storage = os.path.join(dest, "content", "storage", "a", "b")
        os.makedirs(dbs)
        os.makedirs(storage)
        with open(os.path.join(dbs, channel_id + ".sqlite3"), "wb") as f:
            f.write(b"DB")
        with open(os.path.join(storage, "abc.mp4"), "wb") as f:
            f.write(b"MP4")
        return True


class KolibriExportTest(unittest.TestCase):
    def test_open_export_streams_tar_of_the_bundle_and_cleans_up(self):
        import io
        import tarfile

        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "db.sqlite3")
            _make_db(db, cid=VALID_ID)
            adapter = _StubbedKolibri(db, staging_dir=os.path.join(d, "staging"))
            stream = adapter.open_export(VALID_ID)
            self.assertIsNotNone(stream)
            self.assertEqual(stream.filename, f"{VALID_ID}.tar")
            blob = b"".join(stream.chunks)
            with tarfile.open(fileobj=io.BytesIO(blob), mode="r") as tar:
                names = sorted(m.name for m in tar.getmembers() if m.isfile())
            self.assertIn(f"{VALID_ID}/content/databases/{VALID_ID}.sqlite3", names)
            self.assertIn(f"{VALID_ID}/content/storage/a/b/abc.mp4", names)
            # the staging tree is removed after streaming.
            self.assertTrue(os.path.isdir(adapter.staged_dest))
            stream.on_close()
            self.assertFalse(os.path.exists(adapter.staged_dest))

    def test_open_export_rejects_non_channel_id_and_uninstalled(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "db.sqlite3")
            _make_db(db, cid=VALID_ID)
            adapter = _StubbedKolibri(db, staging_dir=os.path.join(d, "staging"))
            self.assertIsNone(adapter.open_export("not-a-channel-id"))
            self.assertIsNone(adapter.open_export("a" * 32))  # valid shape, not installed

    def test_open_export_fails_when_staging_disk_is_short(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "db.sqlite3")
            _make_db(db, cid=VALID_ID)  # channel available bytes = 100 in the fixture
            adapter = _StubbedKolibri(db, staging_dir=os.path.join(d, "staging"))
            # Force the preflight to see almost no free space.
            adapter._stage_export = lambda *a: (_ for _ in ()).throw(AssertionError("must not stage"))
            import cx_core.adapters.kolibri as k
            orig = k.shutil.disk_usage
            k.shutil.disk_usage = lambda p: type("U", (), {"total": 0, "used": 0, "free": 1})()
            try:
                self.assertIsNone(adapter.open_export(VALID_ID))
            finally:
                k.shutil.disk_usage = orig


if __name__ == "__main__":
    unittest.main()
