"""The inner contract: the app-adapter port (Pillar 0).

One adapter owns "how THIS app identifies, stores, exports, imports and deletes its
content". The core and the manifest stay app-agnostic; a new app = a new adapter.

The port now carries the full lifecycle: read, check, export, importItem and delete.
Where an app has an authoritative mechanism, the adapter DELEGATES to it rather than
re-deriving: one source of truth.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Callable, Iterable, List, Optional

from .model import ABSENT, AUTHORITATIVE, ManifestItem


@dataclass
class ExportStream:
    """One item staged for export: a byte stream the core serves and the front moves
    (ADR D1c). ``on_close`` releases any staging (a temp export tree) after streaming."""

    filename: str
    content_type: str
    chunks: Iterable[bytes]
    on_close: Optional[Callable[[], None]] = None


class ContentAdapter(ABC):
    """Read-side port for one content app."""

    #: Stable adapter key used in the REST API (e.g. "kiwix"). Lowercase, URL-safe.
    app: str = ""
    #: Authority of this app's dedup id: AUTHORITATIVE (hard verdict) or SOFT (advisory).
    id_kind: str = AUTHORITATIVE

    @abstractmethod
    def is_installed(self) -> bool:
        """True when this app is present on the box. The registry loads an adapter
        only when this is true, so the service is aware only of installed apps."""

    @abstractmethod
    def list(self) -> List[ManifestItem]:
        """Enumerate indexed content with identity + size (read side of Pillar 1)."""

    def get(self, local_ref: str) -> Optional[ManifestItem]:
        """One item by its local_ref. Default: scan ``list``. An adapter overrides
        this when it can look up a single item more cheaply."""
        for item in self.list():
            if item.identity.local_ref == local_ref:
                return item
        return None

    def open_export(self, local_ref: str) -> Optional[ExportStream]:
        """Stage one item and return a byte stream, or None when the item is absent or the
        adapter cannot export it. Read-only on the source. Default: export not supported."""
        return None

    def check(self, dedup: dict) -> str:
        """The receiver's verdict for an incoming item, from its dedup key (ABSENT /
        SAME_VERSION / OLDER_HERE / NEWER_HERE). Reads only; moves nothing. Default: ABSENT.
        A soft idKind (Calibre) makes the verdict advisory, never an automatic skip."""
        return ABSENT

    def import_item(self, entry: dict, payload_path: str) -> dict:
        """Ingest one already-local item archive into the app index, delegating to the app's
        own import. Idempotent: a re-import must not double-count or corrupt. ``payload_path``
        is a box-visible local file the front placed. Default: not supported."""
        return {"ok": False, "reason": "import not supported"}

    def delete(self, local_ref: str) -> dict:
        """Remove one item to reclaim space, delegating to the app's own delete (never driving
        the app DB + files directly): the app owns its storage invariants and the next list()
        reflects the removal, so there is no separate state to track. Returns
        {ok: True, freedBytes: int, ...} on success, or {ok: False, reason} on failure.
        ``freedBytes`` is what the app ACTUALLY reclaimed, not the item's nominal size (a
        Kolibri channel shares files, so only non-shared bytes are freed). The front confirms
        the destructive action with the user first; the core only executes. Default: not
        supported."""
        return {"ok": False, "reason": "delete not supported"}
