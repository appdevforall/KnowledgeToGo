/// <reference types="node" />
// Router tests over real HTTP (node:http): the guards, the joins, the live channel.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'http';
import { AddressInfo } from 'net';
import express from 'express';
import { ClassRoom, CLASS_GAP_MS, JOIN_LIMIT } from './class';
import { createSchoolRouter, cookieToken, schoolErrors, TOKEN_COOKIE } from './router';

interface Box { port: number; close: () => Promise<void> }

async function startBox(room = new ClassRoom()): Promise<Box> {
    const app = express();
    app.use(express.json({ limit: '4kb' }));
    app.use('/school', createSchoolRouter(room));
    app.use('/school', schoolErrors);
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
function teacherPage(box: Box, lang = ''): Promise<http.ClientRequest> {
    return new Promise((ok) => {
        const req = http.request({ host: '127.0.0.1', port: box.port, path: `/school/teacher/live?role=page${lang ? `&lang=${lang}` : ''}`, headers: { 'X-Real-IP': '127.0.0.1' } },
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
        for (const path of ['/school/teacher/publish', '/school/TEACHER/publish', '/school/teacher/end', '/school/teacher/marks']) {
            assert.equal((await request(box, 'POST', path, { json: { url: '/p' } })).status, 404, path);
        }
        assert.equal((await request(box, 'GET', '/school/teacher/live?role=page')).status, 404);
        assert.equal((await request(box, 'POST', '/school/teacher/end', { ip: '127.0.0.1' })).status, 204);
    } finally { await box.close(); }
});

test('marks: a teacher channel gets an empty list when the box has no lesson (a restart)', async () => {
    const box = await startBox();
    try {
        const teacher = await firstEvent(box, '/school/teacher/live', 'message',
            { ip: '127.0.0.1', want: (d) => (d as { type: string }).type === 'marks' });
        assert.deepEqual(teacher.data, { type: 'marks', url: null, list: [] });
    } finally { await box.close(); }
});

test('exit: a browser goes to the library of the box', async () => {
    const box = await startBox();
    try {
        const r = await request(box, 'GET', '/school/exit', {});
        assert.equal(r.status, 303);
        assert.match(r.body, /Redirecting to \/$/);
    } finally { await box.close(); }
});

test('marks: the teacher marks; a late student and a teacher page get them; a new page clears them', async () => {
    const box = await startBox();
    const local = { ip: '127.0.0.1' };
    const marks = (json: unknown) => request(box, 'POST', '/school/teacher/marks', { ...local, json });
    try {
        const page = await teacherPage(box);
        const mark = { k: 'b', b: 2, t: 'x1' };
        assert.equal((await marks({ op: 'add', url: '/p', mark })).status, 409);   // no lesson yet
        assert.equal((await request(box, 'POST', '/school/teacher/publish', { ...local, json: { url: '/p' } })).status, 204);
        assert.equal((await marks({ op: 'add', url: '/p', mark: { k: 'b' } })).status, 400);
        assert.equal((await marks({ op: 'paint', url: '/p' })).status, 400);
        const long = await marks({ op: 'add', url: '/p', mark: { k: 'p', b: 0, t: 'a', s: 0, eb: 40, et: 'a', e: 1 } });
        assert.deepEqual([long.status, JSON.parse(long.body)], [400, { error: 'long', max: 20 }]);
        assert.equal((await marks({ op: 'add', url: '/p', mark })).status, 204);
        const j = JSON.parse((await request(box, 'POST', '/school/join', { json: {} })).body) as { token: string };
        const late = await firstEvent(box, '/school/live', 'marks', { cookie: cookieOf(j.token) });
        assert.deepEqual(late.data, { url: '/p', list: [{ ...mark, id: 1 }] });
        const teacher = await firstEvent(box, '/school/teacher/live', 'message',
            { ip: '127.0.0.1', want: (d) => (d as { type: string }).type === 'marks' });
        assert.deepEqual(teacher.data, { type: 'marks', url: '/p', list: [{ ...mark, id: 1 }] });
        // A remove that changes nothing still answers the teacher pages with the list of the box.
        const again = firstEvent(box, '/school/teacher/live', 'message',
            { ip: '127.0.0.1', want: (d) => (d as { type: string; list?: unknown[] }).type === 'marks' && (d as { list: unknown[] }).list.length === 1 });
        assert.equal((await marks({ op: 'remove', url: '/p', id: 99 })).status, 204);
        assert.equal(((await again).data as { list: unknown[] }).list.length, 1);
        // A move: the mark keeps its id; the new place is checked as an add.
        const box1 = { k: 'r', b: 0, t: 'a', y: 0, eb: 1, et: 'b', ey: 20, x: 10, w: 40 };
        assert.equal((await marks({ op: 'add', url: '/p', mark: box1 })).status, 204);
        assert.equal((await marks({ op: 'move', url: '/p', id: 2, mark: { k: 'r' } })).status, 400);
        const movedEv = firstEvent(box, '/school/live', 'marks',
            { cookie: cookieOf(j.token), want: (d) => (d as { list: { x?: number }[] }).list.some((m) => m.x === 90) });
        await new Promise((ok) => setTimeout(ok, 100));
        assert.equal((await marks({ op: 'move', url: '/p', id: 2, mark: { ...box1, x: 90 } })).status, 204);
        assert.deepEqual(((await movedEv).data as { list: unknown[] }).list[1], { ...box1, x: 90, id: 2 });
        // A student on the channel hears the clear of a new page.
        const cleared = firstEvent(box, '/school/live', 'marks',
            { cookie: cookieOf(j.token), want: (d) => (d as { url: string }).url === '/q' });
        await new Promise((ok) => setTimeout(ok, 100));
        assert.equal((await request(box, 'POST', '/school/teacher/publish', { ...local, json: { url: '/q' } })).status, 204);
        assert.deepEqual((await cleared).data, { url: '/q', list: [] });
        page.destroy();
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

// An SSE channel that stays open; seen() resolves when an event of that name came.
function listen(box: Box, path: string, cookie: string): { seen: (name: string) => Promise<void>; close: () => void } {
    const names: string[] = [];
    const waiters: { name: string; ok: () => void }[] = [];
    const req = http.request({ host: '127.0.0.1', port: box.port, path, headers: { 'X-Real-IP': '192.168.1.40', Cookie: cookie } }, (res) => {
        let buf = '';
        res.setEncoding('utf8');
        res.on('data', (c: string) => {
            buf += c;
            const blocks = buf.split('\n\n');
            buf = blocks.pop() ?? '';
            for (const b of blocks) {
                const name = /^event: (.*)$/m.exec(b)?.[1] ?? 'message';
                names.push(name);
                waiters.filter((w) => w.name === name).forEach((w) => w.ok());
            }
        });
        res.on('error', () => { /* closed */ });
    });
    req.on('error', () => { /* closed by the test or the box */ });
    req.end();
    return {
        // A missing event fails the test after 5 s: it does not hang (the channel keeps the box open).
        seen: (name) => (names.includes(name) ? Promise.resolve() : new Promise((ok, fail) => {
            const timer = setTimeout(() => fail(new Error(`no "${name}" event`)), 5000);
            waiters.push({ name, ok: () => { clearTimeout(timer); ok(); } });
        })),
        close: () => { req.destroy(); },
    };
}

test('a teacher page that comes back after the class gap ends the old class first (review M1)', async () => {
    let t = 0;
    let n = 0;
    const room = new ClassRoom(50, undefined, () => t, () => `C${++n}A`);
    const box = await startBox(room);
    try {
        const page = await teacherPage(box);
        const j = JSON.parse((await request(box, 'POST', '/school/join', { json: {} })).body) as { token: string };
        const student = listen(box, '/school/live', cookieOf(j.token));
        await student.seen('self');
        page.destroy();
        // The box sees the close a moment later: move the clock past the gap until it counts.
        for (let i = 0; i < 100 && !room.lapsed(); i += 1) {
            await new Promise((ok) => setTimeout(ok, 10));
            t += CLASS_GAP_MS + 1;
        }
        assert.equal(room.lapsed(), true);
        const again = await teacherPage(box);           // before the ping round
        await student.seen('ended');
        assert.deepEqual(room.state(), { open: true, code: 'C2A' });
        assert.equal((await request(box, 'POST', '/school/ack', { json: { token: j.token } })).status, 401);
        student.close();
        again.destroy();
    } finally { await box.close(); }
});

test('live: an unknown token gets 401 on the channel and on an ack', async () => {
    const box = await startBox();
    try {
        const page = await teacherPage(box);
        assert.equal((await firstEvent(box, '/school/live', 'self', { cookie: cookieOf('0'.repeat(32)) })).status, 401);
        assert.equal((await firstEvent(box, '/school/live', 'self')).status, 401);   // no cookie
        assert.equal((await request(box, 'POST', '/school/ack', { json: { token: '0'.repeat(32) } })).status, 401);
        page.destroy();
    } finally { await box.close(); }
});

test('live: a second channel with the same token tells the first one "replaced"', async () => {
    const box = await startBox();
    try {
        const page = await teacherPage(box);
        const s = JSON.parse((await request(box, 'POST', '/school/join', { json: {} })).body) as { token: string };
        const first = listen(box, '/school/live', cookieOf(s.token));
        await first.seen('self');
        const second = listen(box, '/school/live', cookieOf(s.token));
        await second.seen('self');
        await first.seen('replaced');
        first.close();
        second.close();
        page.destroy();
    } finally { await box.close(); }
});

test('ack: Done goes to the roster of the teacher; the ack names the seat, not the token', async () => {
    const box = await startBox();
    try {
        const page = await teacherPage(box);
        const s = JSON.parse((await request(box, 'POST', '/school/join', { json: {} })).body) as { token: string; seat: number };
        const ack = firstEvent(box, '/school/teacher/live', 'message',
            { ip: '127.0.0.1', want: (d) => (d as { type?: string }).type === 'ack' });
        await new Promise((ok) => setTimeout(ok, 50));   // the teacher channel is open
        assert.equal((await request(box, 'POST', '/school/ack', { json: { token: s.token, seq: 7, done: true } })).status, 204);
        assert.deepEqual((await ack).data, { type: 'ack', seat: s.seat, seq: 7, match: true });
        const roster = await firstEvent(box, '/school/teacher/live', 'message',
            { ip: '127.0.0.1', want: (d) => (d as { type?: string }).type === 'roster' });
        assert.deepEqual((roster.data as { students: unknown[] }).students, [{ seat: s.seat, online: false, done: true, quiet: false }]);
        page.destroy();
    } finally { await box.close(); }
});

test('class language: the join answer and "self" carry the language of the teacher page', async () => {
    const box = await startBox();
    try {
        const page = await teacherPage(box, 'es');
        const j = JSON.parse((await request(box, 'POST', '/school/join', { json: {} })).body) as { token: string; lang: string };
        assert.equal(j.lang, 'es');
        const self = await firstEvent(box, '/school/live', 'self', { cookie: cookieOf(j.token) });
        assert.equal((self.data as { lang: string }).lang, 'es');
        page.destroy();
    } finally { await box.close(); }
});

test('a malformed JSON body gets 400 with a short answer, no stack trace (review L1)', async () => {
    const box = await startBox();
    try {
        const r = await new Promise<Answer>((ok, fail) => {
            const req = http.request({ host: '127.0.0.1', port: box.port, method: 'POST', path: '/school/join',
                headers: { 'Content-Type': 'application/json', 'X-Real-IP': '192.168.1.90' } }, (res) => {
                let body = '';
                res.setEncoding('utf8');
                res.on('data', (c: string) => { body += c; });
                res.on('end', () => ok({ status: res.statusCode ?? 0, body }));
            });
            req.on('error', fail);
            req.end('{bad');
        });
        assert.equal(r.status, 400);
        assert.equal(r.body, '{"error":"bad request"}');
    } finally { await box.close(); }
});
