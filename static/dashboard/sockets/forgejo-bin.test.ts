/// <reference types="node" />
// K2GO-593: the forgejo-bin runner. Loads jobs.ts (sqlite), so it runs in `npm run test:db`.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { EventEmitter } from 'events';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'k2go-forgejo-bin-'));
const BIN = path.join(tmp, 'bin');
process.env.K2GO_FORGEJO_BIN_DIR = BIN;
process.env.K2GO_JOBS_DB = path.join(tmp, 'jobs.db');

// eslint-disable-next-line @typescript-eslint/no-var-requires
const mod = require('./forgejo-bin.exec') as typeof import('./forgejo-bin.exec');
const { forgejoSuffix, isForgejoVersion, forgejoUrl, removePartials, forgejoBinRunner, parseSha256 } = mod;
import type { RunnerContext, JobUpdate } from './jobs';
import { CanceledError } from './jobs';
import crypto from 'crypto';

const SUFFIX = forgejoSuffix(os.machine());
const sha256 = (s: string) => crypto.createHash('sha256').update(s).digest('hex');

/** The release API answers `tag`; the .sha256 file answers `sha` (null = the release has none, 404). */
function stubFetch(tag: unknown, seen: Array<Record<string, string>>, sha: string | null = null): () => void {
    const orig = globalThis.fetch;
    globalThis.fetch = (async (url: unknown, init?: { headers?: Record<string, string> }) => {
        seen.push(init?.headers ?? {});
        if (String(url).endsWith('.sha256')) {
            return sha === null ? new Response('not found', { status: 404 })
                : new Response(`${sha}  forgejo-17.0.2-linux-${SUFFIX}\n`, { status: 200 });
        }
        return new Response(JSON.stringify({ tag_name: tag }), { status: 200 });
    }) as typeof fetch;
    return () => { globalThis.fetch = orig; };
}

/** A fake aria2: it writes the files it is told to, then exits as the test says. */
function fakeCtx(onSpawn: (args: string[], child: EventEmitter & { stdout: EventEmitter; stderr: EventEmitter }) => void) {
    const updates: JobUpdate[] = [];
    const state = { canceled: false, paused: false, spawned: 0 };
    const ac = new AbortController();
    const ctx = {
        job: { id: 'forgejo-bin-test' },
        items: ['forgejo'],
        ids: ['forgejo'],
        update: (p: JobUpdate) => { updates.push(p); },
        isCanceled: () => state.canceled,
        throwIfCanceled: () => { if (state.canceled) throw new CanceledError(); },
        isPaused: () => state.paused,
        signal: ac.signal,
        spawn: (_cmd: string, args: string[]) => {
            state.spawned++;
            const child = Object.assign(new EventEmitter(), { stdout: new EventEmitter(), stderr: new EventEmitter() });
            setImmediate(() => onSpawn(args, child));
            return child;
        },
        log: () => { /* quiet */ },
        reportRetry: () => { /* not checked here */ },
    } as unknown as RunnerContext;
    return { ctx, updates, state, ac };
}

function reset(): void {
    fs.rmSync(BIN, { recursive: true, force: true });
}

test('forgejoSuffix uses the role iset_suffixes map', () => {
    assert.equal(forgejoSuffix('aarch64'), 'arm64');
    assert.equal(forgejoSuffix('armv7l'), 'arm-6');
    assert.equal(forgejoSuffix('armv8l'), 'arm-6');   // 32-bit userland on a 64-bit kernel
    assert.equal(forgejoSuffix('x86_64'), 'amd64');
    assert.equal(forgejoSuffix('i686'), null);
});

test('isForgejoVersion accepts only X.Y.Z', () => {
    assert.equal(isForgejoVersion('17.0.2'), true);
    for (const bad of ['v17.0.2', '17.0', '17.0.2-rc1', '../1.2.3', '', null, 17]) {
        assert.equal(isForgejoVersion(bad), false, String(bad));
    }
});

test('forgejoUrl is the role forgejo_download_url', () => {
    assert.equal(forgejoUrl('17.0.2', 'arm64'),
        'https://codeberg.org/forgejo/forgejo/releases/download/v17.0.2/forgejo-17.0.2-linux-arm64');
});

test('removePartials keeps binaries, .asc files and the partial of keepVersion', () => {
    reset();
    fs.mkdirSync(BIN, { recursive: true });
    const names = ['forgejo-17.0.2', 'forgejo-17.0.2.asc', 'forgejo-17.0.1.part', 'forgejo-17.0.1.part.aria2',
        'forgejo-17.0.2.part', 'forgejo-17.0.2.part.aria2', 'other.part'];
    for (const n of names) fs.writeFileSync(path.join(BIN, n), 'x');
    removePartials(BIN, '17.0.2');
    assert.deepEqual(fs.readdirSync(BIN).sort(),
        ['forgejo-17.0.2', 'forgejo-17.0.2.asc', 'forgejo-17.0.2.part', 'forgejo-17.0.2.part.aria2', 'other.part']);
    removePartials(BIN);
    assert.deepEqual(fs.readdirSync(BIN).sort(), ['forgejo-17.0.2', 'forgejo-17.0.2.asc', 'other.part']);
});

test('runner: a binary that is already there ends the job with no download', { skip: !SUFFIX }, async () => {
    reset();
    fs.mkdirSync(BIN, { recursive: true });
    fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2'), 'binary');
    fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2.part'), 'old partial');
    fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2.part.aria2'), 'ctl');
    const headers: Array<Record<string, string>> = [];
    const restore = stubFetch('v17.0.2', headers);
    try {
        const { ctx, updates, state } = fakeCtx(() => assert.fail('no aria2 expected'));
        await forgejoBinRunner(ctx);
        assert.equal(state.spawned, 0);
        assert.deepEqual(fs.readdirSync(BIN), ['forgejo-17.0.2']);   // an old partial is removed
        const last = updates[updates.length - 1];
        assert.equal(last.phase, 'done');
        assert.deepEqual(JSON.parse(String(last.result)), { version: '17.0.2' });
        assert.match(headers[0]['User-Agent'], /^K2Go Dashboard\//);   // K2G-P5: neutral agent
    } finally { restore(); }
});

test('runner: a bad release tag fails at once, with no retry', { skip: !SUFFIX }, async () => {
    reset();
    const headers: Array<Record<string, string>> = [];
    const restore = stubFetch('nightly', headers);
    try {
        const { ctx } = fakeCtx(() => assert.fail('no aria2 expected'));
        await assert.rejects(forgejoBinRunner(ctx), /bad version/);
        assert.equal(headers.length, 1);
    } finally { restore(); }
});

test('runner: a complete download is renamed to the final name, mode 0775', { skip: !SUFFIX }, async () => {
    reset();
    const restore = stubFetch('v17.0.2', []);
    try {
        const { ctx, updates } = fakeCtx((args, child) => {
            const input = fs.readFileSync(args[args.indexOf('-i') + 1], 'utf8');
            assert.match(input, /forgejo-17\.0\.2-linux-/);
            assert.match(input, /out=forgejo-17\.0\.2\.part/);
            fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2.part'), 'binary');
            child.emit('exit', 0, null);
        });
        await forgejoBinRunner(ctx);
        assert.deepEqual(fs.readdirSync(BIN), ['forgejo-17.0.2']);
        if (process.platform !== 'win32') {
            assert.equal(fs.statSync(path.join(BIN, 'forgejo-17.0.2')).mode & 0o777, 0o775);
        }
        assert.equal(updates[updates.length - 1].phase, 'done');
    } finally { restore(); }
});

test('runner: an aria2 run that leaves its control file is not complete', { skip: !SUFFIX }, async () => {
    reset();
    const restore = stubFetch('v17.0.2', []);
    try {
        const { ctx } = fakeCtx((_args, child) => {
            fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2.part'), 'half');
            fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2.part.aria2'), 'ctl');
            child.emit('exit', 0, null);
        });
        await assert.rejects(forgejoBinRunner(ctx), /did not complete/);
        assert.equal(fs.existsSync(path.join(BIN, 'forgejo-17.0.2')), false);
        assert.equal(fs.existsSync(path.join(BIN, 'forgejo-17.0.2.part')), true);   // kept for Retry
    } finally { restore(); }
});

test('runner: Cancel removes the partial and its control file', { skip: !SUFFIX }, async () => {
    reset();
    const restore = stubFetch('v17.0.2', []);
    try {
        const box: { state?: { canceled: boolean } } = {};
        const { ctx, state } = fakeCtx((_args, child) => {
            fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2.part'), 'half');
            fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2.part.aria2'), 'ctl');
            box.state!.canceled = true;
            child.emit('exit', null, 'SIGKILL');
        });
        box.state = state;
        await assert.rejects(forgejoBinRunner(ctx), CanceledError);
        assert.deepEqual(fs.readdirSync(BIN), []);
    } finally { restore(); }
});

test('runner: Pause keeps the partial for Resume', { skip: !SUFFIX }, async () => {
    reset();
    const restore = stubFetch('v17.0.2', []);
    try {
        const box: { state?: { paused: boolean } } = {};
        const { ctx, state } = fakeCtx((_args, child) => {
            fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2.part'), 'half');
            fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2.part.aria2'), 'ctl');
            box.state!.paused = true;
            child.emit('exit', null, 'SIGTERM');
        });
        box.state = state;
        await assert.rejects(forgejoBinRunner(ctx), /paused/);
        assert.deepEqual(fs.readdirSync(BIN).sort(), ['forgejo-17.0.2.part', 'forgejo-17.0.2.part.aria2']);
    } finally { restore(); }
});

test('parseSha256 reads the first field of a sha256sum line', () => {
    const h = 'A'.repeat(64);
    assert.equal(parseSha256(`${h}  forgejo-17.0.2-linux-arm64\n`), 'a'.repeat(64));
    assert.equal(parseSha256(h), 'a'.repeat(64));
    assert.equal(parseSha256('<html>not found</html>'), null);
    assert.equal(parseSha256('abc  file'), null);
});

test('runner: a file that matches the release sha-256 is kept', { skip: !SUFFIX }, async () => {
    reset();
    fs.mkdirSync(BIN, { recursive: true });
    fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2'), 'binary');
    const restore = stubFetch('v17.0.2', [], sha256('binary'));
    try {
        const { ctx, updates, state } = fakeCtx(() => assert.fail('no aria2 expected'));
        await forgejoBinRunner(ctx);
        assert.equal(state.spawned, 0);
        assert.equal(updates[updates.length - 1].phase, 'done');
    } finally { restore(); }
});

test('runner: a file that does not match the release sha-256 is downloaded again', { skip: !SUFFIX }, async () => {
    reset();
    fs.mkdirSync(BIN, { recursive: true });
    fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2'), 'corrupt');
    const restore = stubFetch('v17.0.2', [], sha256('binary'));
    try {
        const { ctx, state } = fakeCtx((args, child) => {
            const input = fs.readFileSync(args[args.indexOf('-i') + 1], 'utf8');
            assert.match(input, new RegExp(`checksum=sha-256=${sha256('binary')}`));
            assert.equal(fs.existsSync(path.join(BIN, 'forgejo-17.0.2')), false);   // the wrong file is gone
            fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2.part'), 'binary');
            child.emit('exit', 0, null);
        });
        await forgejoBinRunner(ctx);
        assert.equal(state.spawned, 1);
        assert.equal(fs.readFileSync(path.join(BIN, 'forgejo-17.0.2'), 'utf8'), 'binary');
    } finally { restore(); }
});

test('runner: a checksum failure (aria2 exit 32) removes the partial', { skip: !SUFFIX }, async () => {
    reset();
    const restore = stubFetch('v17.0.2', [], sha256('binary'));
    try {
        const { ctx } = fakeCtx((_args, child) => {
            fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2.part'), 'wrong');
            fs.writeFileSync(path.join(BIN, 'forgejo-17.0.2.part.aria2'), 'ctl');
            child.emit('exit', 32, null);
        });
        await assert.rejects(forgejoBinRunner(ctx), /does not match the release sha-256/);
        assert.deepEqual(fs.readdirSync(BIN), []);
    } finally { restore(); }
});

// The sqlite file stays open in this process (Windows refuses the delete), so the delete is best effort.
test.after(() => { try { fs.rmSync(tmp, { recursive: true, force: true }); } catch { /* best effort */ } });
