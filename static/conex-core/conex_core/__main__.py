"""Entry point: ``python -m conex_core``. Reads config from the environment and serves."""

from __future__ import annotations

import sys

from .config import Config
from .server import serve


def main() -> None:
    # The supervisor redirects stdout/stderr to /var/log/conex-core.log and the server runs
    # forever, so block-buffered output never reaches the log. Line-buffer our own streams here,
    # so the guarantee holds however the core is launched (no per-supervisor -u to keep in sync).
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(line_buffering=True)
        except (AttributeError, ValueError):
            pass
    serve(Config.from_env())


if __name__ == "__main__":
    main()
