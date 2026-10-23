import os
import tempfile
import threading
import time
import unittest

import cx_core.export_ops as export_ops
from cx_core.export_ops import ExportOps
from cx_core.ports import ContentAdapter, ExportPlan, ExportStream
from cx_core.registry import Registry


class _DirectFake(ContentAdapter):
    """A direct app (Kiwix / Calibre): nothing to stage, ready at once."""

    app = "kiwix"

    def __init__(self, total=42, present=True):
        self._total = total
        self._present = present

    def is_installed(self):
        return True

    def list(self):
        return []

    def prepare_export(self, local_ref):
        if not self._present:
            return None
        return ExportPlan(False, self._total, local_ref, "application/octet-stream")

    def open_fetch(self, local_ref, staged_dir=None):
        if not self._present:
            return None
        return ExportStream(local_ref, "application/octet-stream", [b"x" * self._total])


class _StagingFake(ContentAdapter):
    """A staging app (Kolibri): stage_export writes into dest, gated by an event so a test
    can observe the staging state before the op flips to ready."""

    app = "kolibri"

    def __init__(self, total=300, gate=None, fail=False):
        self._total = total
        self._gate = gate
        self._fail = fail

    def is_installed(self):
        return True

    def list(self):
        return []

    def prepare_export(self, local_ref):
        if local_ref == "missing":
            return None
        return ExportPlan(True, self._total, f"{local_ref}.tar", "application/x-tar")

    def stage_export(self, local_ref, dest, should_cancel=None):
        if self._gate is not None:
            self._gate.wait(5)
        if self._fail:
            return False
        with open(os.path.join(dest, "part.bin"), "wb") as f:
            f.write(b"x" * self._total)
        return True

    def open_fetch(self, local_ref, staged_dir=None):
        path = os.path.join(staged_dir or "", "part.bin")
        data = b""
        if os.path.isfile(path):
            with open(path, "rb") as f:
                data = f.read()
        return ExportStream(f"{local_ref}.tar", "application/x-tar", [data])


def _ops(adapter, staging, ttl=60):
    reg = Registry([adapter])
    return ExportOps(reg, staging_dir=staging, ttl_seconds=ttl)


def _wait_state(ops, op_id, want, timeout=5):
    end = time.time() + timeout
    body = {}
    while time.time() < end:
        _, body = ops.status(op_id)
        if body.get("state") == want:
            return body
        time.sleep(0.01)
    return body


class PrepareTest(unittest.TestCase):
    def test_direct_app_is_ready_at_once(self):
        with tempfile.TemporaryDirectory() as d:
            ops = _ops(_DirectFake(total=42), d)
            status, body = ops.prepare("kiwix", "a.zim")
            self.assertEqual(status, 200)
            self.assertEqual(body["state"], "ready")
            self.assertEqual(body["totalBytes"], 42)
            self.assertIn("opId", body)

    def test_staging_app_starts_staging(self):
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()
            gate.set()  # let staging run to completion
            ops = _ops(_StagingFake(total=300, gate=gate), d)
            status, body = ops.prepare("kolibri", "chan")
            self.assertEqual(status, 200)
            self.assertEqual(body["state"], "staging")
            self.assertEqual(body["totalBytes"], 300)
            # the op flips to ready once the staging thread finishes.
            ready = _wait_state(ops, body["opId"], "ready")
            self.assertEqual(ready["state"], "ready")

    def test_unknown_app_404(self):
        with tempfile.TemporaryDirectory() as d:
            ops = _ops(_DirectFake(), d)
            status, _ = ops.prepare("nope", "x")
            self.assertEqual(status, 404)

    def test_not_exportable_404(self):
        with tempfile.TemporaryDirectory() as d:
            ops = _ops(_StagingFake(), d)
            status, _ = ops.prepare("kolibri", "missing")
            self.assertEqual(status, 404)

    def test_no_room_507(self):
        with tempfile.TemporaryDirectory() as d:
            ops = _ops(_StagingFake(total=10 ** 9), d)
            orig = export_ops.has_room
            export_ops.has_room = lambda path, n: False
            try:
                status, _ = ops.prepare("kolibri", "chan")
            finally:
                export_ops.has_room = orig
            self.assertEqual(status, 507)


class StatusTest(unittest.TestCase):
    def test_staged_bytes_grows_then_flips_to_ready(self):
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()  # hold staging open
            ops = _ops(_StagingFake(total=300, gate=gate), d)
            _, prep = ops.prepare("kolibri", "chan")
            op_id = prep["opId"]
            # Before the gate opens the temp is empty: staging, 0 staged.
            _, mid = ops.status(op_id)
            self.assertEqual(mid["state"], "staging")
            self.assertEqual(mid["stagedBytes"], 0)
            gate.set()
            ready = _wait_state(ops, op_id, "ready")
            self.assertEqual(ready["state"], "ready")
            self.assertEqual(ready["stagedBytes"], 300)
            self.assertEqual(ready["totalBytes"], 300)

    def test_unknown_op_404(self):
        with tempfile.TemporaryDirectory() as d:
            ops = _ops(_DirectFake(), d)
            status, _ = ops.status("deadbeef")
            self.assertEqual(status, 404)

    def test_staging_failure_becomes_error(self):
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()
            gate.set()
            ops = _ops(_StagingFake(gate=gate, fail=True), d)
            _, prep = ops.prepare("kolibri", "chan")
            body = _wait_state(ops, prep["opId"], "error")
            self.assertEqual(body["state"], "error")
            self.assertIn("error", body)


class FetchTest(unittest.TestCase):
    def test_fetch_streams_then_on_close_drops_op_and_temp(self):
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()
            gate.set()
            ops = _ops(_StagingFake(total=120, gate=gate), d)
            _, prep = ops.prepare("kolibri", "chan")
            op_id = prep["opId"]
            _wait_state(ops, op_id, "ready")
            temp = ops._ops[op_id].temp
            self.assertTrue(os.path.isdir(temp))
            status, stream = ops.fetch(op_id)
            self.assertEqual(status, 200)
            data = b"".join(stream.chunks)
            self.assertEqual(len(data), 120)
            # the server calls on_close after streaming: it drops the op and removes the temp.
            stream.on_close()
            self.assertFalse(os.path.exists(temp))
            self.assertEqual(ops.status(op_id)[0], 404)

    def test_fetch_before_ready_409(self):
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()  # keep it staging
            ops = _ops(_StagingFake(gate=gate), d)
            _, prep = ops.prepare("kolibri", "chan")
            status, _ = ops.fetch(prep["opId"])
            self.assertEqual(status, 409)
            # Release the staging thread and let it finish before the temp dir is cleaned up,
            # so the thread is not writing into a dir being removed under it.
            gate.set()
            _wait_state(ops, prep["opId"], "ready")

    def test_fetch_unknown_op_404(self):
        with tempfile.TemporaryDirectory() as d:
            ops = _ops(_DirectFake(), d)
            status, _ = ops.fetch("nope")
            self.assertEqual(status, 404)

    def test_fetch_direct_app_has_no_temp(self):
        with tempfile.TemporaryDirectory() as d:
            ops = _ops(_DirectFake(total=7), d)
            _, prep = ops.prepare("kiwix", "a.zim")
            status, stream = ops.fetch(prep["opId"])
            self.assertEqual(status, 200)
            self.assertEqual(len(b"".join(stream.chunks)), 7)
            stream.on_close()  # no temp, just drops the op
            self.assertEqual(ops.status(prep["opId"])[0], 404)


class DeleteTest(unittest.TestCase):
    def test_delete_drops_op_and_temp_idempotent(self):
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()
            gate.set()
            ops = _ops(_StagingFake(gate=gate), d)
            _, prep = ops.prepare("kolibri", "chan")
            op_id = prep["opId"]
            _wait_state(ops, op_id, "ready")
            temp = ops._ops[op_id].temp
            self.assertEqual(ops.delete(op_id), (200, {"ok": True}))
            self.assertFalse(os.path.exists(temp))
            self.assertEqual(ops.status(op_id)[0], 404)
            # idempotent: a repeat and an unknown op are both 200 ok.
            self.assertEqual(ops.delete(op_id), (200, {"ok": True}))
            self.assertEqual(ops.delete("never-existed"), (200, {"ok": True}))


class SweepTest(unittest.TestCase):
    def test_ttl_sweep_reclaims_an_abandoned_op(self):
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()
            gate.set()
            ops = _ops(_StagingFake(gate=gate), d, ttl=60)
            _, prep = ops.prepare("kolibri", "chan")
            op_id = prep["opId"]
            _wait_state(ops, op_id, "ready")
            temp = ops._ops[op_id].temp
            # Backdate last_seen past the TTL, then run the lazy sweep.
            ops._ops[op_id].last_seen -= 120
            ops._sweep_expired()
            self.assertNotIn(op_id, ops._ops)
            self.assertFalse(os.path.exists(temp))

    def test_ttl_sweep_never_reclaims_an_op_being_fetched(self):
        # A multi-gigabyte fetch can stream longer than the TTL without bumping last_seen; the
        # sweep must not rmtree its temp out from under the open stream.
        with tempfile.TemporaryDirectory() as d:
            gate = threading.Event()
            gate.set()
            ops = _ops(_StagingFake(gate=gate), d, ttl=60)
            _, prep = ops.prepare("kolibri", "chan")
            op_id = prep["opId"]
            _wait_state(ops, op_id, "ready")
            status, stream = ops.fetch(op_id)  # opens the stream, pins the op in use
            self.assertEqual(status, 200)
            temp = ops._ops[op_id].temp
            ops._ops[op_id].last_seen -= 120  # older than the TTL
            ops._sweep_expired()
            self.assertIn(op_id, ops._ops)      # pinned: survives the sweep
            self.assertTrue(os.path.exists(temp))
            b"".join(stream.chunks)             # drain, then close as the server would
            stream.on_close()
            self.assertNotIn(op_id, ops._ops)
            self.assertFalse(os.path.exists(temp))

    def test_startup_sweep_removes_export_and_legacy_temps_only(self):
        with tempfile.TemporaryDirectory() as d:
            orphan = os.path.join(d, "cxexport-abc123")
            legacy = os.path.join(d, "cxkolibri-old99")  # a pre-change temp (e.g. on the A16)
            an_import = os.path.join(d, "cximport-xyz")   # an in-flight import must survive
            unrelated = os.path.join(d, "kolibri")
            for p in (orphan, legacy, an_import, unrelated):
                os.makedirs(p)
                with open(os.path.join(p, "f"), "wb") as f:
                    f.write(b"x")
            ops = _ops(_DirectFake(), d)
            ops.sweep_startup()
            self.assertFalse(os.path.exists(orphan))
            self.assertFalse(os.path.exists(legacy))
            self.assertTrue(os.path.exists(an_import))
            self.assertTrue(os.path.exists(unrelated))

    def test_startup_sweep_tolerates_missing_dir(self):
        ops = _ops(_DirectFake(), "/no/such/staging/dir")
        ops.sweep_startup()  # must not raise


if __name__ == "__main__":
    unittest.main()
