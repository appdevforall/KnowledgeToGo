"""Runtime configuration, read from the environment so the same code runs on a systemd
IIAB host and under the proot host on a phone. Defaults match the box layout."""

from __future__ import annotations

import os
from dataclasses import dataclass

DEFAULT_PORT = 9500


def _parse_port(raw: str) -> int:
    """Parse a TCP port from the environment, falling back to the default on an empty,
    non-numeric or out-of-range value instead of crashing the service at startup."""
    try:
        port = int(raw)
    except (TypeError, ValueError):
        return DEFAULT_PORT
    return port if 1 <= port <= 65535 else DEFAULT_PORT


@dataclass(frozen=True)
class Config:
    host: str = "127.0.0.1"  # localhost only, like dash-node; the front reaches it in-box
    port: int = DEFAULT_PORT
    zims_dir: str = "/library/zims/content/"
    kolibri_db: str = "/library/kolibri/db.sqlite3"
    calibre_db: str = "/library/calibre-web/metadata.db"

    @staticmethod
    def from_env() -> "Config":
        return Config(
            host=os.environ.get("CX_HOST", "127.0.0.1"),
            port=_parse_port(os.environ.get("CX_PORT", str(DEFAULT_PORT))),
            zims_dir=os.environ.get("CX_ZIMS_DIR", "/library/zims/content/"),
            kolibri_db=os.environ.get("CX_KOLIBRI_DB", "/library/kolibri/db.sqlite3"),
            calibre_db=os.environ.get("CX_CALIBRE_DB", "/library/calibre-web/metadata.db"),
        )
