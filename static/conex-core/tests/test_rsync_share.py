"""rsync Share transport tests (K2GO-577): the daemon config and lifecycle, the pull client, the
partial sweep, the gate's rsync modules and progress, the pull import op and the Kiwix pull.
No real rsync runs: the process and the transfer are fakes behind the seams."""

import os
import tempfile
import time
import unittest

from conex_core.adapters.kiwix import KiwixAdapter
from conex_core.import_ops import ImportOps
from conex_core.model import AUTHORITATIVE, Dedup, Identity, ManifestItem
from conex_core.ports import ContentAdapter, ShareFiles
from conex_core.registry import Registry
from conex_core.rsync_share import (
    SHARE_USER,
    Module,
    RsyncDaemon,
    RsyncPuller,
    render_config,
    render_filter,
    sweep_partials,
    valid_source,
)
from conex_core.share_ops import ShareOps
from conex_core.watchdog import CANCELLED, FAILED, OK, SuperviseResult

SOURCE = {"host": "192.168.43.1", "port": 9873, "module": "abc123-0", "token": "tok"}


class _FakeProc:
    def __init__(self, pid=4242):
        self.pid = pid
        self.alive = True

    def poll(self):
        return None if self.alive else 0

    def wait(self, timeout=None):
        return 0


class _FakePopen:
    def __init__(self):
        self.calls = []
        self.procs = []

    def __call__(self, argv, **kwargs):
        self.calls.append((argv, kwargs))
        proc = _FakeProc(pid=1000 + len(self.calls))
        self.procs.append(proc)
        return proc


class _Killer:
    def __init__(self, popen):
        self.popen = popen
        self.signals = []

    def __call__(self, pgid, sig):
        self.signals.append((pgid, sig))
        for proc in self.popen.procs:
            if proc.pid == pgid:
                proc.alive = False


def _daemon(run_dir, available=True):
    popen = _FakePopen()
    killer = _Killer(popen)
    rsync_bin = os.path.join(run_dir, "..", "rsync-bin")
    if available:
        open(rsync_bin, "w").close()
    daemon = RsyncDaemon(run_dir, 9873, rsync_bin, popen=popen, killpg=killer)
    return daemon, popen, killer


def _module(name="s1-0", files=("a.zim",), secret="tok", root="/library/zims/content"):
    return Module(name=name, root=root, files=list(files), secret=secret)


class RenderTests(unittest.TestCase):
    def test_filter_admits_only_the_file(self):
        self.assertEqual(render_filter(["a.zim"]), "+ /a.zim\n- *\n")

    def test_filter_admits_nested_parents_once(self):
        text = render_filter(["content/storage/a/b/x.mp4", "content/storage/a/c/y.png"])
        self.assertEqual(text.splitlines(), [
            "+ /content/", "+ /content/storage/", "+ /content/storage/a/",
            "+ /content/storage/a/b/", "+ /content/storage/a/b/x.mp4",
            "+ /content/storage/a/c/", "+ /content/storage/a/c/y.png", "- *"])

    def test_config_is_read_only_authenticated_and_logs_nothing(self):
        text = render_config([_module()], "/run/r", 10123, 10123)
        for line in ("[s1-0]", "  read only = yes", "  list = no", f"  auth users = {SHARE_USER}",
                     "  path = /library/zims/content", "log file = /dev/null", "uid = 10123",
                     "  secrets file = " + os.path.join("/run/r", "s1-0.secrets")):
            self.assertIn(line, text.splitlines())


class DaemonTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.run_dir = os.path.join(self.tmp, "rsyncd")

    def test_first_module_starts_last_close_stops(self):
        daemon, popen, killer = _daemon(self.run_dir)
        self.assertTrue(daemon.set_modules([_module()]))
        self.assertEqual(len(popen.calls), 1)
        argv, kwargs = popen.calls[0]
        self.assertIn("--daemon", argv)
        self.assertIn("--port=9873", argv)
        self.assertTrue(kwargs.get("start_new_session"))
        with open(os.path.join(self.run_dir, "s1-0.secrets")) as f:
            self.assertEqual(f.read(), f"{SHARE_USER}:tok\n")
        # A second session only rewrites the config: the running daemon re-reads it.
        self.assertTrue(daemon.set_modules([_module(), _module(name="s2-0", secret="t2")]))
        self.assertEqual(len(popen.calls), 1)
        self.assertFalse(daemon.set_modules([]))
        self.assertTrue(killer.signals)
        self.assertFalse(os.path.exists(self.run_dir))  # secrets are gone with the daemon

    def test_closed_session_secrets_are_removed(self):
        daemon, _popen, _killer = _daemon(self.run_dir)
        daemon.set_modules([_module(), _module(name="s2-0")])
        daemon.set_modules([_module(name="s2-0")])
        self.assertFalse(os.path.exists(os.path.join(self.run_dir, "s1-0.secrets")))
        self.assertTrue(os.path.exists(os.path.join(self.run_dir, "s2-0.secrets")))

    def test_unsafe_module_is_never_written(self):
        daemon, popen, _killer = _daemon(self.run_dir)
        bad = [_module(name="x]y"), _module(files=("a.zim\n[evil]",)), _module(secret="a\nb")]
        self.assertFalse(daemon.set_modules(bad))
        self.assertEqual(popen.calls, [])

    def test_no_rsync_binary_means_not_serving(self):
        daemon, popen, _killer = _daemon(self.run_dir, available=False)
        self.assertFalse(daemon.set_modules([_module()]))
        self.assertEqual(popen.calls, [])

    def test_startup_sweep_kills_the_recorded_group(self):
        os.makedirs(self.run_dir)
        with open(os.path.join(self.run_dir, "daemon.pid"), "w") as f:
            f.write("777")
        daemon, _popen, killer = _daemon(self.run_dir)
        daemon.sweep_startup()
        self.assertEqual(killer.signals[0][0], 777)
        self.assertFalse(os.path.exists(self.run_dir))


class SourceAndPullTests(unittest.TestCase):
    def test_valid_source(self):
        self.assertIsNone(valid_source(SOURCE))
        for key, value in (("host", "a b"), ("host", "-x"), ("port", 0), ("port", True),
                           ("port", "9873"), ("module", "../x"), ("token", ""),
                           ("token", "a\nb")):
            self.assertIsNotNone(valid_source(dict(SOURCE, **{key: value})), (key, value))
        self.assertIsNotNone(valid_source(None))

    def test_pull_keeps_the_password_out_of_argv_and_reports_file_size(self):
        tmp = tempfile.mkdtemp()
        dest = os.path.join(tmp, "a.zim")
        seen = {}

        def runner(argv, env, **kwargs):
            seen["argv"], seen["env"] = argv, env
            with open(dest, "wb") as f:
                f.write(b"x" * 10)
            kwargs["on_progress"](0)
            return SuperviseResult(OK, 0, "ok")

        moved = []
        result = RsyncPuller("/usr/bin/rsync", runner=runner).pull_file(
            SOURCE, "a.zim", dest, on_progress=moved.append)
        self.assertTrue(result.ok)
        self.assertEqual(moved, [10])
        self.assertNotIn("tok", " ".join(seen["argv"]))
        self.assertEqual(seen["env"]["RSYNC_PASSWORD"], "tok")
        self.assertIn("--inplace", seen["argv"])
        self.assertIn("--append-verify", seen["argv"])
        self.assertIn(f"rsync://{SHARE_USER}@192.168.43.1:9873/abc123-0/a.zim", seen["argv"])

    def test_sweep_removes_only_old_partials(self):
        root = tempfile.mkdtemp()
        os.makedirs(os.path.join(root, "kiwix"))
        old, new = (os.path.join(root, "kiwix", n) for n in ("old.zim", "new.zim"))
        for path in (old, new):
            open(path, "w").close()
        now = time.time()
        os.utime(old, (now - 3 * 24 * 3600, now - 3 * 24 * 3600))
        sweep_partials(root, now=now)
        self.assertFalse(os.path.exists(old))
        self.assertTrue(os.path.exists(new))


def _mitem(app, local_ref, total):
    return ManifestItem(
        app=app, kind="zim", title=local_ref, bytes=total,
        identity=Identity(local_ref, Dedup(line={}, build=None, version="", id_kind=AUTHORITATIVE)))


class _ShareFake(ContentAdapter):
    app = "kiwix"

    def __init__(self, items, shareable=True):
        self._items = dict(items)
        self._shareable = shareable

    def is_installed(self):
        return True

    def list(self):
        return [_mitem(self.app, ref, size) for ref, size in self._items.items()]

    def share_files(self, local_ref):
        if not self._shareable or local_ref not in self._items:
            return None
        return ShareFiles(root="/zims", files=[local_ref])


class _RecDaemon:
    port = 9873

    def __init__(self, serving=True):
        self.modules = None
        self.serving = serving
        self.swept = False

    def running(self):
        return self.serving and bool(self.modules)

    def set_modules(self, modules):
        self.modules = list(modules)
        return self.running()

    def sweep_startup(self):
        self.swept = True


class ShareGateRsyncTests(unittest.TestCase):
    def _share(self, adapter, daemon):
        return ShareOps(Registry([adapter]), export_ops=None, rsync=daemon)

    def test_manifest_advertises_the_module_and_close_revokes_it(self):
        daemon = _RecDaemon()
        share = self._share(_ShareFake({"a.zim": 5, "b.zim": 7}), daemon)
        _, created = share.create([{"app": "kiwix", "localRef": "a.zim"},
                                   {"app": "kiwix", "localRef": "b.zim"}])
        sid, token = created["sessionId"], created["token"]
        self.assertEqual([m.name for m in daemon.modules], [f"{sid}-0", f"{sid}-1"])
        self.assertTrue(all(m.secret == token for m in daemon.modules))
        _, manifest = share.manifest(sid, token)
        self.assertEqual(manifest["items"][0]["rsync"], {"port": 9873, "module": f"{sid}-0"})
        self.assertEqual(share.delete(sid, token)[0], 200)
        self.assertEqual(daemon.modules, [])

    def test_no_rsync_block_when_daemon_is_not_serving(self):
        daemon = _RecDaemon(serving=False)
        share = self._share(_ShareFake({"a.zim": 5}), daemon)
        _, created = share.create([{"app": "kiwix", "localRef": "a.zim"}])
        _, manifest = share.manifest(created["sessionId"], created["token"])
        self.assertNotIn("rsync", manifest["items"][0])

    def test_progress_report_and_view(self):
        share = self._share(_ShareFake({"a.zim": 5}), _RecDaemon())
        _, created = share.create([{"app": "kiwix", "localRef": "a.zim"}])
        sid, token = created["sessionId"], created["token"]
        report = {"itemRef": "0", "movedBytes": 3, "totalBytes": 5, "state": "running"}
        self.assertEqual(share.report_progress(sid, token, report)[0], 200)
        self.assertEqual(share.report_progress(sid, "bad", report)[0], 403)
        self.assertEqual(share.report_progress(sid, token, dict(report, itemRef="9"))[0], 404)
        self.assertEqual(share.report_progress(sid, token, dict(report, movedBytes=-1))[0], 400)
        self.assertEqual(share.report_progress(sid, token, dict(report, state="x"))[0], 400)
        share.report_progress(sid, token, dict(report, movedBytes=5, state="done"))
        _, view = share.progress(sid, token)
        self.assertEqual(view["items"], [{"itemRef": "0", "movedBytes": 5, "totalBytes": 5,
                                          "state": "done", "delivered": True}])

    def test_startup_sweep_reaches_the_daemon(self):
        daemon = _RecDaemon()
        self._share(_ShareFake({}), daemon).sweep_startup()
        self.assertTrue(daemon.swept)


class _PullAdapter(_ShareFake):
    def __init__(self):
        super().__init__({})
        self.calls = []

    def pull_item(self, entry, source, partial_dir, puller, should_cancel=None, on_progress=None):
        self.calls.append((entry, source, partial_dir))
        on_progress(4)
        return {"ok": True, "app": "kiwix", "localRef": "a.zim"}


class _OkPuller:
    def available(self):
        return True


class PullOpTests(unittest.TestCase):
    def _wait(self, ops, op_id):
        for _ in range(100):
            _, st = ops.status(op_id)
            if st["state"] != "running":
                return st
            time.sleep(0.01)
        self.fail("op did not finish")

    def test_pull_runs_the_adapter_and_finishes(self):
        adapter = _PullAdapter()
        ops = ImportOps(Registry([adapter]), tempfile.mkdtemp(), partial_root="/p",
                        puller=_OkPuller())
        status, started = ops.start_pull("kiwix", {"bytes": 4}, SOURCE)
        self.assertEqual(status, 200)
        st = self._wait(ops, started["opId"])
        self.assertEqual(st["state"], "done")
        self.assertEqual(adapter.calls[0][1], SOURCE)
        self.assertEqual(adapter.calls[0][2], "/p")

    def test_pull_rejects_bad_requests(self):
        ops = ImportOps(Registry([_PullAdapter()]), tempfile.mkdtemp(), partial_root="/p",
                        puller=_OkPuller())
        self.assertEqual(ops.start_pull("nope", {}, SOURCE)[0], 404)
        self.assertEqual(ops.start_pull("kiwix", None, SOURCE)[0], 400)
        self.assertEqual(ops.start_pull("kiwix", {}, dict(SOURCE, port=0))[0], 400)
        no_pull = ImportOps(Registry([_PullAdapter()]), tempfile.mkdtemp())
        self.assertEqual(no_pull.start_pull("kiwix", {}, SOURCE)[0], 501)


class _FakePuller:
    def __init__(self, state=OK, data=b"ZIMDATA"):
        self.state = state
        self.data = data
        self.calls = []

    def pull_file(self, source, remote_path, dest_path, should_cancel=None, on_progress=None):
        self.calls.append((remote_path, dest_path))
        with open(dest_path, "ab") as f:
            f.write(self.data)
        if on_progress:
            on_progress(os.path.getsize(dest_path))
        detail = "ok" if self.state == OK else "exited with code 12"
        return SuperviseResult(self.state, 0 if self.state == OK else 12, detail)


class KiwixPullTests(unittest.TestCase):
    def setUp(self):
        self.zims = tempfile.mkdtemp()
        self.partial = tempfile.mkdtemp()
        self.adapter = KiwixAdapter(self.zims, indexer="")
        self.entry = {"identity": {"localRef": "wiki_en_all_2026-09.zim"}}

    def test_success_renames_into_place(self):
        puller = _FakePuller()
        result = self.adapter.pull_item(self.entry, SOURCE, self.partial, puller)
        self.assertTrue(result["ok"])
        self.assertEqual(puller.calls[0][0], "wiki_en_all_2026-09.zim")
        self.assertTrue(os.path.isfile(os.path.join(self.zims, "wiki_en_all_2026-09.zim")))
        self.assertFalse(os.path.exists(puller.calls[0][1]))  # the partial became the file

    def test_drop_keeps_the_partial_for_a_resume(self):
        puller = _FakePuller(state=FAILED)
        result = self.adapter.pull_item(self.entry, SOURCE, self.partial, puller)
        self.assertFalse(result["ok"])
        self.assertTrue(os.path.isfile(puller.calls[0][1]))
        self.assertFalse(os.path.exists(os.path.join(self.zims, "wiki_en_all_2026-09.zim")))

    def test_cancel_keeps_the_partial(self):
        puller = _FakePuller(state=CANCELLED)
        result = self.adapter.pull_item(self.entry, SOURCE, self.partial, puller)
        self.assertEqual(result["reason"], "import cancelled")
        self.assertTrue(os.path.isfile(puller.calls[0][1]))

    def test_unsafe_local_ref_is_rejected(self):
        bad = {"identity": {"localRef": "../x.zim"}}
        self.assertFalse(self.adapter.pull_item(bad, SOURCE, self.partial, _FakePuller())["ok"])

    def test_share_files_lists_only_the_zim(self):
        open(os.path.join(self.zims, "a.zim"), "w").close()
        files = self.adapter.share_files("a.zim")
        self.assertEqual((files.root, files.files), (self.zims, ["a.zim"]))
        self.assertIsNone(self.adapter.share_files("missing.zim"))


if __name__ == "__main__":
    unittest.main()
