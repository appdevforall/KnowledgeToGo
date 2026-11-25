/// <reference types="node" />
// Router tests over real HTTP (node:http): the guards, the joins, the live channel.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'http';
import { AddressInfo } from 'net';
import express from 'express';
import { ClassRoom, JOIN_LIMIT } from './class';
import { createSchoolRouter, cookieToken, TOKEN_COOKIE } from './router';

interface Box { port: number; close: () => Promise<void> }

async function startBox(room = new ClassRoom()): Promise<Box> {
    const app = express();
    app.use(express.json({ limit: '4kb' }));
    app.use('/school', createSchoolRouter(room));
    const server = http.createServer(app);
    await new Promise<void>((ok) => server.listen(0, '127.0.0.1', ok));
    return {
        port: (server.address() as AddressInfo).port,
        close: () => new Promise<void>((ok) => { server.closeAllConnections(); server.close(() => ok()); }),
    };
}

interface Answer { status: number; body: string }

function request(box: Box, method: string, path: string, opts: { ip?: string; json?: unknown; cookie?: string } = {}): Promise<Answer> {
    return new Promise((ok, fail) => {
        const headers: Record<string, string> = { 'X-Real-IP': opts.ip ?? '192.168.1.20' };
        const data = opts.json === undefined ? undefined : JSON.stringify(opts.json);
        if (data) headers['Content-Type'] = 'application/json';
        if (opts.cookie) headers.Cookie = opts.cookie;
        const req = http.request({ host: '127.0.0.1', port: box.port, method, path, headers }, (res) => {
            let body = '';
            res.setEncoding('utf8');
            res.on('data', (c: string) => { body += c; });
            res.on('end', () => ok({ status: res.statusCode ?? 0, body }));
        });
        req.on('error', fail);
        req.end(data);
    });
}

// Opens an SSE channel and resolves with the first event of the given name, then closes it.
function firstEvent(box: Box, path: string, event: string, opts: { ip?: string; cookie?: string; want?: (d: unknown) => boolean } = {}): Promise<{ status: number; data?: unknown }> {
    return new Promise((ok, fail) => {
        const headers: Record<string, string> = { 'X-Real-IP': opts.ip ?? '192.168.1.20' };
        if (opts.cookie) headers.Cookie = opts.cookie;
        const req = http.request({ host: '127.0.0.1', port: box.port, path, headers }, (res) => {
            if (res.statusCode !== 200) { res.resume(); ok({ status: res.statusCode ?? 0 }); return; }
            let buf = '';
            res.setEncoding('utf8');
            res.on('data', (c: string) => {
                buf += c;
                for (const block of buf.split('\n\n').slice(0, -1)) {
                    const name = /^event: (.*)$/m.exec(block)?.[1] ?? 'message';
                    const data = /^data: (.*)$/m.exec(block)?.[1];
                    if (name === event && data !== undefined && (!opts.want || opts.want(JSON.parse(data)))) {
                        req.destroy();
                        ok({ status: 200, data: JSON.parse(data) });
                        return;
                    }
                }
            });
        });
        req.on('error', (e) => { if ((e as NodeJS.ErrnoException).code !== 'ECONNRESET') fail(e); });
        req.end();
    });
}

// A teacher page channel that stays open (the class is open while it lives).
function teacherPage(box: Box): Promise<http.ClientRequest> {
    return new Promise((ok) => {
        const req = http.request({ host: '127.0.0.1', port: box.port, path: '/school/teacher/live?role=page', headers: { 'X-Real-IP': '127.0.0.1' } },
            (res) => { res.once('data', () => ok(req)); });
        req.on('error', () => { /* closed by the test */ });
        req.end();
    });
}

const cookieOf = (token: string): string => `other=1; ${TOKEN_COOKIE}=${token}`;

test('cookieToken: reads the class cookie among others', () => {
    assert.equal(cookieToken('a=1; k2go-school=abc; b=2'), 'abc');
    assert.equal(cookieToken('k2go-schoolx=abc'), undefined);
    assert.equal(cookieToken(undefined), undefined);
});

test('teacher routes answer only localhost', async () => {
    const box = await startBox();
    try {
        for (const path of ['/school/teacher/publish', '/school/TEACHER/publish', '/school/teacher/end']) {
            assert.equal((await request(box, 'POST', path, { json: { url: '/p' } })).status, 404, path);
        }
        assert.equal((await request(box, 'GET', '/school/teacher/live?role=page')).status, 404);
        assert.equal((await request(box, 'POST', '/school/teacher/end', { ip: '127.0.0.1' })).status, 204);
    } finally { await box.close(); }
});

test('join: refused while no class is open and after End class; the page code must match', async () => {
    const box = await startBox();
    try {
        let r = await request(box, 'POST', '/school/join', { json: {} });
        assert.equal(r.status, 409);
        const page = await teacherPage(box);
        r = await request(box, 'POST', '/school/join', { json: {} });
        assert.equal(r.status, 201);
        const j = JSON.parse(r.body) as { token: string; seat: number; code: string; name?: string };
        assert.equal(j.seat, 1);
        assert.match(j.code, /^[2-9A-HJ-NP-Z]{4}$/);
        assert.equal(j.name, undefined);
        assert.equal((await request(box, 'POST', '/school/join', { ip: '192.168.1.21', json: { code: 'ZZZZ' } })).status, 409);
        assert.equal((await request(box, 'POST', '/school/teacher/end', { ip: '127.0.0.1' })).status, 204);
        assert.equal((await request(box, 'POST', '/school/join', { ip: '192.168.1.22', json: {} })).status, 409);
        assert.equal((await request(box, 'POST', '/school/join', { ip: '192.168.1.22', json: { code: j.code } })).status, 409);
        assert.equal((await request(box, 'GET', '/school/state')).body, '{"open":false,"code":null}');
        // The token of the ended class stops working.
        assert.equal((await request(box, 'POST', '/school/ack', { json: { token: j.token } })).status, 401);
        page.destroy();
    } finally { await box.close(); }
});

test('join: a client address gets at most JOIN_LIMIT joins; another address is not stopped', async () => {
    const box = await startBox();
    try {
        const page = await teacherPage(box);
        for (let i = 0; i < JOIN_LIMIT; i += 1) {
            assert.equal((await request(box, 'POST', '/school/join', { ip: '192.168.1.66', json: {} })).status, 201);
        }
        const r = await request(box, 'POST', '/school/join', { ip: '192.168.1.66', json: {} });
        assert.equal(r.status, 429);
        assert.equal((await request(box, 'POST', '/school/join', { ip: '192.168.1.67', json: {} })).status, 201);
        page.destroy();
    } finally { await box.close(); }
});

test('live: the token comes from the cookie, the seat stays the same over a reconnect', async () => {
    const box = await startBox();
    try {
        const page = await teacherPage(box);
        const a = JSON.parse((await request(box, 'POST', '/school/join', { ip: '192.168.1.30', json: {} })).body) as { token: string; seat: number };
        const b = JSON.parse((await request(box, 'POST', '/school/join', { ip: '192.168.1.31', json: {} })).body) as { token: string; seat: number };
        assert.deepEqual([a.seat, b.seat], [1, 2]);
        // No token in the URL: a query token is not read.
        assert.equal((await firstEvent(box, `/school/live?token=${b.token}`, 'self')).status, 401);
        const first = await firstEvent(box, '/school/live', 'self', { cookie: cookieOf(b.token) });
        assert.equal((first.data as { seat: number }).seat, 2);
        const again = await firstEvent(box, '/school/live', 'self', { cookie: cookieOf(b.token) });   // a reload
        assert.deepEqual(again.data, first.data);
        // The roster to the teacher has seats, no name and no token.
        const roster = await firstEvent(box, '/school/teacher/live', 'message',
            { ip: '127.0.0.1', want: (d) => (d as { type?: string }).type === 'roster' });
        const text = JSON.stringify(roster.data);
        assert.ok(!text.includes(b.token) && !text.includes('name'), text);
        page.destroy();
    } finally { await box.close(); }
});
