"""Pure request routing for the /v1 API. Kept free of the HTTP server so the route logic
is unit-testable without a socket: given a registry, a method, a path and (for POST) a
parsed body, it returns (status, payload). The outer contract (ADR) maps each route 1:1 to
an adapter operation. A read route's payload is JSON; the export-item route's payload is an
ExportStream the server streams as octets.
"""

from __future__ import annotations

from typing import Any, List, Optional, Tuple
from urllib.parse import unquote

from .export import disk_total_free, has_room
from .importing import is_within_dir
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


def _dedup_check(registry: Registry, body: Any) -> Response:
    """Per item {app, verdict, idKind}. Runs on the receiver; moves nothing."""
    items = body.get("items") if isinstance(body, dict) else None
    if not isinstance(items, list):
        return 400, {"error": "body must be {items: [{app, dedup}]}"}
    results = []
    for req in items:
        app = req.get("app") if isinstance(req, dict) else None
        dedup = req.get("dedup") if isinstance(req, dict) else None
        if not registry.has(app) or not isinstance(dedup, dict):
            results.append({"app": app, "verdict": "NO_APP", "idKind": None})
            continue
        adapter = registry.get(app)
        results.append({"app": app, "verdict": adapter.check(dedup), "idKind": adapter.id_kind})
    return 200, {"results": results}


def _import_validate(registry: Registry, body: Any) -> Response:
    """Per item: importable + dedup verdict, or NO_APP. Nothing moved. Partial is first-class."""
    items = body.get("items") if isinstance(body, dict) else None
    if not isinstance(items, list):
        return 400, {"error": "manifest must be {items: [...]}"}
    results = []
    for entry in items:
        app = entry.get("app") if isinstance(entry, dict) else None
        local_ref = (entry.get("identity") or {}).get("localRef") if isinstance(entry, dict) else None
        if not registry.has(app):
            results.append({"app": app, "localRef": local_ref, "status": "NO_APP"})
            continue
        adapter = registry.get(app)
        dedup = (entry.get("identity") or {}).get("dedup") or {}
        results.append({
            "app": app, "localRef": local_ref, "status": "importable",
            "verdict": adapter.check(dedup), "idKind": adapter.id_kind,
        })
    return 200, {"results": results}


def _import_item(registry: Registry, body: Any) -> Response:
    """Ingest one already-local item archive. The front placed payloadPath on a box-visible
    path inside the import area and drives the per-item sequence."""
    if not isinstance(body, dict):
        return 400, {"error": "body must be {app, entry, payloadPath}"}
    app = body.get("app")
    entry = body.get("entry")
    payload_path = body.get("payloadPath")
    if not registry.has(app):
        return 404, {"error": f"unknown app: {app}"}
    if not isinstance(entry, dict) or not isinstance(payload_path, str) or not payload_path:
        return 400, {"error": "entry and payloadPath are required"}
    # Containment: the payload must live inside the import area, never an arbitrary path.
    if not is_within_dir(registry.import_root, payload_path):
        return 400, {"error": "payloadPath is outside the import area"}
    # Preflight the staging disk against the declared item size, so a large import fails
    # cleanly here rather than ENOSPC mid-extract (the box checks its own disk).
    declared = entry.get("bytes")
    if isinstance(declared, int) and declared > 0 and not has_room(registry.import_root, declared):
        return 507, {"error": "insufficient space to import"}
    result = registry.get(app).import_item(entry, payload_path)
    return (200, result) if result.get("ok") else (422, result)


def _delete_item(registry: Registry, app: str, local_ref: str) -> Response:
    """Delete one item, delegating to the app's own delete. 404 when the item is absent, so a
    repeated delete is idempotent-safe. The destructive confirm is the front's (D1c); the core
    only executes."""
    if not registry.has(app):
        return 404, {"error": f"unknown app: {app}"}
    adapter = registry.get(app)
    if adapter.get(local_ref) is None:
        return 404, {"error": "item not found"}
    result = adapter.delete(local_ref)
    return (200, result) if result.get("ok") else (422, result)


def _storage(registry: Registry) -> Response:
    """Box storage summary: the content volume's total / free disk, plus how much of it the
    loaded adapters account for (per app and in total). The PHONE's own free disk is app-side
    (StatFs), not here."""
    total, free = disk_total_free(registry.content_volume)
    per_app: List[dict] = []
    used = 0
    for adapter in registry.adapters():
        app_bytes = sum(item.bytes for item in adapter.list())
        per_app.append({"app": adapter.app, "bytes": app_bytes})
        used += app_bytes
    return 200, {
        "volume": registry.content_volume,
        "totalBytes": total,
        "freeBytes": free,
        "usedByContentBytes": used,
        "perApp": per_app,
    }


def dispatch(registry: Registry, method: str, path: str, query: dict, body: Any = None) -> Response:
    """Route one request. ``query`` is a dict of first values; ``body`` is the parsed JSON
    for a POST."""
    trimmed = path.rstrip("/") or "/"
    segments = [unquote(s) for s in trimmed.split("/") if s != ""]

    if method == "POST":
        # POST /v1/export/plan : plan an export (sizes only, no payload moved).
        if segments == ["v1", "export", "plan"]:
            return _export_plan(registry, body)
        # POST /v1/dedup/check : receiver dedup verdicts, nothing moved.
        if segments == ["v1", "dedup", "check"]:
            return _dedup_check(registry, body)
        # POST /v1/import/validate : what will import and what is skipped, nothing moved.
        if segments == ["v1", "import", "validate"]:
            return _import_validate(registry, body)
        # POST /v1/import/item : ingest one already-local item archive.
        if segments == ["v1", "import", "item"]:
            return _import_item(registry, body)
        return 404, {"error": "not found"}

    if method == "DELETE":
        # DELETE /v1/content/{app}/{localRef} : reclaim space by removing one item.
        if len(segments) == 4 and segments[0] == "v1" and segments[1] == "content":
            return _delete_item(registry, segments[2], segments[3])
        return 405, {"error": "method not allowed"}

    if method != "GET":
        return 405, {"error": "method not allowed"}

    # GET /v1/apps : installed apps that have a loaded adapter.
    if segments == ["v1", "apps"]:
        return 200, {"apps": registry.apps()}

    # GET /v1/storage : box content-volume disk summary + per-app content totals.
    if segments == ["v1", "storage"]:
        return _storage(registry)

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
