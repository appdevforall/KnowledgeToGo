/// <reference types="node" />
// The verified class: the class key of the box, the vouch of the teacher app, /school/hello.
// ClassCheckTest.java checks the same chain in the app.
import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'crypto';
import fs from 'fs';
import http from 'http';
import os from 'os';
import path from 'path';
import { AddressInfo } from 'net';
import express from 'express';
import { ClassRoom } from './class';
import { PhoneTrust, readTrust } from './role';
import { Ring } from './ring';
import { createSchoolRouter, schoolErrors } from './router';
import { checkProof, readRoot, RootRecord, sign, TRUST_V } from './trust';
import { boxAddress, ClassVouches, HELLO_PER_WINDOW, HELLO_WINDOW_MS, HelloLimit } from './vouch';

const V = JSON.parse(fs.readFileSync(path.join(__dirname, 'trust-vectors.json'), 'utf8'));
const pkcs8 = (b64: string): crypto.KeyObject => crypto.createPrivateKey({ key: Buffer.from(b64, 'base64'), format: 'der', type: 'pkcs8' });
const rootKey = pkcs8(V.keys.rootPkcs8);
const teacherKey = pkcs8(V.keys.teacherPkcs8);
const NOW = Math.floor(Date.now() / 1000);

// A root and a certificate that are valid now (the vectors have a fixed time).
const rootToken = sign(rootKey, { kind: 'root', v: TRUST_V, school: V.school, name: 'Test school', rootKey: V.keys.rootSpki, until: NOW + 365 * 86400 });
const root = readRoot(rootToken) as RootRecord;
const certToken = sign(rootKey, {
    kind: 'teacher', v: TRUST_V, school: V.school, key: V.keys.teacherSpki, label: 'Room 3B', serial: 'a1a1a1a1a1a1a1a1', from: NOW - 60, until: NOW + 86400,
});

function vouchFor(code: string, classKey: string, o: object = {}, key = teacherKey): string {
    return sign(key, { kind: 'class', v: TRUST_V, school: V.school, serial: 'a1a1a1a1a1a1a1a1', code, classKey, until: NOW + 3600, ...o });
}

test('the box makes one class key per class and takes only a vouch for it', () => {
    const v = new ClassVouches();
    assert.equal(v.keyFor(null), null);
    const k = v.keyFor('86GZ')!;
    assert.equal(v.keyFor('86GZ'), k);                       // the same class: the same key
    assert.ok(!v.accept('86GZ', root, certToken, vouchFor('86GZ', k, {}, rootKey), NOW));   // not the teacher key
    assert.ok(!v.accept('86GZ', root, certToken, vouchFor('XXXX', k), NOW));                 // another class
    assert.ok(!v.accept('86GZ', root, certToken, vouchFor('86GZ', V.vouch.classKey), NOW));  // another class key
    assert.ok(!v.accept('86GZ', null, certToken, vouchFor('86GZ', k), NOW));                 // no school on this phone
    assert.equal(v.hello('86GZ', 'n0nce-1234', '10.0.0.1:8085', NOW), null);
    assert.ok(v.accept('86GZ', root, certToken, vouchFor('86GZ', k), NOW));
    assert.ok(v.vouched('86GZ', NOW));
    const h = v.hello('86GZ', 'n0nce-1234', '10.0.0.1:8085', NOW)!;
    assert.equal(h.cert, certToken);
    assert.ok(checkProof(k, 'n0nce-1234', '86GZ', '10.0.0.1:8085', h.proof));
    assert.ok(!checkProof(k, 'n0nce-1234', '86GZ', '10.0.0.2:8085', h.proof));
    assert.equal(v.hello('86GZ', 'bad nonce', '10.0.0.1:8085', NOW), null);
    assert.equal(v.hello('86GZ', 'n0nce-1234', '10.0.0.1:8085', NOW + 3600), null);   // the vouch ended
    // A new class: a new key, and the old vouch is gone.
    assert.notEqual(v.keyFor('77QX'), k);
    assert.ok(!v.vouched('77QX', NOW));
    assert.equal(v.hello('77QX', 'n0nce-1234', '10.0.0.1:8085', NOW), null);
});

test('a phone that asks for hello too often waits', () => {
    const l = new HelloLimit();
    for (let i = 0; i < HELLO_PER_WINDOW; i++) assert.ok(l.allow('10.0.0.5', 0));
    assert.ok(!l.allow('10.0.0.5', 1));
    assert.ok(l.allow('10.0.0.6', 1));                       // another phone
    assert.ok(l.allow('10.0.0.5', HELLO_WINDOW_MS));          // the next window
});

test('the box address comes from nginx, never from the Host header', () => {
    assert.equal(boxAddress('192.168.1.20:8085'), '192.168.1.20:8085');
    assert.equal(boxAddress('::ffff:192.168.1.20:8085'), '192.168.1.20:8085');
    assert.equal(boxAddress(undefined), null);
    assert.equal(boxAddress('a b'), null);
});

test('the role comes from the trust files that the app binds into the box', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'k2go-trust-'));
    try {
        assert.equal(readTrust(dir).role, 'none');
        assert.equal(readTrust(path.join(dir, 'no-folder')).role, 'none');
        fs.writeFileSync(path.join(dir, 'root.token'), 'not a root');
        assert.equal(readTrust(dir).role, 'student');           // a file of the school takes rights away
        fs.writeFileSync(path.join(dir, 'root.token'), rootToken);
        assert.equal(readTrust(dir).role, 'student');
        fs.writeFileSync(path.join(dir, 'teacher.cert'), certToken);
        assert.equal(readTrust(dir).role, 'teacher');
        assert.equal(readTrust(dir, NOW + 2 * 86400).role, 'student');   // the certificate ended
        fs.writeFileSync(path.join(dir, 'teacher.cert'), V.teacher.signedByOther);
        assert.equal(readTrust(dir).role, 'student');
    } finally {
        fs.rmSync(dir, { recursive: true, force: true });
    }
});

// --- over HTTP ---

interface Box { port: number; close: () => Promise<void> }

async function startBox(trust: () => PhoneTrust): Promise<Box> {
    const app = express();
    app.use(express.json({ limit: '16kb' }));
    app.use('/school', createSchoolRouter(new ClassRoom(), trust, new Ring(), new ClassVouches()));
    app.use('/school', schoolErrors);
    const server = http.createServer(app);
    await new Promise<void>((ok) => server.listen(0, '127.0.0.1', ok));
    return {
        port: (server.address() as AddressInfo).port,
        close: () => new Promise<void>((ok) => { server.closeAllConnections(); server.close(() => ok()); }),
    };
}

function call(box: Box, method: string, p: string, headers: Record<string, string>, json?: unknown): Promise<{ status: number; body: string }> {
    return new Promise((ok, fail) => {
        const data = json === undefined ? undefined : JSON.stringify(json);
        const h = { ...headers, ...(data ? { 'Content-Type': 'application/json' } : {}) };
        const req = http.request({ host: '127.0.0.1', port: box.port, method, path: p, headers: h }, (res) => {
            let body = '';
            res.setEncoding('utf8');
            res.on('data', (c: string) => { body += c; });
            res.on('end', () => ok({ status: res.statusCode ?? 0, body }));
        });
        req.on('error', fail);
        req.end(data);
    });
}

test('routes: the teacher app vouches for its class; a student phone gets the chain', async () => {
    const trust = { root, cert: null, role: 'teacher' as const };
    const box = await startBox(() => trust);
    const local = { 'X-Real-IP': '127.0.0.1' };
    const lan = { 'X-Real-IP': '192.168.1.30', 'X-K2go-Server': '192.168.1.20:8085' };
    try {
        const code = JSON.parse((await call(box, 'POST', '/school/teacher/start', local, { name: null })).body).code;
        const k = JSON.parse((await call(box, 'GET', '/school/teacher/vouch', local)).body);
        assert.equal(k.code, code);
        assert.equal(k.vouched, false);
        assert.equal((await call(box, 'GET', '/school/teacher/vouch', lan)).status, 404);   // localhost only
        // No vouch yet: the class is not verified.
        assert.equal((await call(box, 'POST', '/school/hello', lan, { nonce: 'n0nce-1234' })).status, 404);
        assert.equal((await call(box, 'POST', '/school/teacher/vouch', local, { code, cert: certToken, vouch: vouchFor(code, V.vouch.classKey) })).status, 409);
        assert.equal((await call(box, 'POST', '/school/teacher/vouch', local, { code, cert: certToken, vouch: vouchFor(code, k.classKey) })).status, 204);
        assert.equal(JSON.parse((await call(box, 'GET', '/school/teacher/vouch', local)).body).vouched, true);
        const h = JSON.parse((await call(box, 'POST', '/school/hello', lan, { nonce: 'n0nce-1234' })).body);
        assert.equal(h.code, code);
        assert.equal(h.cert, certToken);
        assert.ok(checkProof(k.classKey, 'n0nce-1234', code, '192.168.1.20:8085', h.proof));
        // With no address from nginx the box cannot prove where it is.
        assert.equal((await call(box, 'POST', '/school/hello', { 'X-Real-IP': '192.168.1.30' }, { nonce: 'n0nce-1234' })).status, 404);
    } finally { await box.close(); }
});
