import sys
import threading
import unittest

from conex_core.watchdog import (
    CANCELLED,
    FAILED,
    KILLED,
    OK,
    STALLED,
    LivenessMonitor,
    classify_exit,
    run_supervised,
)


class LivenessMonitorTest(unittest.TestCase):
    def _monitor(self):
        # Small floor so a test trips the stall in a few simulated seconds.
        return LivenessMonitor(floor_seconds=10.0, gap_multiplier=4.0, cpu_grace=3.0)

    def test_first_sample_seeds_and_is_alive(self):
        monitor = self._monitor()
        self.assertIsNone(monitor.update(0.0, 0, 0))

    def test_bytes_moving_is_always_alive(self):
        monitor = self._monitor()
        monitor.start(0.0, 0, 0)
        # Each sample moves bytes well past the floor; the child is never declared stalled.
        for t in range(1, 200, 20):
            self.assertIsNone(monitor.update(float(t), t * 1000, 0))

    def test_nothing_moving_stalls_after_the_floor_window(self):
        monitor = self._monitor()
        monitor.start(0.0, 0, 0)
        self.assertIsNone(monitor.update(5.0, 0, 0))     # idle 5 < floor 10
        reason = monitor.update(11.0, 0, 0)              # idle 11 > floor 10
        self.assertIsNotNone(reason)
        self.assertIn("no progress", reason)

    def test_cpu_only_extends_the_window(self):
        monitor = self._monitor()
        monitor.start(0.0, 0, 0)
        # CPU keeps advancing with no new bytes: tolerated up to cpu_grace * window (3 * 10 = 30 s).
        self.assertIsNone(monitor.update(11.0, 0, 5))    # past the floor, but CPU moved
        self.assertIsNone(monitor.update(25.0, 0, 9))    # still inside the CPU grace window
        reason = monitor.update(31.0, 0, 12)             # past 30 s of CPU-only
        self.assertIsNotNone(reason)
        self.assertIn("CPU-bound", reason)

    def test_window_is_relative_to_the_longest_gap(self):
        monitor = self._monitor()
        monitor.start(0.0, 0, 0)
        # A healthy 30 s gap between byte bursts raises the window to 4 * 30 = 120 s.
        self.assertIsNone(monitor.update(30.0, 1000, 0))
        self.assertIsNone(monitor.update(140.0, 1000, 0))   # idle 110 < 120: still alive
        reason = monitor.update(260.0, 1000, 0)             # idle 230 > 120: stalled
        self.assertIsNotNone(reason)


class ClassifyExitTest(unittest.TestCase):
    def test_zero_is_ok(self):
        result = classify_exit(0)
        self.assertEqual(result.state, OK)
        self.assertTrue(result.ok)

    def test_nonzero_is_failed_with_code(self):
        result = classify_exit(1)
        self.assertEqual(result.state, FAILED)
        self.assertIn("code 1", result.detail)

    def test_sigkill_is_named_as_oom(self):
        result = classify_exit(-9)
        self.assertEqual(result.state, KILLED)
        self.assertIn("out of memory", result.detail)

    def test_other_signal_is_named(self):
        result = classify_exit(-15)
        self.assertEqual(result.state, KILLED)
        self.assertIn("signal 15", result.detail)


class RunSupervisedTest(unittest.TestCase):
    """Drives a real short-lived child. The stall logic is covered purely by LivenessMonitorTest;
    here we only confirm the exit, cancel and progress wiring around a live process."""

    def _run(self, code, **kwargs):
        return run_supervised([sys.executable, "-c", code], None, poll_seconds=0.02, **kwargs)

    def test_clean_exit_is_ok(self):
        result = self._run("import sys; sys.exit(0)")
        self.assertEqual(result.state, OK)

    def test_nonzero_exit_is_failed(self):
        result = self._run("import sys; sys.exit(3)")
        self.assertEqual(result.state, FAILED)
        self.assertEqual(result.returncode, 3)

    def test_cancel_terminates_the_child(self):
        flag = threading.Event()

        def should_cancel():
            flag.set()        # ask to cancel on the first poll
            return True

        result = run_supervised(
            [sys.executable, "-c", "import time; time.sleep(30)"], None,
            poll_seconds=0.02, should_cancel=should_cancel)
        self.assertEqual(result.state, CANCELLED)
        self.assertTrue(flag.is_set())

    def test_on_progress_is_called(self):
        seen = []
        result = run_supervised(
            [sys.executable, "-c", "import time; time.sleep(0.2)"], None,
            poll_seconds=0.02, on_progress=seen.append)
        self.assertEqual(result.state, OK)
        self.assertTrue(seen)   # at least one progress sample while the child ran


if __name__ == "__main__":
    unittest.main()
