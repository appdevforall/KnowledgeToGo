// sockets/passwords.ts - K2GO-552
//
// Changes the admin password inside each box service and reports whether the stored copy still
// signs in. The service database holds the real password; credentials.json holds only the copy that
// auto-login needs. So "in sync" and "factory default in use" are never stored: each check is a live
// login. Order of a change: the stored copy signs in -> set in the service -> save the copy -> the new
// one signs in (reported as `verified`). If the process dies between the set and the save, the next
// status check reports 'mismatch' and the user repairs it in Settings > Authentication.
import { execFile } from 'child_process';
import * as fs from 'fs';
import {
    ServiceName, Credential, getCredential, setCredential, clearCredential, factoryCredential,
} from './credentials';
import { login as kolibriLogin, KolibriAuthError } from './kolibri.session';
import { login as forgejoLogin, ForgejoAuthError } from './forgejo.session';
import { forgejoStatus } from './forgejo.status';
import { verifyCalibreCredentials } from './books.query';

/** The shortest admin password K2Go takes. With hasLetterAndDigit it is the whole rule: simple to
 *  meet, and it keeps out the factory password. Calibre-Web's own policy does not apply (the hash
 *  goes to its database, see CALIBRE_SET) and Kolibri has no rule. Forgejo can ask for a longer one
 *  (minPasswordLength). */
export const MIN_PASSWORD_LENGTH = 6;
const FORGEJO_INI = '/etc/forgejo/app.ini';

/** Forgejo's own minimum, `[security] MIN_PASSWORD_LENGTH` (default 8): its admin tool refuses a
 *  shorter password. */
export function forgejoMinLength(ini: string): number {
    const m = /^\s*MIN_PASSWORD_LENGTH\s*=\s*(\d+)/m.exec(ini);
    return m ? Number(m[1]) : 8;
}

/** The minimum length for one service: K2Go's minimum, or more when the service asks for more. The
 *  app gets it in the status, so the rule has one source. */
export function minPasswordLength(
    service: ServiceName, readIni: () => string = () => fs.readFileSync(FORGEJO_INI, 'utf8'),
): number {
    if (service !== 'forgejo') return MIN_PASSWORD_LENGTH;
    let own = 8;
    try { own = forgejoMinLength(readIni()); } catch { /* no file: Forgejo's default */ }
    return Math.max(MIN_PASSWORD_LENGTH, own);
}

/** At least one letter and one digit, in any script. */
export function hasLetterAndDigit(pw: string): boolean {
    return /\p{L}/u.test(pw) && /\p{Nd}/u.test(pw);
}

/** The whole rule. Characters, not UTF-16 units: an emoji is one. */
export function passwordFits(service: ServiceName, pw: string): boolean {
    return [...pw].length >= minPasswordLength(service) && hasLetterAndDigit(pw);
}

/** 'absent': the service rejects the sign-in because the admin account is not present (never created,
 *  or deleted by the user). Not a changed password: nothing to check, repair or change. */
export type ProbeResult = 'ok' | 'rejected' | 'absent' | 'unreachable';
export type Probe = (service: ServiceName, cred: Credential) => Promise<ProbeResult>;
export type SetPassword = (service: ServiceName, username: string, password: string) => Promise<void>;

export interface PasswordDeps { probe: Probe; setPassword: SetPassword }

/** A sign-in attempt. A failed attempt counts toward Calibre-Web's login limit (3 a minute), so the
 *  callers below make at most one attempt that can fail. */
export const probeLogin: Probe = async (service, cred) => {
    try {
        if (service === 'kolibri') await kolibriLogin(cred);
        else if (service === 'forgejo') await forgejoLogin(cred);
        else await verifyCalibreCredentials(cred.username, cred.password);
        return 'ok';
    } catch (e) {
        if (e instanceof KolibriAuthError) {
            if (e.reason === 'permission') return 'ok';   // signs in; content rights are another check
            // Kolibri says itself when the account is not present: HTTP 400 NOT_FOUND on "username"
            // (a wrong password is a 401).
            if (e.reason === 'protocol' && /"NOT_FOUND"[^}]*"field":"username"/.test(e.message)) return 'absent';
            return e.reason === 'credentials' ? 'rejected' : 'unreachable';
        }
        let rejected: boolean;
        if (e instanceof ForgejoAuthError) {
            rejected = e.reason === 'credentials';
        } else {
            rejected = /invalid.*cred/i.test(e instanceof Error ? e.message : '');
        }
        if (!rejected) return 'unreachable';
        return await accountPresent(service, cred.username) === false ? 'absent' : 'rejected';
    }
};

/** Is the admin account present in Forgejo or Calibre-Web? Asked only after a rejected sign-in. null = could not
 *  tell: the caller keeps 'rejected', so a real changed password is never hidden. */
async function accountPresent(service: ServiceName, username: string): Promise<boolean | null> {
    if (service === 'forgejo') {
        const s = await forgejoStatus();   // owns the "admin exists" fact (public GET /users/{admin})
        return s.reachable ? s.adminExists : null;
    }
    if (service !== 'calibre') return null;   // Kolibri answers it in the sign-in itself (above)
    const code = await exitCode(CALIBRE_PY, ['-c', CALIBRE_HAS, CALIBRE_DB], { K2GO_USER: username });
    return code === 0 ? true : code === 3 ? false : null;
}

const RUN_TIMEOUT_MS = 120_000;

/** Runs a command without a shell and resolves to its exit code (null: it did not run or end). */
function exitCode(cmd: string, args: string[], env: Record<string, string>): Promise<number | null> {
    return new Promise((resolve) => {
        execFile(cmd, args, { env: { ...process.env, ...env }, timeout: RUN_TIMEOUT_MS }, (err) => {
            if (!err) { resolve(0); return; }
            resolve(typeof err.code === 'number' ? err.code : null);
        });
    });
}

/** Runs a command without a shell. The error never carries the arguments: they hold the password. */
function run(cmd: string, args: string[], env: Record<string, string> = {}): Promise<void> {
    return new Promise((resolve, reject) => {
        execFile(cmd, args, { env: { ...process.env, ...env }, timeout: RUN_TIMEOUT_MS },
            (err, _stdout, stderr) => {
                if (!err) { resolve(); return; }
                const last = String(stderr || '').trim().split('\n').pop() || '';
                reject(new Error(`${cmd.split('/').pop()} exited ${err.code ?? 'abnormally'}: ${last}`));
            });
    });
}

const CALIBRE_PY = '/usr/local/calibre-web-py3/bin/python3';
const CALIBRE_DB = '/library/calibre-web/config/app.db';
// Same hash function as Calibre-Web's own `cps.py -s`, but it also sets the factory password, which
// Calibre-Web's password policy refuses. The password comes in the environment, not in argv.
const CALIBRE_SET = [
    'import os, sqlite3, sys',
    'from werkzeug.security import generate_password_hash',
    'db = sqlite3.connect(sys.argv[1])',
    'n = db.execute("UPDATE user SET password = ? WHERE lower(name) = lower(?)",',
    '    (generate_password_hash(os.environ["K2GO_PASSWORD"]), os.environ["K2GO_USER"])).rowcount',
    'db.commit()',
    'sys.exit(0 if n == 1 else 3)',
].join('\n');

// Exit 0: the account is present; exit 3: it is not. The user name comes in the environment.
const CALIBRE_HAS = [
    'import os, sqlite3, sys',
    'db = sqlite3.connect(sys.argv[1])',
    'r = db.execute("SELECT 1 FROM user WHERE lower(name) = lower(?)", (os.environ["K2GO_USER"],)).fetchone()',
    'sys.exit(0 if r else 3)',
].join('\n');

const KOLIBRI_SET = [
    'import os',
    'from kolibri.core.auth.models import FacilityUser',
    'u = FacilityUser.objects.get(username=os.environ["K2GO_USER"])',
    'u.set_password(os.environ["K2GO_PASSWORD"])',
    'u.save()',
].join('\n');

/** Sets a password with the service's own admin tool. Proven on a box: Kolibri 0.19, Calibre-Web
 *  0.6.28, Forgejo 17 (K2GO-552). */
export const setServicePassword: SetPassword = (service, username, password) => {
    const secret = { K2GO_USER: username, K2GO_PASSWORD: password };
    if (service === 'kolibri') {
        return run('kolibri', ['manage', 'shell', '-c', KOLIBRI_SET],
            { ...secret, KOLIBRI_HOME: process.env.KOLIBRI_HOME || '/library/kolibri/' });
    }
    if (service === 'calibre') return run(CALIBRE_PY, ['-c', CALIBRE_SET, CALIBRE_DB], secret);
    // The Forgejo CLI takes the password only in argv. Without --must-change-password=false it forces
    // a change at the next sign-in, and auto-login stops working.
    return run('/usr/sbin/runuser', ['-u', 'forgejo', '--', '/library/forgejo/forgejo',
        '--config', '/etc/forgejo/app.ini', '--work-path', '/library/forgejo',
        'admin', 'user', 'change-password', '-u', username, '-p', password,
        '--must-change-password=false']);
};

const DEFAULT_DEPS: PasswordDeps = { probe: probeLogin, setPassword: setServicePassword };

export type PasswordErrorCode =
    'weak_password' | 'busy' | 'env_managed' | 'mismatch' | 'no_account' | 'unreachable' | 'set_failed';

export class PasswordError extends Error {
    constructor(readonly code: PasswordErrorCode, message: string) {
        super(message);
        this.name = 'PasswordError';
    }
}

export function httpStatusFor(code: PasswordErrorCode): number {
    switch (code) {
        case 'weak_password': return 400;
        case 'busy': case 'env_managed': case 'mismatch': case 'no_account': return 409;
        case 'unreachable': return 503;
        default: return 500;
    }
}

export interface PasswordStatus {
    service: ServiceName;
    state: 'ok' | 'mismatch' | 'no_account' | 'unreachable';
    /** true: the service accepts its factory password. null: not known (not checked). */
    factoryDefault: boolean | null;
    /** The rule for a new password, so the app shows it from one source: the shortest length
     *  (minPasswordLength) and whether it needs a letter and a digit (hasLetterAndDigit). */
    minLength: number;
    letterAndDigit: boolean;
}

export async function passwordStatus(service: ServiceName, deps: PasswordDeps = DEFAULT_DEPS): Promise<PasswordStatus> {
    const cred = getCredential(service);
    const factory = factoryCredential(service);
    const minLength = minPasswordLength(service);
    const r = await deps.probe(service, cred);
    // On a mismatch the factory password is not tried: a second failed sign-in would use up
    // Calibre-Web's login limit, and the repair comes first anyway.
    if (r !== 'ok') {
        const state = r === 'rejected' ? 'mismatch' : r === 'absent' ? 'no_account' : 'unreachable';
        return { service, state, factoryDefault: null, minLength, letterAndDigit: true };
    }
    if (cred.username.toLowerCase() === factory.username.toLowerCase()) {
        return { service, state: 'ok', factoryDefault: cred.password === factory.password, minLength, letterAndDigit: true };
    }
    // The stored copy is another account: the factory account can still take the public password.
    const d = await deps.probe(service, factory);
    return { service, state: 'ok', factoryDefault: d === 'ok' ? true : d === 'rejected' ? false : null, minLength, letterAndDigit: true };
}

// One change per service at a time. In memory only: a restart clears it with the process.
const busy = new Set<ServiceName>();

async function exclusive<T>(service: ServiceName, work: () => Promise<T>): Promise<T> {
    if (busy.has(service)) throw new PasswordError('busy', `a password change for ${service} is running`);
    busy.add(service);
    try { return await work(); } finally { busy.delete(service); }
}

function notEnv(cred: { origin: string }, service: ServiceName): void {
    if (cred.origin === 'env') {
        throw new PasswordError('env_managed', `${service} sign-in comes from the environment`);
    }
}

/** Sets the password, then keeps the stored copy in step. The tool's exit code is the proof (each one
 *  exits non-zero when nothing changed); the sign-in after it only reports `verified`, because
 *  Calibre-Web answers a locked sign-in (after 3 failed tries in a minute) like a wrong password. */
async function apply(
    service: ServiceName, cred: Credential, save: () => void, deps: PasswordDeps,
): Promise<boolean> {
    try {
        await deps.setPassword(service, cred.username, cred.password);
    } catch (e) {
        throw new PasswordError('set_failed', e instanceof Error ? e.message : String(e));
    }
    save();
    return await deps.probe(service, cred) === 'ok';
}

/** Changes the admin password. The current password is the stored copy, checked live first.
 *  Resolves to `verified`. */
export async function changePassword(
    service: ServiceName, newPassword: string, deps: PasswordDeps = DEFAULT_DEPS,
): Promise<boolean> {
    if (!passwordFits(service, newPassword)) throw new PasswordError('weak_password', 'password too short');
    const cred = getCredential(service);
    notEnv(cred, service);
    return exclusive(service, async () => {
        const now = await deps.probe(service, cred);
        if (now === 'rejected') throw new PasswordError('mismatch', `${service} rejects the stored password`);
        if (now === 'absent') throw new PasswordError('no_account', `${service} has no admin account`);
        if (now === 'unreachable') throw new PasswordError('unreachable', `${service} does not respond`);
        const next = { username: cred.username, password: newPassword };
        return apply(service, next, () => setCredential(service, next), deps);
    });
}

/** Puts the factory password back in the service, also when nobody knows the current one.
 *  Resolves to `verified`. */
export async function resetPassword(service: ServiceName, deps: PasswordDeps = DEFAULT_DEPS): Promise<boolean> {
    notEnv(getCredential(service), service);
    return exclusive(service, () =>
        apply(service, factoryCredential(service), () => clearCredential(service), deps));
}
