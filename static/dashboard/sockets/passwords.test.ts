/// <reference types="node" />
//
// sockets/passwords.test.ts - K2GO-552
//
// The order of a change and the status rules, with a fake service. The credential store is a
// temporary file, set before the modules load (credentials.ts reads the path at import time).
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'k2go-pw-'));
process.env.K2GO_CREDENTIALS_FILE = path.join(dir, 'credentials.json');
delete process.env.K2GO_KOLIBRI_USER;
delete process.env.K2GO_KOLIBRI_PASS;

// eslint-disable-next-line @typescript-eslint/no-var-requires
const pw = require('./passwords') as typeof import('./passwords');
// eslint-disable-next-line @typescript-eslint/no-var-requires
const cred = require('./credentials') as typeof import('./credentials');

type Svc = import('./credentials').ServiceName;

/** A fake service that holds one password per account and records every call. */
function fakeService(initial: Record<string, string>, opts: { setFails?: boolean; down?: boolean } = {}) {
    const accounts = { ...initial };
    const calls: string[] = [];
    const deps: import('./passwords').PasswordDeps = {
        probe: async (_s: Svc, c) => {
            calls.push(`probe:${c.username}:${c.password}`);
            if (opts.down) return 'unreachable';
            return accounts[c.username] === c.password ? 'ok' : 'rejected';
        },
        setPassword: async (_s: Svc, u, p) => {
            calls.push(`set:${u}:${p}`);
            if (opts.setFails) throw new Error('tool exited 1');
            accounts[u] = p;
        },
    };
    return { accounts, calls, deps };
}

function resetStore(): void {
    fs.rmSync(process.env.K2GO_CREDENTIALS_FILE as string, { force: true });
}

test('password rule: the Calibre-Web policy', () => {
    assert.equal(pw.isStrongPassword('K2go-Test-1'), true);
    assert.equal(pw.isStrongPassword('Ñandú-2026'), true);
    assert.equal(pw.isStrongPassword('changeme'), false);
    assert.equal(pw.isStrongPassword('K2go-T1'), false);     // 7 characters
    assert.equal(pw.isStrongPassword('k2go-test-1'), false); // no upper case
    assert.equal(pw.isStrongPassword('K2GO-TEST-1'), false); // no lower case
    assert.equal(pw.isStrongPassword('Kgo-Test-xy'), false); // no digit
    assert.equal(pw.isStrongPassword('K2goTest12'), false);  // no special character
});

test('status: factory default in use', async () => {
    resetStore();
    const f = fakeService({ Admin: 'changeme' });
    assert.deepEqual(await pw.passwordStatus('kolibri', f.deps),
        { service: 'kolibri', state: 'ok', factoryDefault: true });
});

test('status: changed in the service only -> mismatch, with one sign-in attempt', async () => {
    resetStore();
    const f = fakeService({ Admin: 'Other-pass-1' });
    assert.deepEqual(await pw.passwordStatus('calibre', f.deps),
        { service: 'calibre', state: 'mismatch', factoryDefault: null });
    assert.equal(f.calls.length, 1);
});

test('status: service down', async () => {
    resetStore();
    const f = fakeService({}, { down: true });
    assert.equal((await pw.passwordStatus('forgejo', f.deps)).state, 'unreachable');
});

test('change: stored copy first, then set, then save, then the new one signs in', async () => {
    resetStore();
    const f = fakeService({ Admin: 'changeme' });
    assert.equal(await pw.changePassword('kolibri', 'K2go-Test-1', f.deps), true);
    assert.deepEqual(f.calls, ['probe:Admin:changeme', 'set:Admin:K2go-Test-1', 'probe:Admin:K2go-Test-1']);
    assert.equal(cred.getCredential('kolibri').password, 'K2go-Test-1');
    assert.deepEqual(await pw.passwordStatus('kolibri', f.deps),
        { service: 'kolibri', state: 'ok', factoryDefault: false });
});

test('change: a weak password never reaches the service', async () => {
    resetStore();
    const f = fakeService({ Admin: 'changeme' });
    await assert.rejects(pw.changePassword('kolibri', 'changeme2', f.deps), { code: 'weak_password' });
    assert.equal(f.calls.length, 0);
});

test('change: a mismatch is not overwritten, the store is not touched', async () => {
    resetStore();
    const f = fakeService({ Admin: 'Other-pass-1' });
    await assert.rejects(pw.changePassword('calibre', 'K2go-Test-1', f.deps), { code: 'mismatch' });
    assert.deepEqual(f.calls, ['probe:Admin:changeme']);
    assert.equal(cred.getCredential('calibre').origin, 'default');
});

test('change: the tool fails -> nothing saved', async () => {
    resetStore();
    const f = fakeService({ k2goadmin: 'changeme' }, { setFails: true });
    await assert.rejects(pw.changePassword('forgejo', 'K2go-Test-1', f.deps), { code: 'set_failed' });
    assert.equal(cred.getCredential('forgejo').password, 'changeme');
});

test('change: one at a time per service', async () => {
    resetStore();
    const f = fakeService({ Admin: 'changeme' });
    const first = pw.changePassword('kolibri', 'K2go-Test-1', f.deps);
    await assert.rejects(pw.changePassword('kolibri', 'K2go-Test-2', f.deps), { code: 'busy' });
    await first;
    await pw.changePassword('kolibri', 'K2go-Test-2', f.deps);   // free again after the first one
});

test('set ok but the sign-in is locked: the copy is saved, verified is false', async () => {
    resetStore();
    const f = fakeService({ Admin: 'changeme' });
    const locked = { ...f.deps, probe: async () => 'rejected' as const };
    assert.equal(await pw.resetPassword('calibre', locked), false);
    assert.equal(cred.getCredential('calibre').origin, 'default');
    cred.setCredential('calibre', { username: 'Admin', password: 'changeme' });
    assert.equal(await pw.changePassword('calibre', 'K2go-Test-1', { ...f.deps, probe: async (_s, c) =>
        (c.password === 'changeme' ? 'ok' : 'rejected') }), false);
    assert.equal(cred.getCredential('calibre').password, 'K2go-Test-1');
});

test('reset: works without the current password and clears the stored copy', async () => {
    resetStore();
    cred.setCredential('calibre', { username: 'Admin', password: 'Forgotten-1' });
    const f = fakeService({ Admin: 'Something-else-9' });
    assert.equal(await pw.resetPassword('calibre', f.deps), true);
    assert.deepEqual(f.calls, ['set:Admin:changeme', 'probe:Admin:changeme']);
    assert.equal(cred.getCredential('calibre').origin, 'default');
});

test('http status per error code', () => {
    assert.equal(pw.httpStatusFor('weak_password'), 400);
    assert.equal(pw.httpStatusFor('mismatch'), 409);
    assert.equal(pw.httpStatusFor('unreachable'), 503);
    assert.equal(pw.httpStatusFor('set_failed'), 500);
});
