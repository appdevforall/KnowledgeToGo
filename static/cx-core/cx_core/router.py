"""Pure request routing for the /v1 API. Kept free of the HTTP server so the route logic
is unit-testable without a socket: given a registry, a method, a path and (for POST) a
parsed body, it returns (status, payload). The outer contract (ADR) maps each route 1:1 to
an adapter operation. A read route's payload is JSON; the export-item route's payload is an
ExportStream the server streams as octets.
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


def _export_plan(registry: Registry, body: Any) -> Response:
    """Return the manifest entries + exact bytes for the requested items, moving NO payload,
    so the front can preflight the phone disk and let the user pick before any byte moves.
    Partial is first-class: items that cannot be planned are reported, not fatal."""
    items = body.get("items") if isinstance(body, dict) else None
    if not isinstance(items, list):
        return 400, {"error": "body must be {items: [{app, localRef}]}"}
    found: List[dict] = []
    missing: List[dict] = []
    total = 0
    for req in items:
        app = req.get("app") if isinstance(req, dict) else None
        local_ref = req.get("localRef") if isinstance(req, dict) else None
        if not registry.has(app):
            missing.append({"app": app, "localRef": local_ref, "reason": "unknown app"})
            continue
        item = registry.get(app).get(local_ref) if local_ref else None
        if item is None:
            missing.append({"app": app, "localRef": local_ref, "reason": "not found"})
            continue
        found.append(item.to_json())
        total += item.bytes
    return 200, {"items": found, "missing": missing, "totalBytes": total}


def dispatch(registry: Registry, method: str, path: str, query: dict, body: Any = None) -> Response:
    """Route one request. ``query`` is a dict of first values; ``body`` is the parsed JSON
    for a POST."""
    trimmed = path.rstrip("/") or "/"
    segments = [unquote(s) for s in trimmed.split("/") if s != ""]

    if method == "POST":
        # POST /v1/export/plan : plan an export (sizes only, no payload moved).
        if segments == ["v1", "export", "plan"]:
            return _export_plan(registry, body)
        return 404, {"error": "not found"}

    if method != "GET":
        return 405, {"error": "method not allowed"}

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

    # GET /v1/export/item/{app}/{localRef} : stream that one item's archive (payload is an
    # ExportStream; the server streams it). The front moves the bytes and owns the sequence.
    if len(segments) == 5 and segments[0] == "v1" and segments[1] == "export" and segments[2] == "item":
        app, local_ref = segments[3], segments[4]
        if not registry.has(app):
            return 404, {"error": f"unknown app: {app}"}
        stream = registry.get(app).open_export(local_ref)
        if stream is None:
            return 404, {"error": "item not found or not exportable"}
        return 200, stream

    return 404, {"error": "not found"}
