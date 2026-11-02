"""The HTTP layer: a thin stdlib http.server wrapper around the pure router. No
third-party dependency, so the core runs on any of AMD64 / ARM64 / ARM32 with just
the system Python 3. Bound to localhost, like dash-node; the front reaches it in-box.
"""

from __future__ import annotations

import json
import logging
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from . import VERSION
from .config import Config
from .export_ops import ExportOps
from .ports import ExportStream
from .registry import Registry
from .router import dispatch
from .share_ops import ShareOps

_MAX_BODY = 1 * 1024 * 1024  # a plan request is small; cap the read defensively
_BAD_JSON = object()  # sentinel: body present but not valid JSON
_TOO_LARGE = object()  # sentinel: declared body exceeds _MAX_BODY

# One logger for the service. The supervisor (PDSM wrapper or systemd) redirects stdout to
# /var/log/conex-core.log, so a record here is the box-side trace of a failure. Access logs
# stay off (log_message is a no-op); only errors, uncaught exceptions and startup are logged,
# so a failed import leaves a trace without a per-request firehose.
log = logging.getLogger("conex_core")


def make_handler(registry: Registry, ops: ExportOps, share: ShareOps = None):
    class Handler(BaseHTTPRequestHandler):
        server_version = f"conex-core/{VERSION}"
        # HTTP/1.1 so the chunked export stream is valid (HTTP/1.0 would hand the client the
        # chunk-size lines as body). The JSON path sends Content-Length, so keep-alive holds.
        protocol_version = "HTTP/1.1"

        def _share_token(self):
            # The share session token travels in a header, never the URL query, so it does not
            # land in access logs (it is the only auth for the gate).
            return self.headers.get("X-Share-Token")

        def _dispatch(self, method: str, path: str, query: dict, body=None):
            # Single dispatch boundary: an uncaught error in a handler becomes a logged 500
            # with a JSON body, never a dropped connection the front reads as a network error.
            try:
                return dispatch(registry, method, path, query, body, ops=ops, share=share,
                                token=self._share_token())
            except Exception:
                log.exception("unhandled error: %s %s", method, path)
                return 500, {"error": "internal error"}

        def do_GET(self) -> None:  # noqa: N802 (http.server naming)
            parts = urlsplit(self.path)
            query = {k: v[0] for k, v in parse_qs(parts.query).items()}
            status, payload = self._dispatch("GET", parts.path, query)
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
            status, payload = self._dispatch("POST", parts.path, {}, body)
            self._send(status, payload)

        def do_DELETE(self) -> None:  # noqa: N802 (http.server naming)
            parts = urlsplit(self.path)
            query = {k: v[0] for k, v in parse_qs(parts.query).items()}
            status, payload = self._dispatch("DELETE", parts.path, query)
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
            if status >= 400:
                # Log every error response, so an import or share failure leaves a box-side
                # trace. Log the path WITHOUT its query string: a client may put the share token
                # in ?token=... (conex-core reads it from the header, but the query value must not
                # land in the log).
                detail = payload.get("error") or payload.get("reason") if isinstance(payload, dict) else ""
                log.warning("%s %s -> %d %s", self.command, urlsplit(self.path).path, status, detail or "")
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
            try:
                # Write the headers INSIDE the same try whose finally runs on_close, so on_close
                # is guaranteed on every exit: a client that disconnects during the header write
                # must still release the op pin and the share single-flight slot (K2GO-376),
                # never strand them. on_open follows the headers, so a pre-header failure runs
                # on_close without a matching on_open: the op cleanup (_finish) is safe either way.
                self.send_response(status)
                self.send_header("Content-Type", stream.content_type)
                self.send_header("Content-Disposition", f'attachment; filename="{stream.filename}"')
                self.send_header("Transfer-Encoding", "chunked")
                self.send_header("Connection", "close")
                self.end_headers()
                if stream.on_open:
                    stream.on_open()
                for block in stream.chunks:
                    if not block:
                        continue
                    self.wfile.write(b"%X\r\n" % len(block))
                    self.wfile.write(block)
                    self.wfile.write(b"\r\n")
                self.wfile.write(b"0\r\n\r\n")
                # The full body (and its terminator) is written: a clean close. A mid-stream
                # client drop raises above and leaves this False, so the share gate counts the
                # item delivered only on a clean close (K2GO-376).
                stream.completed = True
            finally:
                if stream.on_close:
                    stream.on_close()

        def log_message(self, *_args) -> None:
            # Quiet by default; the front owns user-facing progress / logging.
            pass

    return Handler


def serve(config: Config) -> None:
    # Log to stdout, which the supervisor redirects to /var/log/conex-core.log. A StreamHandler
    # flushes each record, so a failure is on disk at once even though the process never exits;
    # the supervisor also runs python with -u so any other output is unbuffered too.
    logging.basicConfig(level=logging.INFO, stream=sys.stdout,
                        format="%(asctime)s conex-core %(levelname)s %(message)s")
    registry = Registry.load(config)
    ops = ExportOps(registry, config.staging_dir)
    share = ShareOps(registry, ops)
    # Reclaim export op temps left by a dead process before serving (K2GO-507 process-death
    # cleanup owner): a fresh process holds no ops, so any op temp on disk is an orphan. The
    # share sweep then drops any in-memory sessions (K2GO-376 closer 2); a share session's own
    # on-disk footprint is its child export temps, already reclaimed by the export sweep above.
    ops.sweep_startup()
    share.sweep_startup()
    httpd = ThreadingHTTPServer((config.host, config.port), make_handler(registry, ops, share))
    log.info("conex-core %s listening on http://%s:%d (apps: %s)", VERSION, config.host,
             config.port, ", ".join(registry.apps()) or "none")
    httpd.serve_forever()
