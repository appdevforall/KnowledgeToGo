/// <reference types="node" />
//
// sockets/credentials.test.ts — ADFA-4949
//
// El almacén de credenciales es el único módulo nuevo que maneja un secreto, así
// que merece cobertura propia. Solo toca fs, sin dependencias nativas, de modo que
// corre bajo `npm ci --ignore-scripts` igual que el resto de la suite.
//
// El módulo lee la ruta del store en tiempo de importación (K2GO_CREDENTIALS_FILE),
// así que cada caso usa un fichero temporal fijado ANTES del require, y el módulo se
// recarga limpiando la caché de require.
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

/** Carga el módulo con un store temporal y el entorno indicado. */
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

// ─── Precedencia ─────────────────────────────────────────────────────────────

test('sin override ni env, devuelve el default de fábrica de IIAB', () => {
    const { mod, cleanup } = load();
    try {
        const c = mod.getCredential('kolibri');
        assert.equal(c.username, 'Admin');
        assert.equal(c.password, 'changeme');
        assert.equal(c.origin, 'default');
    } finally { cleanup(); }
});

test('el override persistido gana sobre el default', () => {
    const { mod, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: 'operador', password: 's3cr3t' });
        const c = mod.getCredential('kolibri');
        assert.equal(c.username, 'operador');
        assert.equal(c.password, 's3cr3t');
        assert.equal(c.origin, 'override');
    } finally { cleanup(); }
});

test('el entorno gana sobre el override persistido', () => {
    // Precedencia declarada: env > override > default. Es lo que permite fijar la
    // credencial desde el servicio PDSM sin tocar el fichero.
    const { mod, cleanup } = load({
        K2GO_KOLIBRI_USER: 'desde-env', K2GO_KOLIBRI_PASS: 'env-pass',
    });
    try {
        mod.setCredential('kolibri', { username: 'operador', password: 's3cr3t' });
        const c = mod.getCredential('kolibri');
        assert.equal(c.username, 'desde-env');
        assert.equal(c.origin, 'env');
    } finally { cleanup(); }
});

test('el entorno solo aplica si están usuario Y contraseña', () => {
    // Media credencial no es una credencial: con solo el usuario hay que caer al
    // siguiente nivel, no intentar autenticar sin contraseña.
    const { mod, cleanup } = load({ K2GO_KOLIBRI_USER: 'solo-usuario' });
    try {
        assert.equal(mod.getCredential('kolibri').origin, 'default');
    } finally { cleanup(); }
});

test('los servicios no se pisan entre sí', () => {
    const { mod, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: 'k', password: 'kp' });
        assert.equal(mod.getCredential('calibre').origin, 'default');
        assert.equal(mod.getCredential('kolibri').username, 'k');
    } finally { cleanup(); }
});

test('clearCredential vuelve al default', () => {
    const { mod, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: 'operador', password: 's3cr3t' });
        mod.clearCredential('kolibri');
        assert.equal(mod.getCredential('kolibri').origin, 'default');
        assert.equal(mod.getCredential('kolibri').username, 'Admin');
    } finally { cleanup(); }
});

// ─── Persistencia ────────────────────────────────────────────────────────────

test('el fichero se escribe con permisos 0600', () => {
    const { mod, storePath, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: 'operador', password: 's3cr3t' });
        assert.ok(fs.existsSync(storePath));
        if (process.platform !== 'win32') {
            assert.equal(fs.statSync(storePath).mode & 0o777, 0o600);
        }
    } finally { cleanup(); }
});

test('no quedan ficheros temporales tras escribir', () => {
    // La escritura es write+rename para que un corte no deje un JSON truncado.
    const { mod, storePath, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: 'a', password: 'b' });
        mod.setCredential('calibre', { username: 'c', password: 'd' });
        const leftovers = fs.readdirSync(path.dirname(storePath))
            .filter((f) => f.includes('.tmp-'));
        assert.deepEqual(leftovers, []);
    } finally { cleanup(); }
});

test('un JSON corrupto degrada al default en vez de romper', () => {
    const { mod, storePath, cleanup } = load();
    try {
        fs.writeFileSync(storePath, '{ esto no es json', { mode: 0o600 });
        const c = mod.getCredential('kolibri');
        assert.equal(c.origin, 'default');
        assert.equal(c.username, 'Admin');
    } finally { cleanup(); }
});

test('un JSON válido pero con forma inesperada se ignora', () => {
    const { mod, storePath, cleanup } = load();
    try {
        // Entradas sin password, o de tipo equivocado, no deben adoptarse a medias.
        fs.writeFileSync(storePath, JSON.stringify({
            kolibri: { username: 'x' },
            calibre: 'no-soy-un-objeto',
        }), { mode: 0o600 });
        assert.equal(mod.getCredential('kolibri').origin, 'default');
        assert.equal(mod.getCredential('calibre').origin, 'default');
    } finally { cleanup(); }
});

// ─── Validación de entrada ───────────────────────────────────────────────────

test('setCredential rechaza usuario o contraseña vacíos', () => {
    const { mod, cleanup } = load();
    try {
        assert.throws(() => mod.setCredential('kolibri', { username: '', password: 'x' }));
        assert.throws(() => mod.setCredential('kolibri', { username: '   ', password: 'x' }));
        assert.throws(() => mod.setCredential('kolibri', { username: 'u', password: '' }));
    } finally { cleanup(); }
});

test('setCredential recorta espacios del usuario, no de la contraseña', () => {
    // La contraseña se guarda literal: recortarla rompería una que empiece o acabe
    // en espacio, y el usuario nunca sabría por qué falla el login.
    const { mod, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: '  operador  ', password: '  con espacios  ' });
        const c = mod.getCredential('kolibri');
        assert.equal(c.username, 'operador');
        assert.equal(c.password, '  con espacios  ');
    } finally { cleanup(); }
});

// ─── describeCredential: lo que ve el webview ────────────────────────────────

test('describeCredential nunca expone la contraseña', () => {
    const { mod, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: 'operador', password: 'no-debe-salir' });
        const d = mod.describeCredential('kolibri') as Record<string, unknown>;
        assert.equal(JSON.stringify(d).includes('no-debe-salir'), false);
        assert.equal('password' in d, false);
        assert.equal(d.username, 'operador');
    } finally { cleanup(); }
});

test('isDefault avisa de que sigue la credencial de fábrica', () => {
    const { mod, cleanup } = load();
    try {
        assert.equal(mod.describeCredential('kolibri').isDefault, true);
        mod.setCredential('kolibri', { username: 'operador', password: 's3cr3t' });
        assert.equal(mod.describeCredential('kolibri').isDefault, false);
    } finally { cleanup(); }
});

test('isDefault sigue siendo true si se reescribe la credencial de fábrica', () => {
    // Guardar Admin/changeme a mano no deja de ser la credencial de fábrica: el
    // aviso de la UI tiene que seguir apareciendo.
    const { mod, cleanup } = load();
    try {
        mod.setCredential('kolibri', { username: 'Admin', password: 'changeme' });
        assert.equal(mod.describeCredential('kolibri').isDefault, true);
    } finally { cleanup(); }
});

// ─── isServiceName ───────────────────────────────────────────────────────────

test('isServiceName acota los servicios conocidos', () => {
    const { mod, cleanup } = load();
    try {
        assert.equal(mod.isServiceName('kolibri'), true);
        assert.equal(mod.isServiceName('calibre'), true);
        assert.equal(mod.isServiceName('kiwix'), false);
        assert.equal(mod.isServiceName('__proto__'), false);
        assert.equal(mod.isServiceName(''), false);
    } finally { cleanup(); }
});
