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
import tempfile
from typing import List, Optional

from ..export import tar_stream
from ..model import AUTHORITATIVE, Dedup, Identity, ManifestItem
from ..ports import ContentAdapter, ExportStream

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

    def __init__(self, db_path: str, kolibri_bin: str = "/usr/bin/kolibri"):
        self._db = db_path
        self._bin = kolibri_bin
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

    def open_export(self, local_ref: str) -> Optional[ExportStream]:
        # Delegate to Kolibri's own export: it stages the channel DB + the channel's
        # content-addressed files into a temp tree, which we then tar-stream. Read-only on
        # the source (export copies out). The temp tree is removed after streaming.
        if not _CHANNEL_ID_RE.match(local_ref or ""):
            return None
        if not self._query(local_ref):  # not an installed channel
            return None
        dest = tempfile.mkdtemp(prefix="cxkolibri-")
        try:
            staged = self._stage_export(local_ref, dest)
        except OSError:
            staged = False
        if not staged:
            shutil.rmtree(dest, ignore_errors=True)
            return None
        return ExportStream(
            filename=f"{local_ref}.tar",
            content_type="application/x-tar",
            chunks=tar_stream(dest, arcname=local_ref),
            on_close=lambda: shutil.rmtree(dest, ignore_errors=True),
        )

    def _stage_export(self, channel_id: str, dest: str) -> bool:
        """Run the Kolibri export CLI into dest. A seam, so a test can stage a fake tree
        without a live Kolibri. Returns True on success."""
        env = dict(os.environ, KOLIBRI_HOME=self._home)
        for argv in export_commands(channel_id, dest, self._bin):
            result = subprocess.run(
                argv, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False
            )
            if result.returncode != 0:
                return False
        return True
