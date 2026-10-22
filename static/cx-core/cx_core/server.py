"""The HTTP layer: a thin stdlib http.server wrapper around the pure router. No
third-party dependency, so the core runs on any of AMD64 / ARM64 / ARM32 with just
the system Python 3. Bound to localhost, like dash-node; the front reaches it in-box.
"""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from .config import Config
from .registry import Registry
from .router import dispatch


def make_handler(registry: Registry):
    class Handler(BaseHTTPRequestHandler):
        server_version = "cx-core/0.1"

        def do_GET(self) -> None:  # noqa: N802 (http.server naming)
            parts = urlsplit(self.path)
            query = {k: v[0] for k, v in parse_qs(parts.query).items()}
            status, payload = dispatch(registry, "GET", parts.path, query)
            self._send(status, payload)

        def _send(self, status: int, payload) -> None:
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args) -> None:
            # Quiet by default; the front owns user-facing progress / logging.
            pass

    return Handler


def serve(config: Config) -> None:
    registry = Registry.load(config)
    httpd = ThreadingHTTPServer((config.host, config.port), make_handler(registry))
    print(f"cx-core listening on http://{config.host}:{config.port} "
          f"(apps: {', '.join(registry.apps()) or 'none'})")
    httpd.serve_forever()
