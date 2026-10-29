"""Kolibri adapter (read side).

Kolibri identity is authoritative and upstream: channel_id (32 hex) plus an integer
version come from Kolibri Studio, so we reuse them, never recompute. The box keeps them
in the Kolibri content database (db.sqlite3), read in readonly: the same source and the
same aggregation dash-node uses (sockets/kolibri.query.ts), so there is one source of truth.

bytes is the on-disk available size of the channel (what the box actually holds), summed
over its DISTINCT local files. The DISTINCT matters: one local file can hang off several
content nodes, and a flat join would count its bytes once per node.
"""

from __future__ import annotations

import os
import re
import shutil
import sqlite3
import subprocess
import tarfile
import tempfile
from typing import List, Optional

from ..export import free_bytes, tar_stream
from ..importing import safe_extract_tar, single_child_dir
from ..model import (
    ABSENT,
    AUTHORITATIVE,
    NEWER_HERE,
    OLDER_HERE,
    SAME_VERSION,
    Dedup,
    Identity,
    ManifestItem,
)
from ..ports import ContentAdapter, ExportPlan, ExportStream

_CHANNEL_ID_RE = re.compile(r"^[0-9a-fA-F]{32}$")


def export_commands(channel_id: str, dest: str, kolibri_bin: str) -> List[List[str]]:
    """The two Kolibri management commands that stage a channel export into dest: the
    channel DB, then its content files. Pure (builds argv only). Delegating to Kolibri is
    the one owner of "how a channel is exported"; the content store is content-addressed,
    so import dedups shared files across channels by itself."""
    base = [kolibri_bin, "manage"]
    return [
        base + ["exportchannel", channel_id, dest],
        base + ["exportcontent", channel_id, dest],
    ]


def import_commands(channel_id: str, source: str, kolibri_bin: str) -> List[List[str]]:
    """The two Kolibri disk-import commands: register the channel DB, then copy its content
    from the exported folder. Pure (argv only). The content store is content-addressed, so
    files shared with channels already present are deduped by Kolibri itself."""
    base = [kolibri_bin, "manage"]
    return [
        base + ["importchannel", "disk", channel_id, source],
        base + ["importcontent", "disk", channel_id, source],
    ]


def delete_command(channel_id: str, kolibri_bin: str) -> List[str]:
    """The Kolibri management command that deletes a channel and its unused content files.
    Pure (builds argv only). This is Kolibri's OWN delete: it does the shared-file refcounting,
    so it frees only files no other channel still uses (a direct rm would corrupt other
    channels, since the content store is content-addressed and shared).

    dash-node runs the equivalent delete as the async Kolibri REST TASK
    (kolibri.core.content.tasks.deletechannel) because it is a long-lived Node service that
    must not block. conex-core reaches Kolibri through the CLI (as its export / import do), where
    the command is synchronous: we wait for it inherently and then report the reclaimed bytes.
    """
    return [kolibri_bin, "manage", "deletechannel", channel_id]


def _run_cancellable(argv: List[str], env: dict, should_cancel=None) -> bool:
    """Run one child process to completion, polling ``should_cancel`` about once a second.
    When it returns True the process is terminated (then killed if it does not exit) and this
    returns False. Otherwise returns True on exit code 0. This is how an aborted export stops
    the long-running Kolibri CLI instead of leaving it writing into a removed dir."""
    proc = subprocess.Popen(argv, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    while True:
        try:
            proc.wait(timeout=1.0)
            return proc.returncode == 0
        except subprocess.TimeoutExpired:
            if should_cancel and should_cancel():
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()
                return False


def _to_int(*values) -> Optional[int]:
    """First value that parses as an int, else None. The version may arrive as an int or a
    string over JSON, and the build mirrors it."""
    for value in values:
        try:
            return int(value)
        except (TypeError, ValueError):
            continue
    return None



# Available bytes per installed channel. {where} scopes it to one channel for get().
_CHANNELS_SQL = """
WITH channel_files AS (
    SELECT DISTINCT cn.channel_id AS channel_id, f.local_file_id AS local_file_id
    FROM content_file f
    JOIN content_contentnode cn ON cn.id = f.contentnode_id
)
SELECT cm.id, cm.name, cm.version,
       COALESCE(SUM(CASE WHEN lf.available = 1 THEN lf.file_size ELSE 0 END), 0) AS bytes_available
FROM content_channelmetadata cm
LEFT JOIN channel_files p      ON p.channel_id = cm.id
LEFT JOIN content_localfile lf ON lf.id = p.local_file_id
{where}
GROUP BY cm.id, cm.name, cm.version
ORDER BY cm.name
"""


def build_channel_item(channel_id: str, name: Optional[str], version, size_bytes: int) -> ManifestItem:
    """Build the manifest item for one installed channel. Pure."""
    v = int(version) if version is not None else 0
    dedup = Dedup(
        line=channel_id,
        build=str(v),  # the integer version is both the exact build and the version axis
        version=v,
        id_kind=AUTHORITATIVE,
    )
    return ManifestItem(
        app="kolibri",
        kind="channel",
        title=name or channel_id,
        bytes=size_bytes,
        identity=Identity(local_ref=channel_id, dedup=dedup),
    )


class KolibriAdapter(ContentAdapter):
    app = "kolibri"

    def __init__(
        self, db_path: str, kolibri_bin: str = "/usr/bin/kolibri",
        staging_dir: str = "/library/.conex-staging",
    ):
        self._db = db_path
        self._bin = kolibri_bin
        self._staging_dir = staging_dir
        # KOLIBRI_HOME is the directory that holds db.sqlite3; the export CLI reads it.
        self._home = os.path.dirname(db_path)

    def is_installed(self) -> bool:
        return os.path.exists(self._db)

    def _query(self, only_id: Optional[str]) -> List[ManifestItem]:
        if not os.path.exists(self._db):
            return []
        sql = _CHANNELS_SQL.format(where="WHERE cm.id = ?" if only_id else "")
        params = (only_id,) if only_id else ()
        try:
            con = sqlite3.connect(f"file:{self._db}?mode=ro", uri=True)
        except sqlite3.Error:
            return []
        try:
            rows = con.execute(sql, params).fetchall()
        except sqlite3.Error:
            # A locked DB (Kolibri writing during an import) or a schema change: report
            # nothing rather than crash. Kolibri remains the authority.
            return []
        finally:
            con.close()
        return [build_channel_item(r[0], r[1], r[2], r[3]) for r in rows]

    def list(self) -> List[ManifestItem]:
        return self._query(None)

    def get(self, local_ref: str) -> Optional[ManifestItem]:
        # localRef is a channel_id used only as a bound SQL parameter (never a path), so a
        # single scoped query is enough; no path-containment guard applies here.
        if not local_ref:
            return None
        items = self._query(local_ref)
        return items[0] if items else None

    def prepare_export(self, local_ref: str) -> Optional[ExportPlan]:
        # A channel must be staged (the Kolibri export CLI writes the DB + content tree)
        # before it can tar-stream, so stages=True. The op layer preflights the staging disk,
        # creates the temp and runs the staging thread; this only validates and reports.
        if not _CHANNEL_ID_RE.match(local_ref or ""):
            return None
        channel = self._query(local_ref)
        if not channel:  # not an installed channel
            return None
        return ExportPlan(
            stages=True,
            total_bytes=channel[0].bytes,
            filename=f"{local_ref}.tar",
            content_type="application/x-tar",
        )

    def stage_export(self, local_ref: str, dest: str, should_cancel=None) -> bool:
        # Stage the channel into dest (the op layer's temp). Delegate to Kolibri's own export:
        # it writes the channel DB + the content-addressed files that import dedups for free.
        # Read-only on the source (export copies out). ``should_cancel`` lets the op layer stop
        # the export CLI on abort instead of leaving it running into a deleted dir.
        if not _CHANNEL_ID_RE.match(local_ref or ""):
            return False
        try:
            return self._stage_export(local_ref, dest, should_cancel)
        except OSError:
            return False

    def open_fetch(self, local_ref: str, staged_dir: Optional[str] = None) -> Optional[ExportStream]:
        # Tar-stream the staged tree. The op layer sets on_close (drop the op + remove temp).
        if not staged_dir or not os.path.isdir(staged_dir):
            return None
        return ExportStream(
            filename=f"{local_ref}.tar",
            content_type="application/x-tar",
            chunks=tar_stream(staged_dir, arcname=local_ref),
        )

    def _stage_export(self, channel_id: str, dest: str, should_cancel=None) -> bool:
        """Run the Kolibri export CLI into dest. A seam, so a test can stage a fake tree
        without a live Kolibri. Each command runs as a child process polled against
        ``should_cancel``, so an abort terminates the export instead of letting it finish into a
        deleted dir. Returns True on success, False on CLI failure or cancellation."""
        env = dict(os.environ, KOLIBRI_HOME=self._home)
        for argv in export_commands(channel_id, dest, self._bin):
            if not _run_cancellable(argv, env, should_cancel):
                return False
        return True

    def check(self, dedup: dict) -> str:
        # The line is the channel_id; the integer version is the axis. Authoritative.
        line = dedup.get("line")
        incoming = _to_int(dedup.get("version"), dedup.get("build"))
        local = [it.identity.dedup for it in self.list() if it.identity.dedup.line == line]
        if not local:
            return ABSENT
        local_version = max(d.version for d in local)
        if incoming is None or local_version == incoming:
            return SAME_VERSION
        return OLDER_HERE if local_version < incoming else NEWER_HERE

    def import_item(self, entry: dict, payload_path: str) -> dict:
        # The payload is the export tar. Extract it safely, then delegate the disk import to
        # Kolibri. Idempotent: Kolibri's import re-registers and dedups, never double-counts.
        channel_id = (entry.get("identity") or {}).get("localRef", "")
        if not _CHANNEL_ID_RE.match(channel_id or ""):
            return {"ok": False, "reason": "invalid channel id"}
        if not os.path.isfile(payload_path):
            return {"ok": False, "reason": "payload not found"}
        os.makedirs(self._staging_dir, exist_ok=True)
        work = tempfile.mkdtemp(prefix="cximport-", dir=self._staging_dir)
        try:
            try:
                safe_extract_tar(payload_path, work)
            except (ValueError, tarfile.TarError) as exc:
                return {"ok": False, "reason": f"bad archive: {exc}"}
            source = single_child_dir(work) or work
            if not self._stage_import(channel_id, source):
                return {"ok": False, "reason": "kolibri import failed"}
            return {"ok": True, "app": "kolibri", "localRef": channel_id}
        finally:
            shutil.rmtree(work, ignore_errors=True)

    def _stage_import(self, channel_id: str, source: str) -> bool:
        """Run the Kolibri disk-import CLI from source. A seam, like _stage_export."""
        env = dict(os.environ, KOLIBRI_HOME=self._home)
        for argv in import_commands(channel_id, source, self._bin):
            result = subprocess.run(
                argv, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False
            )
            if result.returncode != 0:
                return False
        return True

    def delete(self, local_ref: str) -> dict:
        # Delegate to Kolibri's own delete (it refcounts shared files). Measure the reclaim as a
        # free-space delta around the CLI call: the filesystem is the one source for bytes
        # actually freed, so we never re-derive the refcount here (ADR-5343 guard).
        # freedBytes is APPROXIMATE: the delta reads the WHOLE content volume, and the front
        # serializes only conex-core's OWN deletes, so a concurrent box process (a download, log
        # writes) can skew it. totalBytes / freeBytes from GET /v1/storage stay authoritative.
        # NOT yet confirmed on-device: that `kolibri manage deletechannel` is synchronous and
        # removes the content FILES (not just metadata). If it is async, freedBytes reads ~0 and
        # the channel may still be deleting after this returns: verify on the box, and if needed
        # swap _delete_channel to also run `deletecontent` or to the REST task dash-node uses.
        if not _CHANNEL_ID_RE.match(local_ref or ""):
            return {"ok": False, "reason": "invalid channel id"}
        if not self._query(local_ref):
            return {"ok": False, "reason": "item not found"}
        before = free_bytes(self._home)
        if not self._delete_channel(local_ref):
            return {"ok": False, "reason": "kolibri delete failed"}
        after = free_bytes(self._home)
        freed = max(0, after - before) if before is not None and after is not None else None
        return {"ok": True, "app": "kolibri", "localRef": local_ref, "freedBytes": freed}

    def _delete_channel(self, channel_id: str) -> bool:
        """Run the Kolibri delete CLI (synchronous). A seam, like _stage_export / _stage_import,
        so a test need not run a live Kolibri. Returns True on success."""
        env = dict(os.environ, KOLIBRI_HOME=self._home)
        result = subprocess.run(
            delete_command(channel_id, self._bin),
            env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
        )
        return result.returncode == 0
