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
    """One item's byte stream the core serves and the front moves (ADR D1c). ``on_close``
    runs after the last byte; the EXPORT OP LAYER sets it (to drop the op and remove its
    temp), not the adapter: the adapter only opens the stream, the op layer owns the
    lifecycle (K2GO-507)."""

    filename: str
    content_type: str
    chunks: Iterable[bytes]
    on_close: Optional[Callable[[], None]] = None


@dataclass
class ExportPlan:
    """How to export one item (K2GO-507). ``stages`` is True when the item must be staged
    into a temp before its bytes can stream (a Kolibri channel), False when it streams
    directly (a Kiwix .zim, a Calibre book dir). ``total_bytes`` is the size to move: the
    disk preflight and the progress denominator. ``filename`` / ``content_type`` describe
    the stream. Returned by ``prepare_export``; None when the item is absent or not
    exportable. The op layer owns the temp and the staging thread; the adapter only reports
    this plan and, when asked, stages and opens the stream."""

    stages: bool
    total_bytes: int
    filename: str
    content_type: str


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

    def prepare_export(self, local_ref: str) -> "Optional[ExportPlan]":
        """Validate that this item can be exported and report its export plan (K2GO-507).
        Read-only on the source: no staging, no temp. Default: export not supported."""
        return None

    def stage_export(self, local_ref: str, dest: str) -> bool:
        """Stage the item into ``dest`` (the op layer's temp dir), for a plan with
        stages=True. Read-only on the source (copies out). Runs off the request thread, so it
        may be slow. Returns True on success. Default: nothing to stage."""
        return False

    def open_fetch(self, local_ref: str, staged_dir: Optional[str] = None) -> Optional[ExportStream]:
        """Open the byte stream for a prepared export: the staged tar (``staged_dir`` set) or
        the direct file / tar (``staged_dir`` None). The op layer sets ``on_close``. Returns
        None when the item is no longer exportable. Default: not supported."""
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
