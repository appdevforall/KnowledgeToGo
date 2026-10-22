"""Entry point: ``python -m cx_core``. Reads config from the environment and serves."""

from __future__ import annotations

from .config import Config
from .server import serve


def main() -> None:
    serve(Config.from_env())


if __name__ == "__main__":
    main()
