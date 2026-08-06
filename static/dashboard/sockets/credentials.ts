// sockets/credentials.ts — credential store for the box's local services
//
// Why this exists: credentials for the local services were hardcoded
// (books.exec.ts:15-16, books.query.ts:22, with the comment "a credential override
// can be added later if needed"). This module is that override: if the operator
// changes Kolibri's password on the device, it is updated here and everything keeps
// working without a rebuild or a redeploy.
//
// Precedence (first one that exists wins):
//   1. environment variable   K2GO_<SERVICE>_USER / K2GO_<SERVICE>_PASS
//   2. persisted override     /library/dashboard/credentials.json
//   3. factory default        Admin / changeme (what IIAB's Ansible provisions)
//
// The file is written 0600 and atomically (write + rename), so a crash mid-write
// cannot leave a truncated JSON that locks authentication out.
import fs from 'fs';
import path from 'path';

export type ServiceName = 'kolibri' | 'calibre';

export interface Credential {
    username: string;
    password: string;
}

/** Where the credential in use came from. Useful for diagnostics. */
export type CredentialOrigin = 'env' | 'override' | 'default';

export interface ResolvedCredential extends Credential {
    origin: CredentialOrigin;
}

const STORE_PATH = process.env.K2GO_CREDENTIALS_FILE
    || '/library/dashboard/credentials.json';

/** What IIAB's Ansible role provisions during the rootfs build. */
const DEFAULTS: Record<ServiceName, Credential> = {
    kolibri: { username: 'Admin', password: 'changeme' },
    calibre: { username: 'Admin', password: 'changeme' },
};

const ENV_PREFIX: Record<ServiceName, string> = {
    kolibri: 'K2GO_KOLIBRI',
    calibre: 'K2GO_CALIBRE',
};

const SERVICES: ServiceName[] = ['kolibri', 'calibre'];

export function isServiceName(s: string): s is ServiceName {
    return (SERVICES as string[]).includes(s);
}

type Store = Partial<Record<ServiceName, Credential>>;

/** Tolerant read: a missing or corrupt file degrades to the defaults rather than
 *  breaking dashboard startup.
 *
 *  But the degradation is LOGGED. Silencing it turns "the file was corrupted" into
 *  "the credentials stopped working for no reason", which is half an hour of
 *  diagnosis for the sake of a one-line message. A missing file is the normal case
 *  (nobody has set an override yet) and is not logged. */
function readStore(): Store {
    try {
        const raw = fs.readFileSync(STORE_PATH, 'utf8');
        const parsed = JSON.parse(raw) as unknown;
        if (!parsed || typeof parsed !== 'object') {
            console.warn(`[credentials] ${STORE_PATH} does not contain a JSON object; `
                + 'falling back to the factory values');
            return {};
        }
        const out: Store = {};
        for (const svc of SERVICES) {
            const entry = (parsed as Record<string, unknown>)[svc];
            if (entry && typeof entry === 'object') {
                const { username, password } = entry as Record<string, unknown>;
                if (typeof username === 'string' && typeof password === 'string') {
                    out[svc] = { username, password };
                }
            }
        }
        return out;
    } catch (e) {
        // ENOENT is the normal case: no override yet. Anything else (broken JSON,
        // permissions) does deserve a warning.
        const code = (e as NodeJS.ErrnoException)?.code;
        if (code !== 'ENOENT') {
            console.warn(`[credentials] could not read ${STORE_PATH} `
                + `(${e instanceof Error ? e.message : String(e)}); `
                + 'falling back to the factory values');
        }
        return {};
    }
}

function writeStore(store: Store): void {
    const dir = path.dirname(STORE_PATH);
    fs.mkdirSync(dir, { recursive: true });
    // Atomic write: if the process dies halfway, the old file stays valid instead
    // of leaving a half-written JSON behind.
    const tmp = `${STORE_PATH}.tmp-${process.pid}`;
    fs.writeFileSync(tmp, JSON.stringify(store, null, 2) + '\n', { mode: 0o600 });
    fs.renameSync(tmp, STORE_PATH);
    try { fs.chmodSync(STORE_PATH, 0o600); } catch { /* best effort */ }
}

/** The effective credential for a service, with its provenance. */
export function getCredential(service: ServiceName): ResolvedCredential {
    const envUser = process.env[`${ENV_PREFIX[service]}_USER`];
    const envPass = process.env[`${ENV_PREFIX[service]}_PASS`];
    if (envUser && envPass) {
        return { username: envUser, password: envPass, origin: 'env' };
    }
    const stored = readStore()[service];
    if (stored) return { ...stored, origin: 'override' };
    return { ...DEFAULTS[service], origin: 'default' };
}

/** Persists an override. Does NOT validate against the service: the REST route
 *  does that, and only calls in here after a successful login. */
export function setCredential(service: ServiceName, cred: Credential): void {
    const username = cred.username.trim();
    if (!username) throw new Error('username required');
    if (!cred.password) throw new Error('password required');
    const store = readStore();
    store[service] = { username, password: cred.password };
    writeStore(store);
}

/** Reverts to the factory default (or to the environment variable, if set). */
export function clearCredential(service: ServiceName): void {
    const store = readStore();
    delete store[service];
    writeStore(store);
}

/** View for the UI. A custom password is NEVER returned. Exception (ADFA-5044):
 *  while the service is still on the factory default — which is public and
 *  documented (Admin/changeme in IIAB) — it is included so the form can prefill the
 *  whole sign-in. As soon as an override exists it is omitted again. This is safe
 *  because it exposes no real secret and the API is localhost-only. */
export function describeCredential(service: ServiceName): {
    service: ServiceName;
    username: string;
    origin: CredentialOrigin;
    isDefault: boolean;
    password?: string;
} {
    const c = getCredential(service);
    // Signal so the UI can warn "you are still on the factory password".
    const isDefault = c.origin === 'default'
        || (c.username === DEFAULTS[service].username
            && c.password === DEFAULTS[service].password);
    const out: {
        service: ServiceName;
        username: string;
        origin: CredentialOrigin;
        isDefault: boolean;
        password?: string;
    } = { service, username: c.username, origin: c.origin, isDefault };
    if (isDefault) out.password = DEFAULTS[service].password;
    return out;
}

export const CREDENTIALS_STORE_PATH = STORE_PATH;
