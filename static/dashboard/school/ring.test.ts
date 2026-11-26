/// <reference types="node" />
// The setup ring: PIN proofs, tries, the teacher requests, and the routes over HTTP.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'http';
import { AddressInfo } from 'net';
import express from 'express';
import { ClassRoom } from './class';
import { mac, MAX_WRONG, MAX_WRONG_PER_ADDRESS, Ring, RING_MS } from './ring';
import { createSchoolRouter, schoolErrors } from './router';

const PIN = '4821';
const NONCE = 'nonce-abcdefghijkl';

test('the HMAC values are the same as in the app (RingProofTest.java)', () => {
    assert.equal(mac(PIN, 'k2go-ring-join', NONCE), '4be8e17e46082633c787512c730e1c78619490c61d21e1ad1e28c459e130d210');
    assert.equal(mac(PIN, 'k2go-ring-join', NONCE, 'KEYB64=='), 'e6b74741ce275da051025e8ce7a71204d38026f56a0b6acb2fb167679c3f0b1f');
    assert.equal(mac(PIN, 'k2go-ring-root', NONCE, 'ROOT.TOKEN'), 'd101bf72ecede3b5e55d4dde5029032db0f960430c0d90ac464b2702c529580e');
    assert.equal(mac(PIN, 'k2go-ring-cert', NONCE, 'ROOT.TOKEN', 'CERT.TOKEN'), '4334223e4f4339320550953e685f2538752e0a56aa539ba02248ff3a934d517c');
});

test('a student ring gives the root with a MAC of the PIN; a wrong PIN costs a try', () => {
    const r = new Ring();
    assert.ok(!r.open('student', '12345', 'ROOT', 0));
    assert.ok(r.open('student', PIN, 'ROOT.TOKEN', 0));
    const ok = r.join('10.0.0.2', { nonce: NONCE, proof: mac(PIN, 'k2go-ring-join', NONCE) }, 1);
    assert.equal(ok.status, 200);
    assert.deepEqual(ok.body, { root: 'ROOT.TOKEN', mac: mac(PIN, 'k2go-ring-root', NONCE, 'ROOT.TOKEN') });
    for (let i = 0; i < MAX_WRONG_PER_ADDRESS; i++) {
        assert.equal(r.join('10.0.0.3', { nonce: NONCE, proof: mac('0000', 'k2go-ring-join', NONCE) }, 2).status, 403);
    }
    // The address is out of tries, even with the right PIN; another address is not.
    assert.equal(r.join('10.0.0.3', { nonce: NONCE, proof: mac(PIN, 'k2go-ring-join', NONCE) }, 3).status, 429);
    assert.equal(r.join('10.0.0.4', { nonce: NONCE, proof: mac(PIN, 'k2go-ring-join', NONCE) }, 3).status, 200);
    assert.equal(r.join('10.0.0.4', { nonce: 'short', proof: 'x' }, 3).status, 400);
});

test('too many wrong PINs close the ring; the ring closes after its time', () => {
    const r = new Ring();
    r.open('student', PIN, 'ROOT.TOKEN', 0);
    for (let i = 0; i < MAX_WRONG; i++) r.join(`10.0.1.${i}`, { nonce: NONCE, proof: 'bad' }, 1);
    assert.equal(r.view(2).closedBy, 'guesses');
    assert.equal(r.join('10.0.2.1', { nonce: NONCE, proof: mac(PIN, 'k2go-ring-join', NONCE) }, 2).status, 404);
    r.open('student', PIN, 'ROOT.TOKEN', 0);
    assert.ok(r.view(RING_MS - 1).open);
    assert.equal(r.view(RING_MS).closedBy, 'time');
});

test('a teacher ring: request, approve, the phone gets the certificate with a MAC', () => {
    const r = new Ring();
    r.open('teacher', PIN, 'ROOT.TOKEN', 0);
    const key = 'KEYB64==';
    const join = r.join('10.0.0.5', { nonce: NONCE, key, proof: mac(PIN, 'k2go-ring-join', NONCE, key) }, 1);
    assert.equal(join.status, 202);
    const id = (join.body as { id: string }).id;
    // The same key again: the same request.
    assert.deepEqual(r.join('10.0.0.5', { nonce: NONCE, key, proof: mac(PIN, 'k2go-ring-join', NONCE, key) }, 1).body, { id });
    // A new join of the same key with a new nonce (after Cancel): the answer goes to the new nonce.
    const again = 'nonce-second-join-123';
    assert.deepEqual(r.join('10.0.0.5', { nonce: again, key, proof: mac(PIN, 'k2go-ring-join', again, key) }, 1).body, { id });
    assert.equal(r.result({ id, nonce: NONCE }, 2).status, 404);
    assert.equal(r.result({ id, nonce: again }, 2).status, 202);
    r.join('10.0.0.5', { nonce: NONCE, key, proof: mac(PIN, 'k2go-ring-join', NONCE, key) }, 1);
    // A proof without the key does not pass in a teacher ring.
    assert.equal(r.join('10.0.0.6', { nonce: NONCE, key, proof: mac(PIN, 'k2go-ring-join', NONCE) }, 1).status, 403);
    assert.equal(r.result({ id, nonce: NONCE }, 2).status, 202);
    assert.equal(r.result({ id, nonce: 'other-nonce-abcdefgh' }, 2).status, 404);
    assert.equal(r.view(2).requests.length, 1);
    assert.ok(r.answer(id, 'CERT.TOKEN', 3));
    const got = r.result({ id, nonce: NONCE }, 4);
    assert.equal(got.status, 200);
    assert.deepEqual(got.body, { root: 'ROOT.TOKEN', cert: 'CERT.TOKEN', mac: mac(PIN, 'k2go-ring-cert', NONCE, 'ROOT.TOKEN', 'CERT.TOKEN') });
});

test('device zero refuses a request', () => {
    const r = new Ring();
    r.open('teacher', PIN, 'ROOT.TOKEN', 0);
    const key = 'K2';
    const id = (r.join('10.0.0.7', { nonce: NONCE, key, proof: mac(PIN, 'k2go-ring-join', NONCE, key) }, 1).body as { id: string }).id;
    assert.ok(r.answer(id, null, 2));
    assert.equal(r.result({ id, nonce: NONCE }, 3).status, 403);
    assert.ok(!r.answer('ffffffffffffffff', 'C', 3));
});

// --- the routes ---

interface Box { port: number; close: () => Promise<void> }

async function startBox(): Promise<Box> {
    const app = express();
    app.use(express.json({ limit: '16kb' }));
    app.use('/school', createSchoolRouter(new ClassRoom(), () => 'none', new Ring()));
    app.use('/school', schoolErrors);
    const server = http.createServer(app);
    await new Promise<void>((ok) => server.listen(0, '127.0.0.1', ok));
    return {
        port: (server.address() as AddressInfo).port,
        close: () => new Promise<void>((ok) => { server.closeAllConnections(); server.close(() => ok()); }),
    };
}

function call(box: Box, method: string, path: string, ip: string, json?: unknown): Promise<{ status: number; body: string }> {
    return new Promise((ok, fail) => {
        const headers: Record<string, string> = { 'X-Real-IP': ip };
        const data = json === undefined ? undefined : JSON.stringify(json);
        if (data) headers['Content-Type'] = 'application/json';
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

test('routes: only this phone opens and answers the ring; the class network joins it', async () => {
    const box = await startBox();
    const lan = '192.168.1.30';
    const local = '127.0.0.1';
    try {
        assert.equal((await call(box, 'POST', '/school/teacher/ring/open', lan, { kind: 'teacher', pin: PIN, root: 'R' })).status, 404);
        assert.equal((await call(box, 'POST', '/school/teacher/ring/open', local, { kind: 'teacher', pin: PIN, root: 'ROOT.TOKEN' })).status, 204);
        const key = 'KEYB64==';
        const join = await call(box, 'POST', '/school/ring/join', lan, { nonce: NONCE, key, proof: mac(PIN, 'k2go-ring-join', NONCE, key) });
        assert.equal(join.status, 202);
        const id = JSON.parse(join.body).id;
        const view = JSON.parse((await call(box, 'GET', '/school/teacher/ring', local)).body);
        assert.equal(view.requests[0].key, key);
        assert.equal((await call(box, 'GET', '/school/teacher/ring', lan)).status, 404);
        assert.equal((await call(box, 'POST', '/school/teacher/ring/answer', local, { id, cert: 'CERT.TOKEN' })).status, 204);
        const res = JSON.parse((await call(box, 'POST', '/school/ring/result', lan, { id, nonce: NONCE })).body);
        assert.equal(res.cert, 'CERT.TOKEN');
        assert.equal((await call(box, 'POST', '/school/teacher/ring/close', local, {})).status, 204);
        assert.equal((await call(box, 'POST', '/school/ring/join', lan, { nonce: NONCE, key, proof: 'x' })).status, 404);
    } finally { await box.close(); }
});
