"""rsync transport for Share (K2GO-577).

Share moves content with rsync, not with a staged HTTP stream: rsync writes each file once into its
final place, keeps the partial bytes when the link drops, and resumes from them. The core owns the
whole mechanism, so IIAB and K2Go share it.

Sender: one rsync daemon per box, alive only while a share session is open. Each shared item is a
read-only module whose filter admits only that item's files. The module password is the session
token. The daemon re-reads its config on every connection, so a session that opens or closes only
rewrites the config; the last close stops the daemon.

Receiver: one rsync client per item. A single large file (a ZIM) is pulled with --inplace
--append-verify into a partial path on the content volume, then renamed into place: a kill of the
client or of the sender keeps the bytes, and the next pull appends to them and verifies the whole
file. The rename is the commit, so the app never sees a partial file under its final name.

Lifecycle:
  daemon: started by the first open session, stopped by the last close, the safety cap, or the
    startup sweep (a pid file names the process group a dead core left behind).
  partial file: made by the pull, renamed into place on success, kept on a drop or a cancel so a
    retry resumes, removed by the startup sweep after PARTIAL_MAX_AGE_SECONDS.
"""

from __future__ import annotations

import logging
import os
import re
import shutil
import signal
import subprocess
import threading
import time
from dataclasses import dataclass
from typing import Callable, List, Optional

from .watchdog import run_supervised

_LOG = logging.getLogger("conex-core")
_SIGKILL = getattr(signal, "SIGKILL", 9)

#: The rsync user name. The secret (the session token) is the auth; the name is fixed.
SHARE_USER = "share"
#: Module names are "<sessionId>-<itemRef>": hex, digits and a hyphen.
MODULE_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9-]{0,80}$")
#: An IPv4 address or a host name. No IPv6: the hotspot and LAN paths are IPv4.
HOST_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$")
#: rsync ends a transfer with no data for this long (a dead peer), keeping the partial.
IO_TIMEOUT_SECONDS = 60
CONNECT_TIMEOUT_SECONDS = 20
#: A partial that no retry picked up for this long is removed by the startup sweep.
PARTIAL_MAX_AGE_SECONDS = 48 * 3600


@dataclass(frozen=True)
class Module:
    """One shared item as a daemon module: the root dir and the files under it it may serve."""
    name: str
    root: str
    files: List[str]
    secret: str


def _clean(value: str) -> bool:
    """True when a value is safe to write into rsyncd.conf or a filter file: no control chars
    (a newline would start a new directive) and no ']' (it would close a section header)."""
    return bool(value) and "]" not in value and not any(ord(c) < 32 or ord(c) == 127 for c in value)


def render_filter(files: List[str]) -> str:
    """Filter rules that admit only ``files`` (relative to the module root) and their parent
    dirs, then exclude everything else. Anchored with '/', so a name matches only at its path."""
    rules: List[str] = []
    seen = set()
    for rel in files:
        parts = rel.strip("/").split("/")
        for depth in range(1, len(parts)):
            parent = "/" + "/".join(parts[:depth]) + "/"
            if parent not in seen:
                seen.add(parent)
                rules.append("+ " + parent)
        rules.append("+ /" + "/".join(parts))
    rules.append("- *")
    return "\n".join(rules) + "\n"


def render_config(modules: List[Module], run_dir: str, uid: int, gid: int) -> str:
    """The daemon config. log file /dev/null: the daemon logs no client address (K2GO-559).
    uid/gid keep the core's own identity, so a root daemon does not drop to nobody and lose
    read access to the content."""
    lines = [
        "use chroot = no",
        "reverse lookup = no",
        "log file = /dev/null",
        "transfer logging = no",
        f"timeout = {IO_TIMEOUT_SECONDS * 2}",
        f"uid = {uid}",
        f"gid = {gid}",
    ]
    for m in modules:
        lines += [
            f"[{m.name}]",
            f"  path = {m.root}",
            "  read only = yes",
            "  list = no",
            f"  auth users = {SHARE_USER}",
            f"  secrets file = {os.path.join(run_dir, m.name + '.secrets')}",
            "  strict modes = no",
            f"  filter = merge {os.path.join(run_dir, m.name + '.filter')}",
        ]
    return "\n".join(lines) + "\n"


def _getid(name: str) -> int:
    """The process uid / gid; 0 on a host without them (only the tests run there)."""
    return getattr(os, name, lambda: 0)()


def _write(path: str, text: str, mode: int = 0o600) -> None:
    """Write atomically, so a connection that reads the config mid-rewrite sees old or new."""
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    with os.fdopen(fd, "w") as f:
        f.write(text)
    os.replace(tmp, path)


class RsyncDaemon:
    """The sender's rsync daemon. Thread-safe; one instance lives for the server's lifetime."""

    def __init__(self, run_dir: str, port: int, rsync_bin: str = "/usr/bin/rsync",
                 address: str = "0.0.0.0", popen=subprocess.Popen, killpg=None):
        self._run_dir = run_dir
        self.port = port
        self._bin = rsync_bin
        self._address = address
        self._popen = popen
        # Seams: tests never start or signal a real process. getattr keeps this importable on a
        # host without process groups, so the tests run anywhere.
        self._killpg = killpg or getattr(os, "killpg", None)
        self._lock = threading.Lock()
        self._proc = None

    @property
    def _conf(self) -> str:
        return os.path.join(self._run_dir, "rsyncd.conf")

    @property
    def _pidfile(self) -> str:
        return os.path.join(self._run_dir, "daemon.pid")

    def available(self) -> bool:
        return os.path.isfile(self._bin)

    def running(self) -> bool:
        with self._lock:
            return self._proc is not None and self._proc.poll() is None

    def set_modules(self, modules: List[Module]) -> bool:
        """Serve exactly ``modules``. Starts the daemon for the first module and stops it when
        none is left. Returns True when the daemon serves them. A module with an unsafe value is
        skipped (logged), never written."""
        safe = [m for m in modules if MODULE_RE.match(m.name) and _clean(m.root)
                and _clean(m.secret) and m.files and all(_clean(f) for f in m.files)]
        if len(safe) != len(modules):
            _LOG.warning("share rsync: skipped %d unsafe module(s)", len(modules) - len(safe))
        with self._lock:
            if not safe:
                self._stop_locked()
                return False
            if not self.available():
                return False
            os.makedirs(self._run_dir, exist_ok=True)
            names = set()
            for m in safe:
                names.add(m.name)
                _write(os.path.join(self._run_dir, m.name + ".secrets"), f"{SHARE_USER}:{m.secret}\n")
                _write(os.path.join(self._run_dir, m.name + ".filter"), render_filter(m.files))
            _write(self._conf, render_config(safe, self._run_dir, _getid("getuid"), _getid("getgid")))
            self._remove_stale(names)
            if self._proc is None or self._proc.poll() is not None:
                self._start_locked()
            return self._proc is not None and self._proc.poll() is None

    def stop(self) -> None:
        with self._lock:
            self._stop_locked()

    def sweep_startup(self) -> None:
        """Stop a daemon a dead core left behind and remove its config and secrets."""
        try:
            with open(self._pidfile) as f:
                pgid = int(f.read().strip())
            self._killpg(pgid, _SIGKILL)
        except (OSError, ValueError):
            pass
        shutil.rmtree(self._run_dir, ignore_errors=True)

    def _start_locked(self) -> None:
        argv = [self._bin, "--daemon", "--no-detach", f"--config={self._conf}",
                f"--address={self._address}", f"--port={self.port}"]
        try:
            # Own process group: the per-connection children are in it, so a stop ends a live
            # transfer too (killing only the parent leaves the children serving).
            self._proc = self._popen(argv, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                     start_new_session=True)
        except OSError as exc:
            _LOG.warning("share rsync: daemon did not start: %s", exc)
            self._proc = None
            return
        _write(self._pidfile, str(self._proc.pid))
        time.sleep(0.3)  # a busy port makes the daemon exit at once; report that as not serving
        if self._proc.poll() is not None:
            _LOG.warning("share rsync: daemon exited at start (port %d busy?)", self.port)
            self._proc = None

    def _stop_locked(self) -> None:
        proc, self._proc = self._proc, None
        if proc is not None and proc.poll() is None:
            for sig in (signal.SIGTERM, _SIGKILL):
                try:
                    self._killpg(proc.pid, sig)
                    proc.wait(timeout=3)
                    break
                except (OSError, subprocess.TimeoutExpired):
                    continue
        shutil.rmtree(self._run_dir, ignore_errors=True)

    def _remove_stale(self, keep: set) -> None:
        for name in os.listdir(self._run_dir):
            stem, ext = os.path.splitext(name)
            if ext in (".secrets", ".filter") and stem not in keep:
                try:
                    os.remove(os.path.join(self._run_dir, name))
                except OSError:
                    pass


def valid_source(source) -> Optional[str]:
    """None when a pull source {host, port, module, token} is safe to use, else the reason."""
    if not isinstance(source, dict):
        return "source must be an object"
    host, port, module, token = (source.get(k) for k in ("host", "port", "module", "token"))
    if not isinstance(host, str) or not HOST_RE.match(host):
        return "invalid source host"
    if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
        return "invalid source port"
    if not isinstance(module, str) or not MODULE_RE.match(module):
        return "invalid source module"
    if not isinstance(token, str) or not _clean(token):
        return "invalid source token"
    return None


class RsyncPuller:
    """The receiver's rsync client. ``runner`` is the liveness supervisor (a seam for tests)."""

    def __init__(self, rsync_bin: str = "/usr/bin/rsync", runner=run_supervised):
        self._bin = rsync_bin
        self._runner = runner

    def available(self) -> bool:
        return os.path.isfile(self._bin)

    def pull_file(self, source: dict, remote_path: str, dest_path: str,
                  should_cancel: Optional[Callable[[], bool]] = None,
                  on_progress: Optional[Callable[[int], None]] = None):
        """Pull one file of a module into ``dest_path``, resuming from its bytes. Returns the
        supervisor result: ok, cancelled (SIGTERM, partial kept), or the failure."""
        url = (f"rsync://{SHARE_USER}@{source['host']}:{source['port']}"
               f"/{source['module']}/{remote_path}")
        argv = [self._bin, "-t", "--inplace", "--append-verify",
                f"--timeout={IO_TIMEOUT_SECONDS}", f"--contimeout={CONNECT_TIMEOUT_SECONDS}",
                url, dest_path]
        # The password goes in the environment, never argv (argv is visible to every process).
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "RSYNC_PASSWORD": source["token"]}

        def on_disk() -> int:
            try:
                return os.path.getsize(dest_path)
            except OSError:
                return 0

        def progress(_activity: int) -> None:
            if on_progress is not None:
                on_progress(on_disk())

        # Liveness is the bytes on disk: in a pull the parent rsync is the generator, which moves
        # almost nothing while the receiver child writes, so its own I/O would look stalled.
        return self._runner(argv, env, dest_dir=os.path.dirname(dest_path),
                            should_cancel=should_cancel, on_progress=progress, log=_LOG,
                            activity_fn=on_disk, die_with_parent=True)

    def pull_tree(self, source: dict, dest_dir: str, work_dir: str, filters=(),
                  ignore_existing: bool = True,
                  should_cancel: Optional[Callable[[], bool]] = None,
                  on_progress: Optional[Callable[[int], None]] = None):
        """Pull files of a module into ``dest_dir``, from the MODULE ROOT, picking them with the
        client ``filters`` (rsync matches the daemon filter against paths relative to the
        transfer root, so a subdir pull would miss the module-anchored rules). With
        ``ignore_existing`` a file that already exists is skipped: for content-addressed files
        (Kolibri, name = md5) it is the same file. The in-flight temp file and a kept partial
        live in ``work_dir``, never under a final name in ``dest_dir``. Never deletes in
        ``dest_dir`` and never touches its dir times (other content shares it). Progress is the
        sum of the files completed, read from the client log (cumulative over retries)."""
        kept, tmp = os.path.join(work_dir, "kept"), os.path.join(work_dir, "tmp")
        log = os.path.join(work_dir, "transfer.log")
        for d in (kept, tmp, dest_dir):
            os.makedirs(d, exist_ok=True)
        url = f"rsync://{SHARE_USER}@{source['host']}:{source['port']}/{source['module']}/"
        argv = [self._bin, "-r", "-t", "--omit-dir-times", f"--partial-dir={kept}",
                f"--temp-dir={tmp}", f"--log-file={log}", "--log-file-format=%o %l",
                f"--timeout={IO_TIMEOUT_SECONDS}", f"--contimeout={CONNECT_TIMEOUT_SECONDS}"]
        if ignore_existing:
            argv.append("--ignore-existing")
        argv += [f"--filter={rule}" for rule in filters]
        argv += [url, dest_dir.rstrip("/") + "/"]
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "RSYNC_PASSWORD": source["token"]}
        tally = _LogTally(log)

        def on_disk() -> int:
            # Completed files plus the file in flight (its temp lives in tmp): the liveness
            # signal, since the parent rsync (the generator) moves almost nothing itself.
            return tally.read() + _dir_bytes(tmp)

        def progress(_activity: int) -> None:
            if on_progress is not None:
                on_progress(tally.read())

        return self._runner(argv, env, dest_dir=None, should_cancel=should_cancel,
                            on_progress=progress, log=_LOG, activity_fn=on_disk)


def _dir_bytes(path: str) -> int:
    """Bytes of the files directly in ``path`` (the rsync temp dir holds one in-flight file)."""
    total = 0
    try:
        with os.scandir(path) as it:
            for e in it:
                if e.is_file(follow_symlinks=False):
                    total += e.stat(follow_symlinks=False).st_size
    except OSError:
        pass
    return total


class _LogTally:
    """Sums the "recv <length>" lines the rsync client appends to its log, reading only the
    new bytes on each call (the log can hold thousands of lines)."""

    _LINE = re.compile(r"\brecv (\d+)\s*$")

    def __init__(self, path: str):
        self._path = path
        self._offset = 0
        self._total = 0

    def read(self) -> int:
        try:
            with open(self._path, "rb") as f:
                f.seek(self._offset)
                chunk = f.read()
        except OSError:
            return self._total
        end = chunk.rfind(b"\n") + 1  # leave a half-written last line for the next read
        self._offset += end
        for line in chunk[:end].decode("utf-8", "replace").splitlines():
            m = self._LINE.search(line)
            if m:
                self._total += int(m.group(1))
        return self._total


def sweep_partials(partial_root: str, max_age: float = PARTIAL_MAX_AGE_SECONDS,
                   now: Optional[float] = None) -> None:
    """Remove partial files that no retry picked up within ``max_age``."""
    now = time.time() if now is None else now
    for dirpath, _dirs, files in os.walk(partial_root):
        for name in files:
            path = os.path.join(dirpath, name)
            try:
                if now - os.path.getmtime(path) > max_age:
                    os.remove(path)
            except OSError:
                pass
