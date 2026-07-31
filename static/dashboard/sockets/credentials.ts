// sockets/credentials.ts — almacén de credenciales de servicios del box
//
// Motivación: las credenciales de los servicios locales estaban embebidas en el
// código (books.exec.ts:15-16, books.query.ts:22, con el comentario "a credential
// override can be added later if needed"). Este módulo es ese override: si el
// operador cambia la contraseña de Kolibri en el dispositivo, se actualiza aquí y
// el mecanismo sigue funcionando sin recompilar ni redeployar.
//
// Precedencia (gana el primero que exista):
//   1. variable de entorno   K2GO_<SERVICIO>_USER / K2GO_<SERVICIO>_PASS
//   2. override persistido   /library/dashboard/credentials.json
//   3. default de fábrica    Admin / changeme (lo que provisiona Ansible en IIAB)
//
// El fichero se escribe con 0600 y de forma atómica (write + rename), para que un
// corte a media escritura no deje un JSON truncado que impida autenticarse.
//
// Nota de alcance: por ahora solo Kolibri lo consume. Los runners de books siguen
// con sus constantes; migrarlos es un cambio aparte porque toca código que ya
// funciona en producción.
import fs from 'fs';
import path from 'path';

export type ServiceName = 'kolibri' | 'calibre';

export interface Credential {
    username: string;
    password: string;
}

/** De dónde salió la credencial que se está usando. Útil para diagnóstico. */
export type CredentialOrigin = 'env' | 'override' | 'default';

export interface ResolvedCredential extends Credential {
    origin: CredentialOrigin;
}

const STORE_PATH = process.env.K2GO_CREDENTIALS_FILE
    || '/library/dashboard/credentials.json';

/** Lo que provisiona el rol de Ansible de IIAB en el build del rootfs. */
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

/** Lectura tolerante: un fichero ausente o corrupto degrada a los defaults, no
 *  rompe el arranque del dashboard. */
function readStore(): Store {
    try {
        const raw = fs.readFileSync(STORE_PATH, 'utf8');
        const parsed = JSON.parse(raw) as unknown;
        if (!parsed || typeof parsed !== 'object') return {};
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
    } catch {
        return {};
    }
}

function writeStore(store: Store): void {
    const dir = path.dirname(STORE_PATH);
    fs.mkdirSync(dir, { recursive: true });
    // Escritura atómica: si el proceso muere a medias, el fichero antiguo sigue
    // siendo válido en lugar de quedar un JSON a medio escribir.
    const tmp = `${STORE_PATH}.tmp-${process.pid}`;
    fs.writeFileSync(tmp, JSON.stringify(store, null, 2) + '\n', { mode: 0o600 });
    fs.renameSync(tmp, STORE_PATH);
    try { fs.chmodSync(STORE_PATH, 0o600); } catch { /* best effort */ }
}

/** La credencial efectiva de un servicio, con su procedencia. */
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

/** Persiste un override. NO valida contra el servicio: eso lo hace la ruta REST,
 *  que solo llama aquí después de un login correcto. */
export function setCredential(service: ServiceName, cred: Credential): void {
    const username = cred.username.trim();
    if (!username) throw new Error('username required');
    if (!cred.password) throw new Error('password required');
    const store = readStore();
    store[service] = { username, password: cred.password };
    writeStore(store);
}

/** Vuelve al default de fábrica (o a la variable de entorno, si está puesta). */
export function clearCredential(service: ServiceName): void {
    const store = readStore();
    delete store[service];
    writeStore(store);
}

/** Vista segura para la UI: nunca devuelve la contraseña, solo si hay una y de
 *  dónde viene. Es lo que el webview necesita para pintar el formulario. */
export function describeCredential(service: ServiceName): {
    service: ServiceName;
    username: string;
    origin: CredentialOrigin;
    isDefault: boolean;
} {
    const c = getCredential(service);
    return {
        service,
        username: c.username,
        origin: c.origin,
        // Señal para que la UI pueda avisar "sigues con la contraseña de fábrica".
        isDefault: c.origin === 'default'
            || (c.username === DEFAULTS[service].username
                && c.password === DEFAULTS[service].password),
    };
}

export const CREDENTIALS_STORE_PATH = STORE_PATH;
