// cx-proxy.ts - K2GO-427
//
// Thin relay from dash-node to the content-exchange core (cx-core), a separate in-box
// REST service on 127.0.0.1:9500. The app reaches dash-node (its BFF); dash-node forwards
// /v1/* to the core and streams the answer back. dash-node is a relay here, NOT a second
// brain: it owns none of the content state (one owner per fact, ADR Decision 1 / D1b). No
// proxy library: Node's core http is enough for a localhost passthrough.
import { Request, Response } from 'express';
import http from 'http';

const CX_HOST = '127.0.0.1';

// The core's port, env-overridable to match its own CX_PORT default (9500).
function cxPort(): number {
    return Number(process.env.CX_PORT) || 9500;
}

// Forward one request to the core and pipe the response back. Mounted at '/v1', so req.url
// is the path after it (e.g. '/apps', '/export/item/kiwix/foo.zim?x=1'), query included.
// The streaming endpoint (GET /v1/export/item) returns chunked; piping handles it.
export function cxProxy(req: Request, res: Response): void {
    const corePath = '/v1' + req.url;

    // express.json() already parsed any JSON body upstream of this handler, so re-serialize
    // it. The cx-core contract carries only small JSON bodies (export/plan, import/validate,
    // import/item with a payloadPath); item payloads never pass through dash-node.
    const isWrite = req.method === 'POST' || req.method === 'PUT' || req.method === 'PATCH';
    const hasBody = isWrite && req.body && Object.keys(req.body).length > 0;
    const payload = hasBody ? Buffer.from(JSON.stringify(req.body)) : null;

    const headers: Record<string, string> = {};
    if (payload) {
        headers['content-type'] = 'application/json';
        headers['content-length'] = String(payload.length);
    }

    const upstream = http.request(
        { host: CX_HOST, port: cxPort(), method: req.method, path: corePath, headers },
        (up) => {
            res.status(up.statusCode || 502);
            // Pass the content type through; let Node frame the body (length or chunked) so a
            // streamed export is relayed as-is.
            const ct = up.headers['content-type'];
            if (ct) res.setHeader('content-type', ct);
            up.pipe(res);
        },
    );

    upstream.on('error', () => {
        if (!res.headersSent) res.status(502).json({ error: 'cx-core unreachable' });
        else res.end();
    });

    if (payload) upstream.write(payload);
    upstream.end();
}
