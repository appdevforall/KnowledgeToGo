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
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid as _uuid
from typing import Tuple

_CSRF_RE = re.compile(r'name="csrf_token"[^>]*value="([^"]+)"')


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


class CalibreWebClient:
    """A minimal Calibre-Web client: log in, then upload book files."""

    def __init__(self, base_url: str, username: str, password: str, timeout: int = 60):
        self._base = base_url.rstrip("/")
        self._user = username
        self._pass = password
        self._timeout = timeout
        self._csrf = ""
        self._opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        )

    def login(self) -> bool:
        """Establish a session. Returns True when the login form was accepted."""
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
        with self._opener.open(req, timeout=self._timeout) as resp:
            return resp.status in (200, 302, 303)

    def upload(self, file_path: str, filename: str) -> bool:
        """POST one book file to /upload as multipart. Returns True on a 2xx/redirect."""
        with open(file_path, "rb") as handle:
            file_bytes = handle.read()
        boundary = "----cxcore" + _uuid.uuid4().hex
        body = _multipart(boundary, {"csrf_token": self._csrf}, "btn-upload", filename, file_bytes)
        req = urllib.request.Request(
            f"{self._base}/upload", data=body,
            headers={
                "Content-Type": f"multipart/form-data; boundary={boundary}",
                "Referer": f"{self._base}/",
            },
        )
        try:
            with self._opener.open(req, timeout=self._timeout) as resp:
                return 200 <= resp.status < 400
        except urllib.error.HTTPError as exc:
            return 200 <= exc.code < 400


def _multipart(boundary: str, fields: dict, file_field: str, filename: str, content: bytes) -> bytes:
    sep = f"--{boundary}".encode()
    parts = []
    for name, value in fields.items():
        parts += [sep, f'Content-Disposition: form-data; name="{name}"'.encode(), b"", str(value).encode()]
    parts += [
        sep,
        f'Content-Disposition: form-data; name="{file_field}"; filename="{filename}"'.encode(),
        b"Content-Type: application/octet-stream",
        b"",
        content,
    ]
    parts += [f"--{boundary}--".encode(), b""]
    return b"\r\n".join(parts)
