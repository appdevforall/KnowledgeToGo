"""Serve a folder the way an R2 public bucket serves its objects.

For a local test only. The differences from `python -m http.server`:

- No directory listing. A path that is not an object gets 404.
- No implicit index. "/fdroid/" is 404; "/fdroid/index.html" is the object.
  With --index-rewrite, "/x/" serves "/x/index.html", the same as a Cloudflare
  URL rewrite rule would do.
- Byte ranges (206), ETag and Last-Modified, as the R2 public endpoint gives.
- The same Content-Type and Cache-Control that the publish step sets.

Usage: python r2_mock_server.py --root bucket --port 8080 [--index-rewrite]
"""

import argparse
import email.utils
import hashlib
import http.server
from pathlib import Path
from urllib.parse import unquote

# The same types and cache rules as the publish step (fdroid_repo.py, in this folder).
from fdroid_repo import LONG, SHORT, TYPES  # noqa: E402

OBJECT_FILE = ".r2-object"


def cache_control(key: str) -> str:
    # fdroid_repo.cache_control takes a path under the prefix; a key starts with the prefix.
    rel = key.split("/", 1)[1] if "/" in key else key
    from fdroid_repo import cache_control as publish_rule
    return publish_rule(rel)


class Handler(http.server.BaseHTTPRequestHandler):
    root: Path
    index_rewrite = False
    server_version = "r2-mock"
    sys_version = ""

    def resolve(self):
        # R2 decodes the path: "icon_x%3D.png" is the object "icon_x=.png".
        key = unquote(self.path.split("?", 1)[0].split("#", 1)[0]).lstrip("/")
        if self.index_rewrite and (key == "" or key.endswith("/")):
            key += "index.html"
        target = (self.root / key).resolve()
        if self.root not in target.parents:
            return key, None
        # R2 keys are flat: "fdroid/repo" can be an object beside "fdroid/repo/...". publish.sh
        # (local backend) keeps such an object in <folder>/.r2-object. It is always a page.
        if target.is_dir() and (target / OBJECT_FILE).is_file():
            return key + ".html", target / OBJECT_FILE
        if not target.is_file():
            return key, None
        return key, target

    def do_HEAD(self):
        self.respond(body=False)

    def do_GET(self):
        self.respond(body=True)

    def respond(self, body: bool):
        key, target = self.resolve()
        if target is None:
            data = b"Object not found\n"
            self.send_response(404)
            self.send_header("Content-Type", "text/plain;charset=UTF-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            if body:
                self.wfile.write(data)
            return

        stat = target.stat()
        etag = '"' + hashlib.md5(f"{stat.st_size}-{stat.st_mtime_ns}".encode()).hexdigest() + '"'
        modified = email.utils.formatdate(stat.st_mtime, usegmt=True)
        if self.headers.get("If-None-Match") == etag:
            self.send_response(304)
            self.send_header("ETag", etag)
            self.end_headers()
            return

        start, end = 0, stat.st_size - 1
        status = 200
        wanted = self.headers.get("Range", "")
        if wanted.startswith("bytes=") and "," not in wanted:
            first, _, last = wanted[6:].partition("-")
            try:
                if first:
                    start = int(first)
                    end = min(int(last), end) if last else end
                else:
                    start = max(0, stat.st_size - int(last))
                if start > end:
                    raise ValueError
                status = 206
            except ValueError:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{stat.st_size}")
                self.end_headers()
                return

        self.send_response(status)
        self.send_header("Content-Type", TYPES.get(Path(key).suffix, "application/octet-stream"))
        self.send_header("Cache-Control", cache_control(key))
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", etag)
        self.send_header("Last-Modified", modified)
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{stat.st_size}")
        self.end_headers()
        if not body:
            return
        with target.open("rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(1 << 20, left))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    return
                left -= len(chunk)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--index-rewrite", action="store_true")
    args = parser.parse_args()

    Handler.root = args.root.resolve()
    Handler.index_rewrite = args.index_rewrite
    server = http.server.ThreadingHTTPServer((args.bind, args.port), Handler)
    print(f"serving {Handler.root} on http://{args.bind}:{args.port}/ "
          f"(index rewrite {'on' if args.index_rewrite else 'off'})", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
