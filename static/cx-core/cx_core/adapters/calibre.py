"""Calibre adapter (read side).

Calibre-Web is not a book store: one record can be any media (epub, pdf, image, video,
audio) and can carry several formats, so there is no ISBN key. Identity is split:
- localRef = books.uuid: the box's own handle to this exact item, authoritative-local,
  generated once by Calibre when the item is added (for export / delete).
- dedup = a SOFT, advisory key from metadata Calibre already has (zero compute): normalized
  title + authors + formats + byte-size, plus an external identifier when present. idKind is
  soft: "you may already have this", never an automatic skip or delete.

The adapter reads metadata.db in readonly (the sockets/books.query.ts pattern). The schema
(books.uuid, data.format / data.uncompressed_size, identifiers) is confirmed on the box.
"""

from __future__ import annotations

import os
import sqlite3
from typing import List, Optional

from ..model import SOFT, Dedup, Identity, ManifestItem, normalize_text
from ..ports import ContentAdapter

# One row per book, with its authors, formats, total byte-size and one external id if any.
_BOOKS_SQL = """
SELECT b.uuid,
       b.title,
       (SELECT GROUP_CONCAT(a.name, ' & ')
          FROM authors a
          JOIN books_authors_link bal ON bal.author = a.id
         WHERE bal.book = b.id)                                   AS authors,
       (SELECT GROUP_CONCAT(d.format, ',') FROM data d WHERE d.book = b.id) AS formats,
       (SELECT COALESCE(SUM(d.uncompressed_size), 0) FROM data d WHERE d.book = b.id) AS bytes,
       (SELECT i.val FROM identifiers i WHERE i.book = b.id ORDER BY i.type LIMIT 1) AS ext_id
FROM books b
{where}
ORDER BY b.sort
"""


def build_book_item(
    uuid: str, title: Optional[str], authors: Optional[str],
    formats: Optional[str], size_bytes: int, ext_id: Optional[str],
) -> ManifestItem:
    """Build the manifest item for one Calibre record. Pure."""
    formats_list = sorted(f for f in (formats or "").split(",") if f)
    line = {
        "title": normalize_text(title or ""),
        "authors": normalize_text(authors or ""),
        "formats": formats_list,
        "bytes": size_bytes,
    }
    return ManifestItem(
        app="calibre",
        kind="book",
        title=title or uuid,
        bytes=size_bytes,
        identity=Identity(
            local_ref=uuid,
            # build carries a stronger hint (an external id) when Calibre has one; the key
            # stays soft either way, because arbitrary media has no authoritative cross-box id.
            dedup=Dedup(line=line, build=ext_id or None, version=None, id_kind=SOFT),
        ),
    )


class CalibreAdapter(ContentAdapter):
    app = "calibre"

    def __init__(self, db_path: str):
        self._db = db_path

    def is_installed(self) -> bool:
        return os.path.exists(self._db)

    def _query(self, only_uuid: Optional[str]) -> List[ManifestItem]:
        if not os.path.exists(self._db):
            return []
        sql = _BOOKS_SQL.format(where="WHERE b.uuid = ?" if only_uuid else "")
        params = (only_uuid,) if only_uuid else ()
        try:
            con = sqlite3.connect(f"file:{self._db}?mode=ro", uri=True)
        except sqlite3.Error:
            return []
        try:
            rows = con.execute(sql, params).fetchall()
        except sqlite3.Error:
            return []
        finally:
            con.close()
        return [build_book_item(r[0], r[1], r[2], r[3], r[4], r[5]) for r in rows]

    def list(self) -> List[ManifestItem]:
        return self._query(None)

    def get(self, local_ref: str) -> Optional[ManifestItem]:
        # localRef is a books.uuid used only as a bound SQL parameter (never a path), so a
        # single scoped query is enough; no path-containment guard applies here.
        if not local_ref:
            return None
        items = self._query(local_ref)
        return items[0] if items else None
