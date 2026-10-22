"""Adapter registry: enumerate the content apps present on the box and load each one's
adapter. The read API is just the core composing every loaded adapter (ADR mapping).

Keeping the candidate list here is the one place a new app is wired in. An adapter is
loaded only when its app is installed, so the service is aware only of what is present.
"""

from __future__ import annotations

from typing import Dict, List

from .adapters.kiwix import KiwixAdapter
from .config import Config
from .ports import ContentAdapter


class Registry:
    def __init__(self, adapters: List[ContentAdapter]):
        self._by_app: Dict[str, ContentAdapter] = {a.app: a for a in adapters}

    @staticmethod
    def load(config: Config) -> "Registry":
        candidates: List[ContentAdapter] = [
            KiwixAdapter(config.zims_dir),
            # Kolibri and Calibre adapters slot in here as they are built.
        ]
        return Registry([a for a in candidates if a.is_installed()])

    def apps(self) -> List[str]:
        return sorted(self._by_app.keys())

    def get(self, app: str) -> ContentAdapter:
        return self._by_app[app]

    def has(self, app: str) -> bool:
        return app in self._by_app

    def adapters(self) -> List[ContentAdapter]:
        return [self._by_app[a] for a in self.apps()]
