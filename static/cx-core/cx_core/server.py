"""The HTTP layer: a thin stdlib http.server wrapper around the pure router. No
third-party dependency, so the core runs on any of AMD64 / ARM64 / ARM32 with just
the system Python 3. Bound to localhost, like dash-node; the front reaches it in-box.
"""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from .config import Config
from .ports import ExportStream
from .registry import Registry
from .router import dispatch

_MAX_BODY = 1 * 1024 * 1024  # a plan request is small; cap the read defensively
_BAD_JSON = object()  # sentinel: body present but not valid JSON
_TOO_LARGE = object()  # sentinel: declared body exceeds _MAX_BODY


def make_handler(registry: Registry):
    class Handler(BaseHTTPRequestHandler):
        server_version = "cx-core/0.1"
        # HTTP/1.1 so the chunked export stream is valid (HTTP/1.0 would hand the client the
        # chunk-size lines as body). The JSON path sends Content-Length, so keep-alive holds.
        protocol_version = "HTTP/1.1"

        def do_GET(self) -> None:  # noqa: N802 (http.server naming)
            parts = urlsplit(self.path)
            query = {k: v[0] for k, v in parse_qs(parts.query).items()}
            status, payload = dispatch(registry, "GET", parts.path, query)
            if isinstance(payload, ExportStream):
                self._stream(status, payload)
            else:
                self._send(status, payload)

        def do_POST(self) -> None:  # noqa: N802 (http.server naming)
            parts = urlsplit(self.path)
            body = self._read_json_body()
            if body is _TOO_LARGE:
                # Unread body would desync the next keep-alive request, so close the socket.
                self.close_connection = True
                self._send(413, {"error": "request body too large"})
                return
            if body is _BAD_JSON:
                self._send(400, {"error": "invalid JSON body"})
                return
            status, payload = dispatch(registry, "POST", parts.path, {}, body)
            self._send(status, payload)

        def do_DELETE(self) -> None:  # noqa: N802 (http.server naming)
            parts = urlsplit(self.path)
            query = {k: v[0] for k, v in parse_qs(parts.query).items()}
            status, payload = dispatch(registry, "DELETE", parts.path, query)
            self._send(status, payload)

        def _read_json_body(self):
            try:
                length = int(self.headers.get("Content-Length", 0))
            except ValueError:
                return _BAD_JSON
            if length <= 0:
                return None
            if length > _MAX_BODY:
                return _TOO_LARGE  # do not read a truncated body
            raw = self.rfile.read(length)
            try:
                return json.loads(raw.decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                return _BAD_JSON

        def _send(self, status: int, payload) -> None:
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _stream(self, status: int, stream: ExportStream) -> None:
            # Chunked transfer: the item size is not known up front for a tar, and the front
            # moves the bytes. on_close releases any staging after the last byte.
            # Close after the stream: the body length is unknown up front, so a chunked
            # response plus an explicit close keeps the connection state simple.
            self.close_connection = True
            self.send_response(status)
            self.send_header("Content-Type", stream.content_type)
            self.send_header("Content-Disposition", f'attachment; filename="{stream.filename}"')
            self.send_header("Transfer-Encoding", "chunked")
            self.send_header("Connection", "close")
            self.end_headers()
            try:
                for block in stream.chunks:
                    if not block:
                        continue
                    self.wfile.write(b"%X\r\n" % len(block))
                    self.wfile.write(block)
                    self.wfile.write(b"\r\n")
                self.wfile.write(b"0\r\n\r\n")
            finally:
                if stream.on_close:
                    stream.on_close()

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
