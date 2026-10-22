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
import uuid
from typing import List, Optional, Tuple

from ..model import AUTHORITATIVE, Dedup, Identity, ManifestItem
from ..ports import ContentAdapter

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
        id_kind=AUTHORITATIVE,
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

    def __init__(self, zims_dir: str):
        self._dir = zims_dir

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
