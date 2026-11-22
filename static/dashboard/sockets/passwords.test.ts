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
            if (!(c.username in accounts)) return 'absent';   // the account is not present
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

test('password rule: 6 characters with a letter and a digit', () => {
    assert.equal(pw.passwordFits('kolibri', 'perro1'), true);    // 6 characters, a letter, a digit
    assert.equal(pw.passwordFits('calibre', 'perr1'), false);    // 5 characters
    assert.equal(pw.passwordFits('kolibri', 'perros'), false);   // no digit
    assert.equal(pw.passwordFits('kolibri', '123456'), false);   // no letter
    assert.equal(pw.passwordFits('kolibri', 'changeme'), false); // the factory password has no digit
    assert.equal(pw.passwordFits('kolibri', '\u00d1and\u00fa1'), true);    // accented letters count once
    assert.equal(pw.minPasswordLength('calibre'), pw.MIN_PASSWORD_LENGTH);
});

test('password rule: Forgejo asks for its own minimum', () => {
    assert.equal(pw.forgejoMinLength('[security]\nINSTALL_LOCK = true\n'), 8);   // Forgejo default
    assert.equal(pw.forgejoMinLength('[security]\nMIN_PASSWORD_LENGTH = 10\n'), 10);
    assert.equal(pw.minPasswordLength('forgejo', () => 'MIN_PASSWORD_LENGTH = 4'), pw.MIN_PASSWORD_LENGTH);
    assert.equal(pw.minPasswordLength('forgejo', () => { throw new Error('no file'); }), 8);
});

test('status: factory default in use', async () => {
    resetStore();
    const f = fakeService({ Admin: 'changeme' });
    assert.deepEqual(await pw.passwordStatus('kolibri', f.deps),
        { service: 'kolibri', state: 'ok', factoryDefault: true, minLength: 6, letterAndDigit: true });
});

test('status: changed in the service only -> mismatch, with one sign-in attempt', async () => {
    resetStore();
    const f = fakeService({ Admin: 'Other-pass-1' });
    assert.deepEqual(await pw.passwordStatus('calibre', f.deps),
        { service: 'calibre', state: 'mismatch', factoryDefault: null, minLength: 6, letterAndDigit: true });
    assert.equal(f.calls.length, 1);
});

test('status: service down', async () => {
    resetStore();
    const f = fakeService({}, { down: true });
    assert.equal((await pw.passwordStatus('forgejo', f.deps)).state, 'unreachable');
});

test('status: admin account not present -> no_account, not mismatch', async () => {
    resetStore();
    const f = fakeService({ someoneElse: 'x' });
    assert.deepEqual(await pw.passwordStatus('forgejo', f.deps),
        { service: 'forgejo', state: 'no_account', factoryDefault: null, minLength: 8, letterAndDigit: true });
});

test('change: admin account not present -> no_account, nothing set', async () => {
    resetStore();
    const f = fakeService({});
    await assert.rejects(pw.changePassword('calibre', 'K2go-Test-1', f.deps),
        (e: unknown) => e instanceof pw.PasswordError && e.code === 'no_account');
    assert.equal(f.calls.filter((c) => c.startsWith('set:')).length, 0);
    assert.equal(pw.httpStatusFor('no_account'), 409);
});

test('change: stored copy first, then set, then save, then the new one signs in', async () => {
    resetStore();
    const f = fakeService({ Admin: 'changeme' });
    assert.equal(await pw.changePassword('kolibri', 'K2go-Test-1', f.deps), true);
    assert.deepEqual(f.calls, ['probe:Admin:changeme', 'set:Admin:K2go-Test-1', 'probe:Admin:K2go-Test-1']);
    assert.equal(cred.getCredential('kolibri').password, 'K2go-Test-1');
    assert.deepEqual(await pw.passwordStatus('kolibri', f.deps),
        { service: 'kolibri', state: 'ok', factoryDefault: false, minLength: 6, letterAndDigit: true });
});

test('change: a weak password never reaches the service', async () => {
    resetStore();
    const f = fakeService({ Admin: 'changeme' });
    await assert.rejects(pw.changePassword('kolibri', 'perro', f.deps), { code: 'weak_password' });
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
