/// <reference types="node" />
//
// sockets/credentials.test.ts — ADFA-4949
//
// The credentials store is the only new module that handles a secret, so it
// deserves coverage of its own. It touches fs alone, with no native dependencies,
// so it runs under `npm ci --ignore-scripts` like the rest of the suite.
//
// The module reads the store path at import time (K2GO_CREDENTIALS_FILE), so each
// case uses a temporary file set BEFORE the require, and the module is reloaded by
// clearing the require cache.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const MODULE = './credentials';

interface CredentialsModule {
    getCredential(s: 'kolibri' | 'calibre'): { username: string; password: string; origin: string };
    setCredential(s: 'kolibri' | 'calibre', c: { username: string; password: string }): void;
    clearCredential(s: 'kolibri' | 'calibre'): void;
    describeCredential(s: 'kolibri' | 'calibre'): {
        service: string; username: string; origin: string; isDefault: boolean;
    };
    isServiceName(s: string): boolean;
    CREDENTIALS_STORE_PATH: string;
}

/** Loads the module with a temporary store and the given environment. */
function load(env: Record<string, string | undefined> = {}): {
    mod: CredentialsModule; storePath: string; cleanup: () => void;
} {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'k2go-cred-'));
    const storePath = path.join(dir, 'credentials.json');

    const saved: Record<string, string | undefined> = {};
    const apply = (k: string, v: string | undefined) => {
        saved[k] = process.env[k];
        if (v === undefined) delete process.env[k]; else process.env[k] = v;
    };
    apply('K2GO_CREDENTIALS_FILE', storePath);
    for (const k of ['K2GO_KOLIBRI_USER', 'K2GO_KOLIBRI_PASS',
                     'K2GO_CALIBRE_USER', 'K2GO_CALIBRE_PASS']) {
        apply(k, env[k]);
    }

    delete require.cache[require.resolve(MODULE)];
    const mod = require(MODULE) as CredentialsModule;

    return {
        mod,
        storePath,
        cleanup: () => {
            for (const [k, v] of Object.entries(saved)) {
                if (v === undefined) delete process.env[k]; else process.env[k] = v;
            }
            delete require.cache[require.resolve(MODULE)];
            try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* best effort */ }
        },
    };
}

// ─── Precedence ──────────────────────────────────────────────────────────────

test('with no override and no env, returns the IIAB factory default', () => {
    const { mod, cleanup } = load();
    try {
        const c = mod.getCredential('kolibri');
        assert.equal(c.username, 'Admin');
        assert.equal(c.password, 'changeme');
        assert.equal(c.origin, 'default');
    } finally { cleanup(); }
});

test('the persisted override wins over the default', () => {
    const { mod, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: 'operator', password: 's3cr3t' });
        const c = mod.getCredential('kolibri');
        assert.equal(c.username, 'operator');
        assert.equal(c.password, 's3cr3t');
        assert.equal(c.origin, 'override');
    } finally { cleanup(); }
});

test('the environment wins over the persisted override', () => {
    // Declared precedence: env > override > default. This is what allows the
    // credential to be set from the PDSM service without touching the file.
    const { mod, cleanup } = load({
        K2GO_KOLIBRI_USER: 'from-env', K2GO_KOLIBRI_PASS: 'env-pass',
    });
    try {
        mod.setCredential('kolibri', { username: 'operator', password: 's3cr3t' });
        const c = mod.getCredential('kolibri');
        assert.equal(c.username, 'from-env');
        assert.equal(c.origin, 'env');
    } finally { cleanup(); }
});

test('the environment only applies if both username AND password are set', () => {
    // Half a credential is not a credential: with only the username we must fall
    // to the next level, not try to authenticate without a password.
    const { mod, cleanup } = load({ K2GO_KOLIBRI_USER: 'user-only' });
    try {
        assert.equal(mod.getCredential('kolibri').origin, 'default');
    } finally { cleanup(); }
});

test('services do not overwrite each other', () => {
    const { mod, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: 'k', password: 'kp' });
        assert.equal(mod.getCredential('calibre').origin, 'default');
        assert.equal(mod.getCredential('kolibri').username, 'k');
    } finally { cleanup(); }
});

test('clearCredential returns to the default', () => {
    const { mod, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: 'operator', password: 's3cr3t' });
        mod.clearCredential('kolibri');
        assert.equal(mod.getCredential('kolibri').origin, 'default');
        assert.equal(mod.getCredential('kolibri').username, 'Admin');
    } finally { cleanup(); }
});

// ─── Persistence ─────────────────────────────────────────────────────────────

test('the file is written with 0600 permissions', () => {
    const { mod, storePath, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: 'operator', password: 's3cr3t' });
        assert.ok(fs.existsSync(storePath));
        if (process.platform !== 'win32') {
            assert.equal(fs.statSync(storePath).mode & 0o777, 0o600);
        }
    } finally { cleanup(); }
});

test('no temporary files are left behind after writing', () => {
    // The write is write+rename so an interruption cannot leave a truncated JSON.
    const { mod, storePath, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: 'a', password: 'b' });
        mod.setCredential('calibre', { username: 'c', password: 'd' });
        const leftovers = fs.readdirSync(path.dirname(storePath))
            .filter((f) => f.includes('.tmp-'));
        assert.deepEqual(leftovers, []);
    } finally { cleanup(); }
});

test('a corrupt JSON degrades to the default instead of breaking', () => {
    const { mod, storePath, cleanup } = load();
    try {
        fs.writeFileSync(storePath, '{ esto no es json', { mode: 0o600 });
        const c = mod.getCredential('kolibri');
        assert.equal(c.origin, 'default');
        assert.equal(c.username, 'Admin');
    } finally { cleanup(); }
});

test('a valid JSON with an unexpected shape is ignored', () => {
    const { mod, storePath, cleanup } = load();
    try {
        // Entries with no password, or of the wrong type, must not be half-adopted.
        fs.writeFileSync(storePath, JSON.stringify({
            kolibri: { username: 'x' },
            calibre: 'not-an-object',
        }), { mode: 0o600 });
        assert.equal(mod.getCredential('kolibri').origin, 'default');
        assert.equal(mod.getCredential('calibre').origin, 'default');
    } finally { cleanup(); }
});

// ─── Input validation ────────────────────────────────────────────────────────

test('setCredential rejects an empty username or password', () => {
    const { mod, cleanup } = load();
    try {
        assert.throws(() => mod.setCredential('kolibri', { username: '', password: 'x' }));
        assert.throws(() => mod.setCredential('kolibri', { username: '   ', password: 'x' }));
        assert.throws(() => mod.setCredential('kolibri', { username: 'u', password: '' }));
    } finally { cleanup(); }
});

test('setCredential trims spaces from the username, not from the password', () => {
    // The password is stored verbatim: trimming it would break one starting or
    // ending in a space, and the user would never know why the login fails.
    const { mod, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: '  operator  ', password: '  with spaces  ' });
        const c = mod.getCredential('kolibri');
        assert.equal(c.username, 'operator');
        assert.equal(c.password, '  with spaces  ');
    } finally { cleanup(); }
});

// ─── describeCredential: what the webview sees ───────────────────────────────

test('describeCredential never exposes the password', () => {
    const { mod, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: 'operator', password: 'must-not-leak' });
        const d = mod.describeCredential('kolibri') as Record<string, unknown>;
        assert.equal(JSON.stringify(d).includes('must-not-leak'), false);
        assert.equal('password' in d, false);
        assert.equal(d.username, 'operator');
    } finally { cleanup(); }
});

test('isDefault warns that the factory credential is still in place', () => {
    const { mod, cleanup } = load();
    try {
        assert.equal(mod.describeCredential('kolibri').isDefault, true);
        mod.setCredential('kolibri', { username: 'operator', password: 's3cr3t' });
        assert.equal(mod.describeCredential('kolibri').isDefault, false);
    } finally { cleanup(); }
});

test('isDefault stays true if the factory credential is written again', () => {
    // Saving Admin/changeme by hand is still the factory credential: the UI
    // warning has to keep appearing.
    const { mod, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: 'Admin', password: 'changeme' });
        assert.equal(mod.describeCredential('kolibri').isDefault, true);
    } finally { cleanup(); }
});

// ─── isServiceName ───────────────────────────────────────────────────────────

test('isServiceName limits to the known services', () => {
    const { mod, cleanup } = load();
    try {
        assert.equal(mod.isServiceName('kolibri'), true);
        assert.equal(mod.isServiceName('calibre'), true);
        assert.equal(mod.isServiceName('kiwix'), false);
        assert.equal(mod.isServiceName('__proto__'), false);
        assert.equal(mod.isServiceName(''), false);
    } finally { cleanup(); }
});
