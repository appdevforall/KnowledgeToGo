"""Liveness watchdog for a supervised child process (K2GO-376).

A content import or export runs a Kolibri CLI child that can take minutes. A fixed timeout is
wrong for it: a large channel is slow but healthy, and a small one that hangs must still be
caught. So this module supervises the child by LIVENESS, not by a deadline:

  - Bytes are moving   -> the child is alive. There is no time limit.
  - CPU only, no bytes  -> it may be a legitimate database phase, or a loop. Allow a longer
    window before giving up.
  - Nothing moves       -> it is stalled. Terminate it and report.

The stall window is RELATIVE TO THE TASK, not a wall clock. It is max(floor, k * the longest gap
between byte-progress events seen so far in this run), so a task that naturally pauses between
bursts widens its own tolerance.

LivenessMonitor is pure: it takes samples and returns a verdict, so it is unit-tested without a
subprocess. run_supervised wires a real child, the /proc samplers, cancellation and exit
classification around it. The byte signal is /proc/<pid>/io (rchar+wchar), which is readable under
proot for a same-uid child; directory growth is the fallback when /proc is not readable.
"""

from __future__ import annotations

import os
import signal
import subprocess
import time
from dataclasses import dataclass
from typing import Callable, Optional

# The end states run_supervised reports.
OK = "ok"
CANCELLED = "cancelled"
STALLED = "stalled"
FAILED = "failed"
KILLED = "killed"


@dataclass
class SuperviseResult:
    state: str                 # one of OK / CANCELLED / STALLED / FAILED / KILLED
    returncode: Optional[int]
    detail: str

    @property
    def ok(self) -> bool:
        return self.state == OK


def dir_size(path: Optional[str]) -> int:
    """Sum of the file sizes under path, or 0 when it is missing or None. Shared with export_ops
    as the staging heartbeat, and used here as the byte-progress fallback when /proc/<pid>/io
    cannot be read."""
    if not path:
        return 0
    total = 0
    for dirpath, _dirnames, filenames in os.walk(path):
        for name in filenames:
            try:
                total += os.path.getsize(os.path.join(dirpath, name))
            except OSError:
                pass
    return total


def proc_io_bytes(pid: int) -> Optional[int]:
    """Bytes the process has moved through read()/write() syscalls (rchar+wchar from
    /proc/<pid>/io), or None when the file cannot be read. Verified readable under proot for a
    same-uid child. rchar+wchar is preferred over read_bytes/write_bytes: it counts a page-cached
    write too, so it reflects a copy in progress before the data reaches the disk."""
    try:
        total = 0
        with open(f"/proc/{pid}/io", "r", encoding="ascii", errors="replace") as handle:
            for line in handle:
                if line.startswith(("rchar:", "wchar:")):
                    total += int(line.split(":", 1)[1])
        return total
    except (OSError, ValueError):
        return None


def proc_cpu_ticks(pid: int) -> Optional[int]:
    """utime+stime in clock ticks from /proc/<pid>/stat, or None. Field 2 (comm) is in parentheses
    and may hold spaces or parentheses, so read the fields AFTER the last ')': utime and stime are
    then at offsets 11 and 12."""
    try:
        with open(f"/proc/{pid}/stat", "r", encoding="ascii", errors="replace") as handle:
            data = handle.read()
        fields = data.rpartition(")")[2].split()
        return int(fields[11]) + int(fields[12])
    except (OSError, ValueError, IndexError):
        return None


class LivenessMonitor:
    """Pure stall detector. Feed it (now, activity_bytes, cpu_ticks) samples; it reports whether
    the child still looks alive. Bytes moving resets the clock (no limit). CPU-only extends the
    window. Nothing moving trips the stall after a task-relative window."""

    def __init__(self, floor_seconds: float = 120.0, gap_multiplier: float = 4.0,
                 cpu_grace: float = 3.0):
        self._floor = floor_seconds
        self._gap_multiplier = gap_multiplier
        self._cpu_grace = cpu_grace
        self._last_activity = 0
        self._last_cpu: Optional[int] = None
        self._progress_at = 0.0
        self._longest_gap = 0.0
        self._started = False

    def start(self, now: float, activity: int, cpu: Optional[int]) -> None:
        self._last_activity = activity
        self._last_cpu = cpu
        self._progress_at = now
        self._longest_gap = 0.0
        self._started = True

    def update(self, now: float, activity: int, cpu: Optional[int]) -> Optional[str]:
        """Return None while the child looks alive, or a human-readable reason once it should be
        killed. The first call seeds the baselines and always returns None."""
        if not self._started:
            self.start(now, activity, cpu)
            return None
        if activity > self._last_activity:
            # Bytes moved: the child is alive. Record this gap so the window grows to fit the
            # task's own rhythm.
            self._longest_gap = max(self._longest_gap, now - self._progress_at)
            self._progress_at = now
            self._last_activity = activity
            self._last_cpu = cpu
            return None
        idle = now - self._progress_at
        window = max(self._floor, self._gap_multiplier * self._longest_gap)
        if cpu is not None and self._last_cpu is not None and cpu > self._last_cpu:
            # CPU advances with no new bytes: a database phase, or a loop. Give it a longer window;
            # if even that passes, treat it as a loop rather than progress.
            self._last_cpu = cpu
            if idle > self._cpu_grace * window:
                return f"no byte progress for {idle:.0f}s while CPU-bound (likely a loop)"
            return None
        if idle > window:
            return f"no progress for {idle:.0f}s (no bytes, no CPU)"
        return None


def _kill(proc: subprocess.Popen) -> None:
    """Terminate, then kill, giving the child a short grace to exit on SIGTERM."""
    try:
        proc.terminate()
    except OSError:
        pass
    try:
        proc.wait(timeout=5)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        proc.kill()
        proc.wait(timeout=5)
    except (OSError, subprocess.TimeoutExpired):
        pass


def classify_exit(returncode: int) -> SuperviseResult:
    """Map a child return code to a result. A negative code is death by signal; signal 9 (SIGKILL)
    on a child that was not cancelled is almost always the OOM killer, so it is named as such."""
    if returncode == 0:
        return SuperviseResult(OK, 0, "ok")
    if returncode < 0:
        sig = -returncode
        # SIGKILL is 9 on Linux (the only platform the core serves on); getattr keeps this file
        # importable on a host whose signal module has no SIGKILL, so the tests run anywhere.
        if sig == getattr(signal, "SIGKILL", 9):
            return SuperviseResult(KILLED, returncode, "killed (likely out of memory)")
        return SuperviseResult(KILLED, returncode, f"killed by signal {sig}")
    return SuperviseResult(FAILED, returncode, f"exited with code {returncode}")


def run_supervised(argv, env, *, dest_dir: Optional[str] = None,
                   should_cancel: Optional[Callable[[], bool]] = None,
                   poll_seconds: float = 1.0, floor_seconds: float = 120.0,
                   gap_multiplier: float = 4.0, cpu_grace: float = 3.0,
                   on_progress: Optional[Callable[[int], None]] = None,
                   log=None) -> SuperviseResult:
    """Run argv as a child and supervise it by liveness. The child's stdout/stderr go to DEVNULL
    (the caller owns user output). dest_dir is the directory whose growth is the byte-progress
    fallback when /proc/<pid>/io is unreadable. should_cancel is polled each interval; True
    terminates the child and returns CANCELLED. on_progress(activity_bytes) is called each poll so
    the caller can surface progress."""
    try:
        proc = subprocess.Popen(argv, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError as exc:
        # The child could not start at all (a missing binary, a bad interpreter). Own it here as a
        # clean failure rather than letting the raw OSError escape the supervisor.
        return SuperviseResult(FAILED, None, f"could not start: {exc}")
    pid = proc.pid
    # Decide the byte source once: the box either exposes /proc/<pid>/io or it does not.
    use_io = proc_io_bytes(pid) is not None

    def activity() -> int:
        if use_io:
            value = proc_io_bytes(pid)
            if value is not None:
                return value
        return dir_size(dest_dir)

    monitor = LivenessMonitor(floor_seconds, gap_multiplier, cpu_grace)
    monitor.start(time.monotonic(), activity(), proc_cpu_ticks(pid))
    try:
        while True:
            returncode = proc.poll()
            if returncode is not None:
                return classify_exit(returncode)
            if should_cancel is not None and should_cancel():
                _kill(proc)
                return SuperviseResult(CANCELLED, None, "cancelled")
            time.sleep(poll_seconds)
            current = activity()
            if on_progress is not None:
                try:
                    on_progress(current)
                except Exception:  # a progress sink must never break the supervision loop
                    pass
            reason = monitor.update(time.monotonic(), current, proc_cpu_ticks(pid))
            if reason is not None:
                _kill(proc)
                if log is not None:
                    log.warning("supervised child stalled: %s (%s)", reason, argv[0])
                return SuperviseResult(STALLED, None, reason)
    finally:
        # Never leak the child. A normal exit already reaped it, so _kill on a dead process is a
        # harmless no-op; this only matters if the loop left with the child still running.
        if proc.poll() is None:
            _kill(proc)
