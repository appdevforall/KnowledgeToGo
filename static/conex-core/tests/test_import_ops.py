import os
import tempfile
import threading
import time
import unittest

from conex_core.import_ops import ImportOps
from conex_core.ports import ContentAdapter
from conex_core.registry import Registry


class _ImportFake(ContentAdapter):
    """A fake import adapter: import_item returns a configured result, optionally firing progress
    first and optionally waiting on a gate so a test can observe the running state."""

    app = "kiwix"

    def __init__(self, result=None, gate=None, progress=None):
        self._result = result if result is not None else {"ok": True, "app": "kiwix", "localRef": "r"}
        self._gate = gate
        self._progress = progress or []
        self.saw_cancel = False

    def is_installed(self):
        return True

    def list(self):
        return []

    def import_item(self, entry, payload_path, should_cancel=None, on_progress=None):
        for value in self._progress:
            if on_progress:
                on_progress(value)
        if self._gate is not None:
            self._gate.wait(5)
        if should_cancel and should_cancel():
            self.saw_cancel = True
        return self._result


def _ops(adapter, root, ttl=60):
    reg = Registry([adapter], import_root=root)
    return ImportOps(reg, import_root=root, ttl_seconds=ttl)


def _payload(root, name="a.zim"):
    path = os.path.join(root, name)
    with open(path, "wb") as f:
        f.write(b"x")
    return path


def _wait_state(ops, op_id, want, timeout=5):
    end = time.time() + timeout
    body = {}
    while time.time() < end:
        _, body = ops.status(op_id)
        if body.get("state") == want:
            return body
        time.sleep(0.01)
    return body


class StartTest(unittest.TestCase):
    def test_start_runs_and_completes_to_done_with_result(self):
        with tempfile.TemporaryDirectory() as d:
            payload = _payload(d)
            ops = _ops(_ImportFake(result={"ok": True, "app": "kiwix", "localRef": "r"}), d)
            status, body = ops.start("kiwix", {"identity": {"localRef": "a.zim"}, "bytes": 10}, payload)
            self.assertEqual(status, 200)
            self.assertEqual(body["state"], "running")
            self.assertIn("opId", body)
            done = _wait_state(ops, body["opId"], "done")
            self.assertEqual(done["state"], "done")
            self.assertEqual(done["result"]["localRef"], "r")
            self.assertEqual(done["movedBytes"], 10)
            self.assertEqual(done["totalBytes"], 10)

    def test_start_unknown_app_404(self):
        with tempfile.TemporaryDirectory() as d:
            ops = _ops(_ImportFake(), d)
            status, _ = ops.start("nope", {"identity": {"localRef": "a"}}, os.path.join(d, "p"))
            self.assertEqual(status, 404)

    def test_start_bad_body_400(self):
        with tempfile.TemporaryDirectory() as d:
            ops = _ops(_ImportFake(), d)
            self.assertEqual(ops.start("kiwix", "not-a-dict", os.path.join(d, "p"))[0], 400)
            self.assertEqual(ops.start("kiwix", {"x": 1}, "")[0], 400)

    def test_start_rejects_payload_outside_area(self):
        with tempfile.TemporaryDirectory() as d:
            ops = _ops(_ImportFake(), d)
            status, _ = ops.start("kiwix", {"identity": {"localRef": "a"}}, "/etc/passwd")
            self.assertEqual(status, 400)


class StatusTest(unittest.TestCase):
    def test_unknown_op_404(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(_ops(_ImportFake(), d).status("deadbeef")[0], 404)

    def test_failing_adapter_becomes_error_without_code(self):
        with tempfile.TemporaryDirectory() as d:
            payload = _payload(d)
            ops = _ops(_ImportFake(result={"ok": False, "reason": "bad archive"}), d)
            _, body = ops.start("kiwix", {"identity": {"localRef": "a.zim"}, "bytes": 5}, payload)
            err = _wait_state(ops, body["opId"], "error")
            self.assertEqual(err["error"], "bad archive")
            self.assertNotIn("code", err)

    def test_no_space_result_carries_structural_code(self):
        with tempfile.TemporaryDirectory() as d:
            payload = _payload(d)
            ops = _ops(_ImportFake(result={"ok": False, "code": "no_space", "reason": "full"}), d)
            _, body = ops.start("kiwix", {"identity": {"localRef": "a.zim"}, "bytes": 5}, payload)
            err = _wait_state(ops, body["opId"], "error")
            self.assertEqual(err["code"], "no_space")

    def test_progress_is_reported_and_clamped_to_total(self):
        with tempfile.TemporaryDirectory() as d:
            payload = _payload(d)
            gate = threading.Event()
            # The watchdog reports cumulative syscall bytes that exceed the payload; the op clamps
            # moved_bytes to the known total (10), so the bar never passes 100%.
            ops = _ops(_ImportFake(gate=gate, progress=[100]), d)
            _, body = ops.start("kiwix", {"identity": {"localRef": "a.zim"}, "bytes": 10}, payload)
            op_id = body["opId"]
            end = time.time() + 5
            moved = 0
            while time.time() < end:
                _, st = ops.status(op_id)
                if st["state"] == "running" and st["movedBytes"] > 0:
                    moved = st["movedBytes"]
                    break
                time.sleep(0.01)
            self.assertEqual(moved, 10)
            gate.set()
            _wait_state(ops, op_id, "done")


class CancelAndSweepTest(unittest.TestCase):
    def test_delete_drops_op_and_cancels_the_worker_idempotent(self):
        with tempfile.TemporaryDirectory() as d:
            payload = _payload(d)
            gate = threading.Event()
            fake = _ImportFake(gate=gate)
            ops = _ops(fake, d)
            _, body = ops.start("kiwix", {"identity": {"localRef": "a.zim"}, "bytes": 1}, payload)
            op_id = body["opId"]
            self.assertEqual(ops.delete(op_id), (200, {"ok": True}))
            self.assertEqual(ops.status(op_id)[0], 404)
            gate.set()  # release the worker; it sees should_cancel True (the op is gone)
            end = time.time() + 2
            while time.time() < end and not fake.saw_cancel:
                time.sleep(0.01)
            self.assertTrue(fake.saw_cancel)
            # idempotent: a repeat and an unknown op are both 200 ok.
            self.assertEqual(ops.delete(op_id), (200, {"ok": True}))
            self.assertEqual(ops.delete("never-existed"), (200, {"ok": True}))

    def test_ttl_sweep_reclaims_an_abandoned_op(self):
        with tempfile.TemporaryDirectory() as d:
            payload = _payload(d)
            ops = _ops(_ImportFake(), d, ttl=60)
            _, body = ops.start("kiwix", {"identity": {"localRef": "a.zim"}, "bytes": 1}, payload)
            op_id = body["opId"]
            _wait_state(ops, op_id, "done")
            ops._ops[op_id].last_seen -= 120
            ops._sweep_expired()
            self.assertNotIn(op_id, ops._ops)

    def test_startup_sweep_reclaims_import_temps_only(self):
        with tempfile.TemporaryDirectory() as d:
            mine = os.path.join(d, "cximport-abc")
            export = os.path.join(d, "cxexport-xyz")   # an export temp: import's sweep leaves it
            unrelated = os.path.join(d, "kolibri")
            for p in (mine, export, unrelated):
                os.makedirs(p)
            ops = _ops(_ImportFake(), d)
            ops.sweep_startup()
            self.assertFalse(os.path.exists(mine))
            self.assertTrue(os.path.exists(export))
            self.assertTrue(os.path.exists(unrelated))

    def test_startup_sweep_tolerates_missing_dir(self):
        ops = _ops(_ImportFake(), "/no/such/import/dir")
        ops.sweep_startup()  # must not raise


if __name__ == "__main__":
    unittest.main()
