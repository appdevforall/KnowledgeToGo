/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'http';
import express from 'express';
import { cxProxy } from './cx-proxy';

// End-to-end over real sockets: a stub "core" plus a dash-node app mounting the proxy at
// '/v1', exactly as routes.ts does. This catches path, method, body and streaming relay
// (the pieces a mock would not).

function listen(server: http.Server): Promise<number> {
    return new Promise((resolve) => server.listen(0, '127.0.0.1', () => {
        resolve((server.address() as import('net').AddressInfo).port);
    }));
}

// A minimal HTTP client that returns status, headers and the raw body.
function get(port: number, path: string, method = 'GET', body?: string): Promise<{ status: number; body: string; headers: http.IncomingHttpHeaders }> {
    return new Promise((resolve, reject) => {
        const req = http.request({ host: '127.0.0.1', port, path, method,
            headers: body ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) } : {} },
            (res) => {
                let data = '';
                res.on('data', (c) => { data += c; });
                res.on('end', () => resolve({ status: res.statusCode || 0, body: data, headers: res.headers }));
            });
        req.on('error', reject);
        if (body) req.write(body);
        req.end();
    });
}

// Build the dash-node side: express.json() then the proxy at '/v1', mirroring server.ts.
async function startProxyApp(): Promise<{ port: number; close: () => void }> {
    const app = express();
    app.use(express.json());
    app.use('/v1', cxProxy);
    const server = http.createServer(app);
    const port = await listen(server);
    return { port, close: () => server.close() };
}

test('cxProxy relays a GET with path + query and the core status/body', async () => {
    const core = http.createServer((req, res) => {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ seen: req.url, method: req.method }));
    });
    const corePort = await listen(core);
    process.env.CX_PORT = String(corePort);
    const app = await startProxyApp();
    try {
        const r = await get(app.port, '/v1/content?app=kiwix');
        assert.equal(r.status, 200);
        const j = JSON.parse(r.body);
        // The core must see the full /v1 path and the query, not the mount-stripped path.
        assert.equal(j.seen, '/v1/content?app=kiwix');
        assert.equal(j.method, 'GET');
    } finally {
        app.close(); core.close();
    }
});

test('cxProxy forwards a POST JSON body to the core', async () => {
    const core = http.createServer((req, res) => {
        let data = '';
        req.on('data', (c) => { data += c; });
        req.on('end', () => {
            res.writeHead(200, { 'content-type': 'application/json' });
            res.end(JSON.stringify({ echo: JSON.parse(data || '{}'), path: req.url }));
        });
    });
    const corePort = await listen(core);
    process.env.CX_PORT = String(corePort);
    const app = await startProxyApp();
    try {
        const r = await get(app.port, '/v1/export/plan', 'POST', JSON.stringify({ items: ['a', 'b'] }));
        assert.equal(r.status, 200);
        const j = JSON.parse(r.body);
        assert.deepEqual(j.echo, { items: ['a', 'b'] });
        assert.equal(j.path, '/v1/export/plan');
    } finally {
        app.close(); core.close();
    }
});

test('cxProxy streams a chunked response intact (the export path)', async () => {
    const payload = 'Z'.repeat(50000);   // bigger than one write block
    const core = http.createServer((req, res) => {
        res.writeHead(200, { 'content-type': 'application/octet-stream' });   // no content-length -> chunked
        res.write(payload.slice(0, 25000));
        res.write(payload.slice(25000));
        res.end();
    });
    const corePort = await listen(core);
    process.env.CX_PORT = String(corePort);
    const app = await startProxyApp();
    try {
        const r = await get(app.port, '/v1/export/item/kiwix/foo.zim');
        assert.equal(r.status, 200);
        assert.equal(r.body.length, payload.length);
        assert.equal(r.body, payload);
    } finally {
        app.close(); core.close();
    }
});

test('cxProxy passes Content-Disposition through (the export filename)', async () => {
    const core = http.createServer((req, res) => {
        res.writeHead(200, {
            'content-type': 'application/octet-stream',
            'content-disposition': 'attachment; filename="foo.zim"',
        });
        res.end('zim-bytes');
    });
    const corePort = await listen(core);
    process.env.CX_PORT = String(corePort);
    const app = await startProxyApp();
    try {
        const r = await get(app.port, '/v1/export/item/kiwix/foo.zim');
        assert.equal(r.status, 200);
        assert.equal(r.headers['content-disposition'], 'attachment; filename="foo.zim"');
    } finally {
        app.close(); core.close();
    }
});

test('cxProxy returns 502 when the core is unreachable', async () => {
    // Point at a port with nothing listening.
    const dead = http.createServer();
    const deadPort = await listen(dead);
    dead.close();
    process.env.CX_PORT = String(deadPort);
    const app = await startProxyApp();
    try {
        const r = await get(app.port, '/v1/apps');
        assert.equal(r.status, 502);
    } finally {
        app.close();
    }
});
