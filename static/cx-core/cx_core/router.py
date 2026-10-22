"""Pure request routing for the /v1 read API. Kept free of the HTTP server so the route
logic is unit-testable without a socket: given a registry, a method and a path, it
returns (status, payload). The outer contract (ADR) maps each route 1:1 to an adapter
operation; this slice is the read side only.
"""

from __future__ import annotations

from typing import Any, List, Optional, Tuple
from urllib.parse import unquote

from .registry import Registry

Response = Tuple[int, Any]


def _content_rows(registry: Registry, only_app: Optional[str]) -> Response:
    if only_app is not None and not registry.has(only_app):
        return 404, {"error": f"unknown app: {only_app}"}
    adapters = [registry.get(only_app)] if only_app else registry.adapters()
    items: List[dict] = []
    for adapter in adapters:
        items.extend(item.to_json() for item in adapter.list())
    return 200, {"items": items}


def dispatch(registry: Registry, method: str, path: str, query: dict) -> Response:
    """Route one request. ``query`` is a dict of first values (e.g. {"app": "kiwix"})."""
    if method != "GET":
        return 405, {"error": "method not allowed"}

    # Normalize: drop the trailing slash (but keep the root) and split.
    trimmed = path.rstrip("/") or "/"
    segments = [unquote(s) for s in trimmed.split("/") if s != ""]

    # GET /v1/apps : installed apps that have a loaded adapter.
    if segments == ["v1", "apps"]:
        return 200, {"apps": registry.apps()}

    # GET /v1/content[?app=] : everything this box has, optionally filtered by app.
    if segments == ["v1", "content"]:
        return _content_rows(registry, query.get("app"))

    # GET /v1/content/{app}/{localRef} : one item detail.
    if len(segments) == 4 and segments[0] == "v1" and segments[1] == "content":
        app, local_ref = segments[2], segments[3]
        if not registry.has(app):
            return 404, {"error": f"unknown app: {app}"}
        item = registry.get(app).get(local_ref)
        if item is None:
            return 404, {"error": "item not found"}
        return 200, item.to_json()

    return 404, {"error": "not found"}
