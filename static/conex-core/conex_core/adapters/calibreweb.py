"""Calibre-Web HTTP access (add a book), isolated from the adapter.

calibredb is not installed on the box, so a book is added through Calibre-Web's own upload
endpoint, exactly as dash-node does (sockets/books.exec.ts): log in for a session + CSRF
token, then POST the file to /upload as multipart (field "btn-upload"). Delegating to
Calibre-Web keeps it the one owner of its library.

Credentials come from the same store dash-node uses: the env override first, then the
operator's credentials.json, then the Ansible factory default. This module never prints a
credential value.
"""

from __future__ import annotations

import http.cookiejar
import io
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid as _uuid
from typing import Tuple

_CSRF_RE = re.compile(r'name="csrf_token"[^>]*value="([^"]+)"')
_UPLOAD_BLOCK = 1024 * 1024


def resolve_calibre_credentials(credentials_file: str) -> Tuple[str, str]:
    """(username, password) for Calibre-Web: env K2GO_CALIBRE_USER / _PASS, else the calibre
    entry in credentials_file, else the Ansible default Admin / changeme."""
    env_user = os.environ.get("K2GO_CALIBRE_USER")
    env_pass = os.environ.get("K2GO_CALIBRE_PASS")
    if env_user and env_pass:
        return env_user, env_pass
    try:
        with open(credentials_file, encoding="utf-8") as handle:
            entry = (json.load(handle) or {}).get("calibre") or {}
        if entry.get("username") and entry.get("password"):
            return entry["username"], entry["password"]
    except (OSError, ValueError):
        pass
    return "Admin", "changeme"


def safe_upload_name(name: str) -> str:
    """A filename safe to place in a multipart Content-Disposition header. The name comes
    from an untrusted archive member, so CR / LF (which would inject headers) and double
    quotes (which would break the quoted value) are stripped. Never empty."""
    cleaned = name.replace("\r", "").replace("\n", "").replace('"', "").strip()
    cleaned = os.path.basename(cleaned)
    return cleaned or "upload.bin"


class _ConcatStream(io.RawIOBase):
    """A read-only stream of prefix bytes, then a file's bytes, then suffix bytes. Lets the
    multipart body be sent without reading the (possibly multi-gigabyte) file into memory."""

    def __init__(self, prefix: bytes, file_path: str, suffix: bytes):
        self._pre = io.BytesIO(prefix)
        self._file = open(file_path, "rb")
        self._suf = io.BytesIO(suffix)
        self._stage = 0  # 0 prefix, 1 file, 2 suffix, 3 done

    def readable(self) -> bool:
        return True

    def readinto(self, buffer) -> int:
        sources = (self._pre, self._file, self._suf)
        while self._stage < 3:
            got = sources[self._stage].readinto(buffer)
            if got:
                return got
            self._stage += 1
        return 0

    def close(self) -> None:
        try:
            self._file.close()
        finally:
            super().close()


def _multipart_frame(boundary: str, fields: dict, file_field: str, filename: str) -> Tuple[bytes, bytes]:
    sep = f"--{boundary}\r\n"
    head = ""
    for name, value in fields.items():
        head += sep + f'Content-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'
    head += sep + f'Content-Disposition: form-data; name="{file_field}"; filename="{filename}"\r\n'
    head += "Content-Type: application/octet-stream\r\n\r\n"
    return head.encode("utf-8"), f"\r\n--{boundary}--\r\n".encode("utf-8")


class CalibreWebClient:
    """A minimal Calibre-Web client: log in, then upload book files."""

    def __init__(self, base_url: str, username: str, password: str, timeout: int = 60):
        self._base = base_url.rstrip("/")
        self._user = username
        self._pass = password
        self._timeout = timeout
        self._csrf = ""
        self._jar = http.cookiejar.CookieJar()
        self._opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self._jar))

    def login(self) -> bool:
        """Establish a session and confirm it actually authenticated. Calibre-Web re-serves
        the login form with 200 on bad credentials, so a 2xx alone is not success."""
        page = self._opener.open(f"{self._base}/login", timeout=self._timeout).read().decode("utf-8", "replace")
        match = _CSRF_RE.search(page)
        if not match:
            return False
        self._csrf = match.group(1)
        form = urllib.parse.urlencode({
            "csrf_token": self._csrf,
            "username": self._user,
            "password": self._pass,
            "remember_me": "on",
        }).encode("utf-8")
        req = urllib.request.Request(
            f"{self._base}/login", data=form,
            headers={"Content-Type": "application/x-www-form-urlencoded", "Referer": f"{self._base}/login"},
        )
        try:
            with self._opener.open(req, timeout=self._timeout) as resp:
                final_url = resp.geturl()
                body = resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError:
            return False
        # A successful login (remember_me=on) sets a remember_token cookie and redirects off
        # /login; a failure re-renders the login form (password field present).
        if any(c.name == "remember_token" for c in self._jar):
            return True
        return not final_url.rstrip("/").endswith("/login") and 'name="password"' not in body

    def upload(self, file_path: str, filename: str) -> bool:
        """POST one book file to /upload as multipart, streaming the file. Returns True on a
        2xx/redirect."""
        name = safe_upload_name(filename)
        boundary = "----conexcore" + _uuid.uuid4().hex
        prefix, suffix = _multipart_frame(boundary, {"csrf_token": self._csrf}, "btn-upload", name)
        length = len(prefix) + os.path.getsize(file_path) + len(suffix)
        body = _ConcatStream(prefix, file_path, suffix)
        req = urllib.request.Request(
            f"{self._base}/upload", data=body,
            headers={
                "Content-Type": f"multipart/form-data; boundary={boundary}",
                "Content-Length": str(length),
                "Referer": f"{self._base}/",
            },
        )
        try:
            with self._opener.open(req, timeout=self._timeout) as resp:
                return 200 <= resp.status < 400
        except urllib.error.HTTPError as exc:
            return 200 <= exc.code < 400
        finally:
            body.close()

    def remove(self, book_id: int) -> bool:
        """POST /delete/{id} to remove a book (calibredb is absent), exactly as dash-node does
        (sockets/books.query.ts removeBook): a logged-in session + CSRF token. ``book_id`` is
        the Calibre-Web library id (integer), not the book uuid. Returns True on a 2xx/redirect.
        Deleting the row also drops its FK rows (data / authors / identifiers) and its files,
        so Calibre-Web stays the one owner of its library."""
        form = urllib.parse.urlencode({"csrf_token": self._csrf}).encode("utf-8")
        req = urllib.request.Request(
            f"{self._base}/delete/{book_id}", data=form,
            headers={
                "Content-Type": "application/x-www-form-urlencoded",
                "Referer": f"{self._base}/",
            },
        )
        try:
            with self._opener.open(req, timeout=self._timeout) as resp:
                return 200 <= resp.status < 400
        except urllib.error.HTTPError as exc:
            return 200 <= exc.code < 400
