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
import os
import shutil
import sqlite3
import tarfile
import tempfile
from typing import List, Optional

from ..export import resolve_within, tar_stream
from ..importing import safe_extract_tar, single_child_dir
from ..model import ABSENT, SAME_VERSION, SOFT, Dedup, Identity, ManifestItem, normalize_text
from ..ports import ContentAdapter, ExportStream
from .calibreweb import CalibreWebClient, resolve_calibre_credentials

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


def _content_files(book_dir: str, formats: set) -> List[str]:
    """The book's content files to upload. When the manifest lists formats, pick files with
    those extensions; otherwise take every file except the Calibre sidecars (cover, opf)."""
    out: List[str] = []
    try:
        names = sorted(os.listdir(book_dir))
    except OSError:
        return out
    for name in names:
        full = os.path.join(book_dir, name)
        if not os.path.isfile(full):
            continue
        ext = os.path.splitext(name)[1].lstrip(".").lower()
        if formats:
            if ext in formats:
                out.append(full)
        elif name.lower() != "metadata.opf" and not name.lower().startswith("cover."):
            out.append(full)
    return out


class CalibreAdapter(ContentAdapter):
    app = "calibre"
    id_kind = SOFT  # arbitrary media has no authoritative cross-box id; verdicts are advisory

    def __init__(
        self, db_path: str,
        calibre_url: str = "http://127.0.0.1:8083",
        credentials_file: str = "/library/dashboard/credentials.json",
        staging_dir: str = "/library/.cx-staging",
    ):
        self._db = db_path
        # The Calibre library root is the directory that holds metadata.db; each book's
        # files live under <root>/<books.path>/.
        self._lib_root = os.path.dirname(db_path)
        self._url = calibre_url
        self._credentials_file = credentials_file
        self._staging_dir = staging_dir

    def is_installed(self) -> bool:
        return os.path.exists(self._db)

    def check(self, dedup: dict) -> str:
        # Soft: there is no authoritative cross-box id for arbitrary media, so the verdict is
        # advisory. Match on the normalized title + authors of the soft key; present -> "you
        # may already have this" (SAME_VERSION), else ABSENT. Never an automatic skip.
        line = dedup.get("line") or {}
        title, authors = line.get("title"), line.get("authors")
        for item in self.list():
            local = item.identity.dedup.line
            if local.get("title") == title and local.get("authors") == authors:
                return SAME_VERSION
        return ABSENT

    def import_item(self, entry: dict, payload_path: str) -> dict:
        # The payload is the tar of the book's own directory. Extract it safely, then add each
        # content-format file through Calibre-Web's upload (calibredb is not installed).
        # Soft identity: a re-import can create a duplicate record, so the front checks first.
        if not os.path.isfile(payload_path):
            return {"ok": False, "reason": "payload not found"}
        formats = {f.lower() for f in ((entry.get("identity") or {}).get("dedup", {}).get("line", {}).get("formats") or [])}
        os.makedirs(self._staging_dir, exist_ok=True)
        work = tempfile.mkdtemp(prefix="cximport-", dir=self._staging_dir)
        try:
            try:
                safe_extract_tar(payload_path, work)
            except (ValueError, tarfile.TarError) as exc:
                return {"ok": False, "reason": f"bad archive: {exc}"}
            files = _content_files(single_child_dir(work) or work, formats)
            if not files:
                return {"ok": False, "reason": "no book files in archive"}
            uploaded = self._upload_files(files)
            if uploaded == 0:
                return {"ok": False, "reason": "calibre upload failed"}
            return {"ok": True, "app": "calibre", "localRef": (entry.get("identity") or {}).get("localRef"), "uploaded": uploaded}
        finally:
            shutil.rmtree(work, ignore_errors=True)

    def _upload_files(self, files: List[str]) -> int:
        """Log into Calibre-Web and upload each file. A seam, so a test need not run a live
        Calibre-Web. Returns the number uploaded."""
        user, password = resolve_calibre_credentials(self._credentials_file)
        client = CalibreWebClient(self._url, user, password)
        if not client.login():
            return 0
        done = 0
        for path in files:
            if client.upload(path, os.path.basename(path)):
                done += 1
        return done

    def delete(self, local_ref: str) -> dict:
        # calibredb is absent, so delete through Calibre-Web's own remove (POST /delete/{id}),
        # which drops the book row plus its FK rows and files. Its delete keys on the integer
        # library id, so resolve it from the uuid first. A book is self-contained (no shared
        # files), so freedBytes is the item's KNOWN size (already summed from data, like Kiwix
        # uses the file size): no need for a racy free-space delta.
        item = self.get(local_ref)
        book_id = self._book_id(local_ref)
        if item is None or book_id is None:
            return {"ok": False, "reason": "item not found"}
        if not self._remove_book(book_id):
            return {"ok": False, "reason": "calibre delete failed"}
        return {"ok": True, "app": "calibre", "localRef": local_ref, "freedBytes": item.bytes}

    def _book_id(self, uuid: str) -> Optional[int]:
        """The integer books.id for a book uuid, from metadata.db. None if absent. Calibre-Web
        delete keys on this id, not the uuid."""
        if not uuid or not os.path.exists(self._db):
            return None
        try:
            con = sqlite3.connect(f"file:{self._db}?mode=ro", uri=True)
        except sqlite3.Error:
            return None
        try:
            row = con.execute("SELECT id FROM books WHERE uuid = ?", (uuid,)).fetchone()
        except sqlite3.Error:
            return None
        finally:
            con.close()
        return int(row[0]) if row and row[0] is not None else None

    def _remove_book(self, book_id: int) -> bool:
        """Log into Calibre-Web and remove one book. A seam, like _upload_files, so a test need
        not run a live Calibre-Web. Returns True on success."""
        user, password = resolve_calibre_credentials(self._credentials_file)
        client = CalibreWebClient(self._url, user, password)
        if not client.login():
            return False
        return client.remove(book_id)

    def _book_path(self, uuid: str) -> Optional[str]:
        """The relative on-disk directory for a book uuid, from metadata.db. None if absent."""
        if not uuid or not os.path.exists(self._db):
            return None
        try:
            con = sqlite3.connect(f"file:{self._db}?mode=ro", uri=True)
        except sqlite3.Error:
            return None
        try:
            row = con.execute("SELECT path FROM books WHERE uuid = ?", (uuid,)).fetchone()
        except sqlite3.Error:
            return None
        finally:
            con.close()
        return row[0] if row and row[0] else None

    def open_export(self, local_ref: str) -> Optional[ExportStream]:
        # Export the book's own directory (its format files, cover and opf), the layout
        # Calibre itself reads back. books.path comes from the DB, so a containment guard
        # keeps a crafted path from escaping the library root.
        rel = self._book_path(local_ref)
        if rel is None:
            return None
        book_dir = resolve_within(self._lib_root, rel)
        if book_dir is None or not os.path.isdir(book_dir):
            return None
        return ExportStream(
            filename=f"{local_ref}.tar",
            content_type="application/x-tar",
            chunks=tar_stream(book_dir, arcname=local_ref),
        )

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
