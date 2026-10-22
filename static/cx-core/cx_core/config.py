"""Runtime configuration, read from the environment so the same code runs on a systemd
IIAB host and under the proot host on a phone. Defaults match the box layout."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    host: str = "127.0.0.1"  # localhost only, like dash-node; the front reaches it in-box
    port: int = 9500
    zims_dir: str = "/library/zims/content/"

    @staticmethod
    def from_env() -> "Config":
        return Config(
            host=os.environ.get("CX_HOST", "127.0.0.1"),
            port=int(os.environ.get("CX_PORT", "9500")),
            zims_dir=os.environ.get("CX_ZIMS_DIR", "/library/zims/content/"),
        )
