"""The inner contract: the app-adapter port (Pillar 0).

One adapter owns "how THIS app identifies, stores, exports, imports and deletes its
content". The core and the manifest stay app-agnostic; a new app = a new adapter.

This slice is the READ side only, so the port declares the read methods. The full
port in the ADR also has check / export / importItem / delete; those arrive with the
later pillars and are added here then. Where an app has an authoritative mechanism,
the adapter DELEGATES to it rather than re-deriving: one source of truth.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Callable, Iterable, List, Optional

from .model import ManifestItem


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
        adapter cannot export it. Read-only on the source. Default: export not supported.
        (The full ADR port also has importItem / delete; those arrive with later pillars.)"""
        return None
