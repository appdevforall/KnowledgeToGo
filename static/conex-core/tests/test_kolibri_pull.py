"""Kolibri over rsync (K2GO-577): the share file list, the pull steps, the finalize commands and
the import manifest. No rsync and no Kolibri run: the transfer and the CLI are fakes."""

import json
import os
import sqlite3
import tempfile
import unittest

from conex_core.adapters.kolibri import KolibriAdapter, pull_finalize_commands
from conex_core.rsync_share import _LogTally
from conex_core.watchdog import CANCELLED, FAILED, OK, SuperviseResult

CID = "9b3463eaa85354eeb26a184fe1d9a04b"
F1 = "8b0a7472152ff9b4b46cacd2761d52d1"
F2 = "c61f30d1875c3b4b9a883d304c553956"
F3 = "8d08d00cd420f7ce94411d685d2e553e"
SOURCE = {"host": "192.168.43.1", "port": 9873, "module": "abc-0", "token": "tok"}


def _home():
    """A KOLIBRI_HOME with a main DB: two available files and one missing, three nodes."""
    home = tempfile.mkdtemp()
    os.makedirs(os.path.join(home, "content", "databases"))
    open(os.path.join(home, "content", "databases", CID + ".sqlite3"), "w").close()
    con = sqlite3.connect(os.path.join(home, "db.sqlite3"))
    con.executescript("""
        CREATE TABLE content_channelmetadata (id TEXT, name TEXT, version INTEGER);
        CREATE TABLE content_contentnode (id TEXT, channel_id TEXT, available INTEGER, kind TEXT);
        CREATE TABLE content_file (contentnode_id TEXT, local_file_id TEXT);
        CREATE TABLE content_localfile (id TEXT, extension TEXT, file_size INTEGER, available INTEGER);
    """)
    con.execute("INSERT INTO content_channelmetadata VALUES (?, 'Digital Awareness', 8)", (CID,))
    con.executemany("INSERT INTO content_contentnode VALUES (?, ?, ?, ?)", [
        ("root", CID, 1, "topic"), ("n1", CID, 1, "video"), ("n2", CID, 0, "h5p")])
    con.executemany("INSERT INTO content_file VALUES (?, ?)", [
        ("n1", F1), ("n1", F3), ("n2", F2), ("root", F1)])
    con.executemany("INSERT INTO content_localfile VALUES (?, ?, ?, ?)", [
        (F1, "png", 10, 1), (F3, "mp4", 30, 1), (F2, "h5p", 20, 0)])
    con.commit()
    con.close()
    return home


class ShareFilesTests(unittest.TestCase):
    def test_lists_the_db_and_only_available_files(self):
        home = _home()
        files = KolibriAdapter(os.path.join(home, "db.sqlite3")).share_files(CID)
        self.assertEqual(files.root, os.path.join(home, "content"))
        self.assertEqual(files.files[0], f"databases/{CID}.sqlite3")
        self.assertEqual(sorted(files.files[1:]), sorted([
            f"storage/8/b/{F1}.png", f"storage/8/d/{F3}.mp4"]))

    def test_unknown_or_unsafe_channel_is_not_shared(self):
        adapter = KolibriAdapter(os.path.join(_home(), "db.sqlite3"))
        self.assertIsNone(adapter.share_files("../etc"))
        self.assertIsNone(adapter.share_files("0" * 32))  # no channel DB on disk


class _Puller:
    def __init__(self, states=(OK, OK)):
        self.states = list(states)
        self.calls = []

    def _result(self):
        state = self.states.pop(0)
        return SuperviseResult(state, 0 if state == OK else 12, "ok" if state == OK else "exited with code 12")

    def pull_tree(self, source, dest_dir, work_dir, filters=(), ignore_existing=True,
                  should_cancel=None, on_progress=None):
        self.calls.append((dest_dir, tuple(filters), ignore_existing))
        if on_progress:
            on_progress(40)
        return self._result()


class _Adapter(KolibriAdapter):
    def __init__(self, db, finalize_state=OK):
        super().__init__(db)
        self.finalized = []
        self._state = finalize_state

    def _finalize_pull(self, channel_id, src, should_cancel=None):
        self.finalized.append((channel_id, src))
        return SuperviseResult(self._state, 0, "ok" if self._state == OK else "exited with code 1")


class PullItemTests(unittest.TestCase):
    def setUp(self):
        self.home = _home()
        self.partial = tempfile.mkdtemp()
        self.entry = {"identity": {"localRef": CID}}

    def test_pulls_db_then_storage_then_links(self):
        adapter, puller = _Adapter(os.path.join(self.home, "db.sqlite3")), _Puller()
        moved = []
        result = adapter.pull_item(self.entry, SOURCE, self.partial, puller, on_progress=moved.append)
        self.assertTrue(result["ok"])
        db_dest, db_filters, db_ignore = puller.calls[0]
        self.assertTrue(os.path.normpath(db_dest).endswith(os.path.join("src", "content")))
        self.assertEqual(db_filters, ("+ /databases/", f"+ /databases/{CID}.sqlite3", "- *"))
        self.assertFalse(db_ignore)
        self.assertEqual(puller.calls[1], (os.path.join(self.home, "content"),
                                           ("- /databases/",), True))
        self.assertEqual(moved, [40])
        self.assertEqual(len(adapter.finalized), 1)
        self.assertFalse(os.path.exists(os.path.join(self.partial, "kolibri", CID)))  # work dir gone

    def test_transfer_drop_keeps_the_work_dir_and_skips_linking(self):
        adapter, puller = _Adapter(os.path.join(self.home, "db.sqlite3")), _Puller((OK, FAILED))
        result = adapter.pull_item(self.entry, SOURCE, self.partial, puller)
        self.assertFalse(result["ok"])
        self.assertIn("transfer", result["reason"])
        self.assertEqual(adapter.finalized, [])
        self.assertTrue(os.path.isdir(os.path.join(self.partial, "kolibri", CID)))

    def test_cancel_and_link_failure(self):
        adapter = _Adapter(os.path.join(self.home, "db.sqlite3"))
        result = adapter.pull_item(self.entry, SOURCE, self.partial, _Puller((CANCELLED,)))
        self.assertEqual(result["reason"], "import cancelled")
        failing = _Adapter(os.path.join(self.home, "db.sqlite3"), finalize_state=FAILED)
        result = failing.pull_item(self.entry, SOURCE, self.partial, _Puller())
        self.assertIn("kolibri import", result["reason"])

    def test_invalid_channel_id(self):
        adapter = _Adapter(os.path.join(self.home, "db.sqlite3"))
        bad = {"identity": {"localRef": "../x"}}
        self.assertFalse(adapter.pull_item(bad, SOURCE, self.partial, _Puller())["ok"])


class FinalizeTests(unittest.TestCase):
    def test_commands_scan_before_importcontent(self):
        cmds = pull_finalize_commands(CID, "/w/src", "/usr/bin/kolibri")
        self.assertEqual([c[2] for c in cmds], ["importchannel", "scanforcontent", "importcontent"])
        self.assertEqual(cmds[1][3:], ["--channels", CID, "--channel-import-mode", "none"])
        self.assertEqual(cmds[0][3:], ["disk", CID, "/w/src"])

    def test_manifest_lists_available_leaf_nodes(self):
        home = _home()
        src = os.path.join(tempfile.mkdtemp(), "src")
        os.makedirs(os.path.join(src, "content"))
        KolibriAdapter(os.path.join(home, "db.sqlite3"))._write_pull_manifest(CID, src)
        with open(os.path.join(src, "content", "manifest.json")) as f:
            manifest = json.load(f)
        self.assertEqual(manifest, {"channels": [{"id": CID, "version": 8,
                                                  "include_node_ids": ["n1"]}]})


class LogTallyTests(unittest.TestCase):
    def test_sums_recv_lines_incrementally(self):
        log = os.path.join(tempfile.mkdtemp(), "t.log")
        tally = _LogTally(log)
        self.assertEqual(tally.read(), 0)  # no log yet
        with open(log, "w") as f:
            f.write("2026/11/16 10:00:00 [12] receiving file list\n")
            f.write("2026/11/16 10:00:01 [12] recv 100\n2026/11/16 10:00:02 [12] recv 2")
        self.assertEqual(tally.read(), 100)  # the half-written last line waits
        with open(log, "a") as f:
            f.write("50\n2026/11/16 10:00:03 [12] sent 10 bytes  received 999 bytes\n")
        self.assertEqual(tally.read(), 350)


if __name__ == "__main__":
    unittest.main()
