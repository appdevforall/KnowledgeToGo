"""Shared, app-agnostic content model and the manifest shapes.

These types are the data half of Pillar 0. The core never interprets the inside of
a dedup key: an adapter builds it, and the matching adapter on another box reads it.
So fields like ``line`` and ``version`` carry whatever shape the owning app needs.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Optional

# idKind values. "authoritative" means the id is safe for a hard skip / delete
# (Kiwix UUID, Kolibri channel_id+version). "soft" means advisory only, never an
# automatic action (Calibre, which has no native key for arbitrary media).
AUTHORITATIVE = "authoritative"
SOFT = "soft"

# Presence: the receiver's verdict for an incoming item (ADR check()). For an
# authoritative idKind the verdict is a hard fact; for a soft idKind (Calibre) it is
# advisory only, never an automatic skip. OLDER_HERE means the box's copy is older than
# the incoming one (import would upgrade); NEWER_HERE means the box's copy is newer.
ABSENT = "ABSENT"
SAME_VERSION = "SAME_VERSION"
OLDER_HERE = "OLDER_HERE"
NEWER_HERE = "NEWER_HERE"

_WS = re.compile(r"\s+")


def normalize_text(value: str) -> str:
    """Normalize a free-text field for a soft dedup key: trim, lowercase, collapse
    runs of whitespace to one space. Shared so every adapter that needs a soft key
    (Calibre first) normalizes the same way, instead of each inventing its own."""
    if not value:
        return ""
    return _WS.sub(" ", value.strip().lower())


@dataclass(frozen=True)
class Dedup:
    """Cross-box "do you already have this?" envelope. The core passes it through;
    only the owning app's adapter interprets ``line`` / ``build`` / ``version``."""

    line: Any  # app-shaped line key: Kiwix {creator,lang,flavour}, Kolibri channel_id
    build: Optional[str]  # exact-build id: Kiwix ZIM UUID; may be None where an app has none
    version: Any  # version axis: Kiwix date, Kolibri integer version
    id_kind: str  # AUTHORITATIVE or SOFT

    def to_json(self) -> dict:
        return {
            "line": self.line,
            "build": self.build,
            "version": self.version,
            "idKind": self.id_kind,
        }


@dataclass(frozen=True)
class Identity:
    """Two handles (ADR "Identity semantics"): ``local_ref`` is the app's own handle
    to THIS item on THIS box (export / delete, always free and authoritative-local);
    ``dedup`` is the cross-box key."""

    local_ref: str
    dedup: Dedup

    def to_json(self) -> dict:
        return {"localRef": self.local_ref, "dedup": self.dedup.to_json()}


@dataclass(frozen=True)
class ManifestItem:
    """One content item, as both the read-API row and a manifest entry. Export-only
    fields (``payload``) stay None on the read side."""

    app: str
    kind: str
    title: str
    bytes: int
    identity: Identity
    integrity: Optional[dict] = None  # upstream hash when present; never computed on-device
    payload: Optional[str] = None  # per-item archive path, set only at export (Pillar 2)
    requires: list = field(default_factory=list)

    def to_json(self) -> dict:
        return {
            "app": self.app,
            "kind": self.kind,
            "title": self.title,
            "bytes": self.bytes,
            "identity": self.identity.to_json(),
            "integrity": self.integrity,
            "payload": self.payload,
            "requires": list(self.requires),
        }
