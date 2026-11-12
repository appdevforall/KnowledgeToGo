"""Unit tests for k2go_box_identity (binding, scrub, start flow). Run: python -m unittest -v"""
import os
import sqlite3
import tempfile
import unittest
from unittest import mock

import k2go_box_identity as bi

DEV_A = bytes.fromhex("00" * 16)
DEV_B = bytes.fromhex("ff" * 16)


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        for name, value in (("BINDING_DIR", os.path.join(self.tmp.name, "binding")),
                            ("DEVICE_ID", os.path.join(self.tmp.name, "device-id"))):
            p = mock.patch.object(bi, name, value)
            p.start()
            self.addCleanup(p.stop)


class BindingTest(Base):
    def test_matches_only_the_phone_that_wrote_it(self):
        bi.bind("kolibri", DEV_A)
        self.assertTrue(bi.bound("kolibri", DEV_A))
        self.assertFalse(bi.bound("kolibri", DEV_B))

    def test_is_per_item(self):
        bi.bind("system", DEV_A)
        self.assertFalse(bi.bound("kolibri", DEV_A))

    def test_holds_no_raw_id(self):
        dev = bytes.fromhex("0123456789abcdef0123456789abcdef")
        bi.bind("system", dev)
        with open(os.path.join(bi.BINDING_DIR, "system")) as f:
            self.assertNotIn(dev.hex(), f.read())

    def test_garbage_is_foreign(self):
        os.makedirs(bi.BINDING_DIR)
        with open(os.path.join(bi.BINDING_DIR, "system"), "w") as f:
            f.write("zz")
        self.assertFalse(bi.bound("system", DEV_A))

    def test_device_id(self):
        self.assertIsNone(bi.device_id())
        with open(bi.DEVICE_ID, "w") as f:
            f.write("not-hex")
        self.assertIsNone(bi.device_id())
        with open(bi.DEVICE_ID, "w") as f:
            f.write("ab" * 16 + "\n")
        self.assertEqual(bytes.fromhex("ab" * 16), bi.device_id())


class ScrubTest(Base):
    def test_deletes_rows_and_skips_missing_tables(self):
        db = os.path.join(self.tmp.name, "n.sqlite3")
        con = sqlite3.connect(db)
        con.execute("PRAGMA journal_mode=WAL")
        con.execute("CREATE TABLE discovery_networklocation (id TEXT, location_type TEXT)")
        con.executemany("INSERT INTO discovery_networklocation VALUES (?, ?)",
                        [("studio", "reserved"), ("peer-phone", "dynamic")])
        con.commit()
        con.close()
        bi.scrub(db, (("discovery_networklocation",
                       "DELETE FROM discovery_networklocation WHERE location_type != 'reserved'"),
                      ("no_such_table", "DELETE FROM no_such_table")))
        con = sqlite3.connect(db)
        self.assertEqual([("studio",)], con.execute("SELECT id FROM discovery_networklocation").fetchall())
        con.close()
        with open(db, "rb") as f:
            self.assertNotIn(b"peer-phone", f.read())
        self.assertFalse(os.path.exists(db + "-wal") and os.path.getsize(db + "-wal"))


class StartFlowTest(Base):
    def run_main(self, dev):
        if dev:
            with open(bi.DEVICE_ID, "w") as f:
                f.write(dev.hex())
        calls = []
        items = (("x", lambda: calls.append("make") or [], lambda: calls.append("rotate") or ["v"],
                  lambda: calls.append("strip")),)
        with mock.patch.object(bi, "ITEMS", items), mock.patch("builtins.print"):
            bi.main(["k2go-box-identity"])
        return calls

    def test_foreign_tree_rotates_once(self):
        os.makedirs(bi.BINDING_DIR)
        bi.bind("x", DEV_B)
        self.assertEqual(["rotate"], self.run_main(DEV_A))
        self.assertEqual(["make"], self.run_main(DEV_A))

    def test_old_app_never_rotates(self):
        self.assertEqual(["make"], self.run_main(None))

    def test_failed_rotation_is_not_bound(self):
        with open(bi.DEVICE_ID, "w") as f:
            f.write(DEV_A.hex())

        def boom():
            raise RuntimeError()

        with mock.patch.object(bi, "ITEMS", (("x", None, boom, None),)), mock.patch("builtins.print"):
            bi.main(["k2go-box-identity"])
        self.assertFalse(bi.bound("x", DEV_A))


if __name__ == "__main__":
    unittest.main()
