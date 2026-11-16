"""Runtime configuration, read from the environment so the same code runs on a systemd
IIAB host and under the proot host on a phone. Defaults match the box layout."""

from __future__ import annotations

import os
from dataclasses import dataclass

DEFAULT_PORT = 9500
#: The share rsync daemon port (K2GO-577). Above 1024: a proot box cannot bind a low port.
DEFAULT_SHARE_RSYNC_PORT = 9873


def _parse_port(raw: str, default: int = DEFAULT_PORT, allow_off: bool = False) -> int:
    """Parse a TCP port from the environment, falling back to the default on an empty,
    non-numeric or out-of-range value instead of crashing the service at startup. With
    ``allow_off``, 0 is kept (the feature is off)."""
    try:
        port = int(raw)
    except (TypeError, ValueError):
        return default
    if allow_off and port == 0:
        return 0
    return port if 1 <= port <= 65535 else default


@dataclass(frozen=True)
class Config:
    host: str = "127.0.0.1"  # localhost only, like dash-node; the front reaches it in-box
    port: int = DEFAULT_PORT
    # The content volume: the disk the storage summary measures (GET /v1/storage). All app
    # content (zims, kolibri, calibre) lives under it, so one disk_usage read is the box total.
    content_dir: str = "/library"
    zims_dir: str = "/library/zims/content/"
    kiwix_indexer: str = "/usr/bin/iiab-make-kiwix-lib"
    kiwix_serve: str = "/opt/iiab/kiwix/bin/kiwix-serve"
    kolibri_db: str = "/library/kolibri/db.sqlite3"
    kolibri_bin: str = "/usr/bin/kolibri"
    calibre_db: str = "/library/calibre-web/metadata.db"
    calibre_url: str = "http://127.0.0.1:8083"
    credentials_file: str = "/library/dashboard/credentials.json"
    # Where an export is staged. On the content volume, not /tmp (often tmpfs / small), so a
    # multi-gigabyte channel export has room and the free-space preflight measures the right
    # disk.
    staging_dir: str = "/library/.conex-staging"
    # K2GO-577: the share rsync daemon port (0 = off: share over HTTP fetch only), the rsync
    # binary, and where a pull keeps resumable partial files (the content volume, so the final
    # rename does not copy).
    share_rsync_port: int = DEFAULT_SHARE_RSYNC_PORT
    rsync_bin: str = "/usr/bin/rsync"
    partial_dir: str = "/library/.conex-partial"

    @staticmethod
    def from_env() -> "Config":
        return Config(
            host=os.environ.get("CONEX_HOST", "127.0.0.1"),
            port=_parse_port(os.environ.get("CONEX_PORT", str(DEFAULT_PORT))),
            content_dir=os.environ.get("CONEX_CONTENT_DIR", "/library"),
            zims_dir=os.environ.get("CONEX_ZIMS_DIR", "/library/zims/content/"),
            kiwix_indexer=os.environ.get("CONEX_KIWIX_INDEXER", "/usr/bin/iiab-make-kiwix-lib"),
            kiwix_serve=os.environ.get("CONEX_KIWIX_SERVE", "/opt/iiab/kiwix/bin/kiwix-serve"),
            kolibri_db=os.environ.get("CONEX_KOLIBRI_DB", "/library/kolibri/db.sqlite3"),
            kolibri_bin=os.environ.get("CONEX_KOLIBRI_BIN", "/usr/bin/kolibri"),
            calibre_db=os.environ.get("CONEX_CALIBRE_DB", "/library/calibre-web/metadata.db"),
            calibre_url=os.environ.get("CONEX_CALIBRE_URL", "http://127.0.0.1:8083"),
            credentials_file=os.environ.get(
                "K2GO_CREDENTIALS_FILE", "/library/dashboard/credentials.json"),
            staging_dir=os.environ.get("CONEX_STAGING_DIR", "/library/.conex-staging"),
            share_rsync_port=_parse_port(
                os.environ.get("CONEX_SHARE_RSYNC_PORT", str(DEFAULT_SHARE_RSYNC_PORT)),
                DEFAULT_SHARE_RSYNC_PORT, allow_off=True),
            rsync_bin=os.environ.get("CONEX_RSYNC_BIN", "/usr/bin/rsync"),
            partial_dir=os.environ.get("CONEX_PARTIAL_DIR", "/library/.conex-partial"),
        )
