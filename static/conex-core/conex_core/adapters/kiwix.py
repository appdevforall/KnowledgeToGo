"""Kiwix adapter (read side).

Kiwix identity is the cleanest: the ZIM header carries a UUID set by the upstream
scraper at build time and byte-copied on download, so the same file always has the
same UUID and we never generate one. We read it (we do not compute it), which is the
ADR "delegate to the app's own mechanism" rule.

Source of truth is the ZIM files on disk, not library.xml: the box may run no Kiwix
indexer, so library.xml is not guaranteed, but the .zim files always are. The line
(creator, lang, flavour) and the version (date) come from the Kiwix filename
convention <creator>_<lang>_<flavour>_<date>.zim, the same keys kiwix_catalog.csv uses.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import uuid
from typing import List, Optional, Tuple

from ..export import file_stream
from ..model import (
    ABSENT,
    AUTHORITATIVE,
    NEWER_HERE,
    OLDER_HERE,
    SAME_VERSION,
    SOFT,
    Dedup,
    Identity,
    ManifestItem,
)
from ..ports import ContentAdapter, ExportPlan, ExportStream

# openzim header: magic number is uint32 little-endian 0x044D495A at offset 0, and the
# 16-byte UUID is at offset 8. We only need the first 24 bytes.
ZIM_MAGIC = 0x044D495A
_ZIM_HEADER_MIN = 24

# A trailing YYYY-MM or YYYY-MM-DD is the version (date) axis in the filename.
_DATE_RE = re.compile(r"^\d{4}-\d{2}(-\d{2})?$")


def read_zim_uuid(header: bytes) -> Optional[str]:
    """Return the canonical UUID string from a ZIM header, or None when the bytes are
    not a valid ZIM header. Pure: takes the header bytes, reads nothing from disk."""
    if len(header) < _ZIM_HEADER_MIN:
        return None
    if int.from_bytes(header[0:4], "little") != ZIM_MAGIC:
        return None
    return str(uuid.UUID(bytes=header[8:24]))


def read_zim_uuid_from_file(path: str) -> Optional[str]:
    """Read just the header of a ZIM file and return its UUID, or None on any error."""
    try:
        with open(path, "rb") as f:
            return read_zim_uuid(f.read(_ZIM_HEADER_MIN))
    except OSError:
        return None


def is_safe_local_ref(local_ref: str) -> bool:
    """True when local_ref is a plain filename contained in one directory: no path
    separators, no parent/current reference, no null byte. So joining it onto the
    content dir cannot escape that dir (path-traversal guard). Pure and filesystem
    free, like the repo's ArchiveEntry.escapesRoot / ModuleName.isAllowed guards.
    Reused by get() now and by export() in the next slice."""
    if not local_ref or local_ref in (".", ".."):
        return False
    return not ("/" in local_ref or "\\" in local_ref or "\x00" in local_ref)


def parse_zim_filename(name: str) -> Tuple[str, str, str, str]:
    """Decompose a Kiwix filename into (creator, lang, flavour, date).

    The convention is <creator>_<lang>_<flavour>_<date>.zim, where the flavour may hold
    several underscore tokens (e.g. wikipedia_en_all_maxi_2023-10.zim). So the date is
    taken from the trailing dated token and the flavour is everything between the lang
    and the date. A filename that does not follow the convention still yields a stable
    creator (the stem) with empty lang / flavour / date."""
    stem = name[:-4] if name.lower().endswith(".zim") else name
    parts = stem.split("_")
    date = ""
    if parts and _DATE_RE.match(parts[-1]):
        date = parts[-1]
        parts = parts[:-1]
    creator = parts[0] if parts else stem
    lang = parts[1] if len(parts) > 1 else ""
    flavour = "_".join(parts[2:]) if len(parts) > 2 else ""
    return creator, lang, flavour, date


def build_item(name: str, size_bytes: int, zim_uuid: Optional[str]) -> ManifestItem:
    """Build the manifest item for one ZIM from its name, size and header UUID. Pure."""
    creator, lang, flavour, date = parse_zim_filename(name)
    dedup = Dedup(
        line={"creator": creator, "lang": lang, "flavour": flavour},
        build=zim_uuid,
        version=date,
        # The UUID is the authoritative cross-box key. When it could not be read
        # (short / non-ZIM / unreadable header) the item is degraded, so the dedup
        # is advisory only: never a confident authoritative match on a missing id.
        id_kind=AUTHORITATIVE if zim_uuid else SOFT,
    )
    return ManifestItem(
        app="kiwix",
        kind="zim",
        # The ZIM title lives in an internal metadata entry; the filename stem is a
        # cheap, always-available stand-in until metadata reading is added.
        title=name[:-4] if name.lower().endswith(".zim") else name,
        bytes=size_bytes,
        identity=Identity(local_ref=name, dedup=dedup),
        # Upstream hash (.meta4) is deleted after download, so none is retained on-device.
        integrity=None,
    )


class KiwixAdapter(ContentAdapter):
    app = "kiwix"
    id_kind = AUTHORITATIVE

    def __init__(self, zims_dir: str, indexer: str = "/usr/bin/iiab-make-kiwix-lib"):
        self._dir = zims_dir
        self._indexer = indexer

    def is_installed(self) -> bool:
        return os.path.isdir(self._dir)

    def list(self) -> List[ManifestItem]:
        items: List[ManifestItem] = []
        try:
            names = os.listdir(self._dir)
        except OSError:
            return items
        for name in names:
            if not name.lower().endswith(".zim"):
                continue
            path = os.path.join(self._dir, name)
            try:
                size = os.path.getsize(path)
            except OSError:
                continue  # vanished between listdir and stat
            items.append(build_item(name, size, read_zim_uuid_from_file(path)))
        items.sort(key=lambda it: it.identity.local_ref)
        return items

    def get(self, local_ref: str) -> Optional[ManifestItem]:
        # Resolve the one requested file instead of scanning list() and opening every
        # ZIM header. The containment guard runs FIRST, so a traversal ref never reaches
        # the filesystem.
        if not is_safe_local_ref(local_ref) or not local_ref.lower().endswith(".zim"):
            return None
        path = os.path.join(self._dir, local_ref)
        try:
            size = os.path.getsize(path)
        except OSError:
            return None
        return build_item(local_ref, size, read_zim_uuid_from_file(path))

    def prepare_export(self, local_ref: str) -> Optional[ExportPlan]:
        # A ZIM streams directly (it IS the file), so nothing is staged. Same containment
        # guard as get(), so a traversal ref never reaches the filesystem.
        path = self._export_path(local_ref)
        if path is None:
            return None
        return ExportPlan(
            stages=False,
            total_bytes=os.path.getsize(path),
            filename=local_ref,
            content_type="application/octet-stream",
        )

    def open_fetch(self, local_ref: str, staged_dir: Optional[str] = None) -> Optional[ExportStream]:
        # Direct app: staged_dir is unused. Re-validate at fetch time (the file may have
        # vanished between prepare and fetch).
        path = self._export_path(local_ref)
        if path is None:
            return None
        return ExportStream(
            filename=local_ref,
            content_type="application/octet-stream",
            chunks=file_stream(path),
        )

    def _export_path(self, local_ref: str) -> Optional[str]:
        """The on-disk path of an exportable ZIM, or None. Containment-guarded, like get()."""
        if not is_safe_local_ref(local_ref) or not local_ref.lower().endswith(".zim"):
            return None
        path = os.path.join(self._dir, local_ref)
        return path if os.path.isfile(path) else None

    def check(self, dedup: dict) -> str:
        # Same (creator, lang, flavour) line is the comparable set; the UUID is the exact
        # build and the date is the version axis. Authoritative, so the verdict is a hard fact.
        line = dedup.get("line")
        build = dedup.get("build")
        version = dedup.get("version") or ""
        local = [it.identity.dedup for it in self.list() if it.identity.dedup.line == line]
        if not local:
            return ABSENT
        if build and any(d.build == build for d in local):
            return SAME_VERSION
        local_version = max((d.version or "") for d in local)
        if local_version == version:
            return SAME_VERSION
        return OLDER_HERE if local_version < version else NEWER_HERE

    def import_item(self, entry: dict, payload_path: str) -> dict:
        # The payload is the .zim itself: place it in the zims dir under its own name, then
        # re-run the indexer so the library entry appears. Overwriting the same name is
        # idempotent (same content, same UUID).
        local_ref = (entry.get("identity") or {}).get("localRef", "")
        if not is_safe_local_ref(local_ref) or not local_ref.lower().endswith(".zim"):
            return {"ok": False, "reason": "invalid localRef"}
        if not os.path.isfile(payload_path):
            return {"ok": False, "reason": "payload not found"}
        os.makedirs(self._dir, exist_ok=True)
        dest = os.path.join(self._dir, local_ref)
        try:
            shutil.copyfile(payload_path, dest)
        except OSError as exc:
            return {"ok": False, "reason": f"copy failed: {exc}"}
        self._reindex()
        return {"ok": True, "app": "kiwix", "localRef": local_ref, "bytes": os.path.getsize(dest)}

    def delete(self, local_ref: str) -> dict:
        # A ZIM is self-contained, so the file IS the unit: remove it, then re-run the indexer
        # to drop its library.xml entry. freedBytes is the file size exactly (no shared bytes).
        # Same containment guard as get(), so a traversal ref never reaches the filesystem.
        if not is_safe_local_ref(local_ref) or not local_ref.lower().endswith(".zim"):
            return {"ok": False, "reason": "invalid localRef"}
        path = os.path.join(self._dir, local_ref)
        if not os.path.isfile(path):
            return {"ok": False, "reason": "item not found"}
        try:
            freed = os.path.getsize(path)
        except OSError:
            freed = 0
        try:
            os.remove(path)
        except OSError as exc:
            return {"ok": False, "reason": f"delete failed: {exc}"}
        self._reindex()
        return {"ok": True, "app": "kiwix", "localRef": local_ref, "freedBytes": freed}

    def _reindex(self) -> None:
        """Rebuild the Kiwix library index. A seam, so a test need not run the real indexer.
        NOTE: this runs outside the dash-node jobs engine, so a concurrent kiwix download +
        its end-of-run reindex can race (routes.ts ADFA-5004). The front serializes imports."""
        if not self._indexer or not os.path.exists(self._indexer):
            return
        try:
            subprocess.run([self._indexer], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        except OSError:
            pass
