// sockets/kolibri.session.ts — sesión autenticada contra la API de Kolibri
//
// Mismo patrón que getCalibreSession() en books.exec.ts: nos autenticamos contra un
// servicio local por HTTP y conservamos cookie + CSRF. Kolibri difiere de
// Calibre-Web en cuatro cosas que hacen fallar el primer intento si no se saben:
//
//   1. Las cookies NO son las estándar de Django:
//        sesión → 'kolibri'             (no 'sessionid')
//        CSRF   → 'kolibri_csrftoken'   (no 'csrftoken')
//   2. Django ROTA el token CSRF al hacer login. Hay que releerlo después.
//   3. El login es JSON (no form-urlencoded como Calibre-Web) y devuelve
//      'can_manage_content', que es el permiso que de verdad hace falta para
//      importar contenido: un usuario puede autenticarse y NO poder importar.
//   4. El viewset exige 'Referer' del mismo origen (csrf_protect).
//
// Además aquí vive el requisito de proot que no es obvio: 'importcontent' llama
// SIEMPRE a lookup_channel_listing_status(), que pasa por
// NetworkClient.discover_from_address(). Si no existe una NetworkLocation cuyo
// base_url coincida con el origen del contenido, ese camino cae al fallback de
// variaciones de URL, que invoca ifaddr.get_adapters() — y netlink está bloqueado
// bajo proot en Android >= 13, así que revienta tras decenas de segundos.
//
// En IIAB eso se resuelve sembrando filas 'reserved' con `kolibri manage shell`.
// No lo necesitamos: known_location_for_address() es
//     NetworkLocation.objects.filter(base_url__in=candidates).first()
// y NO filtra por location_type. Una NetworkLocation 'static' creada por REST
// satisface el lookup igual, y StaticNetworkLocationViewSet es un ModelViewSet
// completo. Por eso ensureContentOrigin() puede hacerlo sin salir de HTTP.
import { getCredential } from './credentials';

export const KOLIBRI_BASE = process.env.K2GO_KOLIBRI_URL || 'http://127.0.0.1:8009';
export const STUDIO_URL = process.env.K2GO_STUDIO_URL
    || 'https://studio.learningequality.org';

const SESSION_COOKIE = 'kolibri';
const CSRF_COOKIE = 'kolibri_csrftoken';
const CSRF_HEADER = 'X-CSRFToken';

/** Loopback: generoso para el arranque de Django, corto para no colgar la UI. */
const DEFAULT_TIMEOUT_MS = 8000;

export interface KolibriSession {
    /** Cabecera Cookie ya montada para reusar en cada petición. */
    cookie: string;
    csrfToken: string;
    username: string;
    canManageContent: boolean;
}

/** Error con la causa distinguible, para que las rutas devuelvan el status correcto:
 *  'unreachable' → 503 (el servicio no está listo; problema de la capa de arranque)
 *  'credentials' → 401 (usuario o contraseña incorrectos)
 *  'permission'  → 403 (autentica, pero no puede gestionar contenido) */
export type KolibriAuthReason = 'unreachable' | 'credentials' | 'permission' | 'protocol';

export class KolibriAuthError extends Error {
    readonly reason: KolibriAuthReason;
    constructor(reason: KolibriAuthReason, message: string) {
        super(message);
        this.name = 'KolibriAuthError';
        this.reason = reason;
    }
}

/** Extrae el valor de una cookie de las cabeceras Set-Cookie de una respuesta. */
export function cookieValue(setCookies: string[], name: string): string | null {
    for (const raw of setCookies) {
        const first = raw.split(';')[0];
        const eq = first.indexOf('=');
        if (eq < 0) continue;
        if (first.slice(0, eq).trim() === name) return first.slice(eq + 1).trim();
    }
    return null;
}

/** Fusiona pares nombre=valor en una sola cabecera Cookie, con el último ganando.
 *  Exportada porque es lógica pura y se testea sin red. */
export function mergeCookies(existing: string, setCookies: string[]): string {
    const jar = new Map<string, string>();
    for (const part of existing.split(';')) {
        const p = part.trim();
        if (!p) continue;
        const eq = p.indexOf('=');
        if (eq > 0) jar.set(p.slice(0, eq).trim(), p.slice(eq + 1).trim());
    }
    for (const raw of setCookies) {
        const first = raw.split(';')[0];
        const eq = first.indexOf('=');
        if (eq > 0) jar.set(first.slice(0, eq).trim(), first.slice(eq + 1).trim());
    }
    return [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
}

async function fetchWithTimeout(
    url: string,
    init: RequestInit = {},
    timeoutMs = DEFAULT_TIMEOUT_MS,
): Promise<Response> {
    // AbortSignal.timeout existe en Node 18+; el rootfs corre Node 22.
    return fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
}

/**
 * Autentica contra Kolibri y devuelve una sesión reutilizable.
 *
 * @param override credenciales explícitas (las usa el endpoint de validación antes
 *                 de persistirlas); si se omite, se toman del almacén.
 */
export async function login(
    override?: { username: string; password: string },
): Promise<KolibriSession> {
    const cred = override ?? getCredential('kolibri');

    // 1. Sembrar la cookie CSRF. El viewset de sesión lleva @ensure_csrf_cookie,
    //    así que un GET basta.
    let cookie = '';
    let csrfToken: string | null = null;
    try {
        const seed = await fetchWithTimeout(`${KOLIBRI_BASE}/api/auth/session/current/`);
        const setCookies = seed.headers.getSetCookie();
        cookie = mergeCookies('', setCookies);
        csrfToken = cookieValue(setCookies, CSRF_COOKIE);
    } catch (e) {
        throw new KolibriAuthError('unreachable',
            `Kolibri no respondió en ${KOLIBRI_BASE}: ${e instanceof Error ? e.message : String(e)}`);
    }
    if (!csrfToken) {
        throw new KolibriAuthError('protocol',
            `Kolibri no devolvió la cookie ${CSRF_COOKIE}`);
    }

    // 2. Login. JSON, con Referer del mismo origen (csrf_protect lo exige).
    let res: Response;
    try {
        res = await fetchWithTimeout(`${KOLIBRI_BASE}/api/auth/session/`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                Cookie: cookie,
                [CSRF_HEADER]: csrfToken,
                Referer: `${KOLIBRI_BASE}/`,
            },
            body: JSON.stringify({ username: cred.username, password: cred.password }),
        });
    } catch (e) {
        throw new KolibriAuthError('unreachable',
            `Kolibri cortó la conexión durante el login: ${e instanceof Error ? e.message : String(e)}`);
    }

    if (res.status === 401 || res.status === 400) {
        // 401 también aparece en "app mode" si allow_other_browsers_to_connect=False
        // y no hay cookie de app-key. En un box servido por nginx no aplica, pero el
        // mensaje lo menciona para que el diagnóstico no lleve horas.
        throw new KolibriAuthError('credentials',
            `Kolibri rechazó las credenciales de '${cred.username}' (HTTP ${res.status}). `
            + 'Si el dispositivo está en modo aplicación, revisa allow_other_browsers_to_connect.');
    }
    if (!res.ok) {
        throw new KolibriAuthError('protocol', `Login devolvió HTTP ${res.status}`);
    }

    // 3. Django rota el token CSRF al hacer login: hay que releerlo o el primer
    //    POST posterior fallará con 403.
    const loginCookies = res.headers.getSetCookie();
    cookie = mergeCookies(cookie, loginCookies);
    csrfToken = cookieValue(loginCookies, CSRF_COOKIE) ?? csrfToken;

    if (!cookie.includes(`${SESSION_COOKIE}=`)) {
        throw new KolibriAuthError('protocol',
            `El login no devolvió la cookie de sesión '${SESSION_COOKIE}'`);
    }

    let canManageContent = false;
    let username = cred.username;
    try {
        const data = await res.json() as Record<string, unknown>;
        canManageContent = data.can_manage_content === true;
        if (typeof data.username === 'string') username = data.username;
    } catch {
        // Cuerpo inesperado: no invalidamos la sesión, pero el permiso queda en false
        // y el llamador decidirá. Mejor eso que asumir que sí puede.
    }

    return { cookie, csrfToken, username, canManageContent };
}

/** Igual que login(), pero exige además el permiso de gestión de contenido.
 *  Es lo que usa el runner: sin can_manage_content, /api/tasks/ rechazará el job. */
export async function loginForContent(
    override?: { username: string; password: string },
): Promise<KolibriSession> {
    const session = await login(override);
    if (!session.canManageContent) {
        throw new KolibriAuthError('permission',
            `El usuario '${session.username}' autentica pero no tiene can_manage_content, `
            + 'así que no puede importar contenido.');
    }
    return session;
}

/** Petición autenticada. Reinyecta cookie + CSRF + Referer en cada llamada. */
export async function apiFetch(
    session: KolibriSession,
    pathname: string,
    init: RequestInit = {},
    timeoutMs = DEFAULT_TIMEOUT_MS,
): Promise<Response> {
    const headers: Record<string, string> = {
        Cookie: session.cookie,
        Referer: `${KOLIBRI_BASE}/`,
        Accept: 'application/json',
        ...(init.headers as Record<string, string> | undefined),
    };
    const method = (init.method ?? 'GET').toUpperCase();
    if (method !== 'GET' && method !== 'HEAD') {
        headers[CSRF_HEADER] = session.csrfToken;
        if (init.body !== undefined && !headers['Content-Type']) {
            headers['Content-Type'] = 'application/json';
        }
    }
    return fetchWithTimeout(`${KOLIBRI_BASE}${pathname}`, { ...init, headers }, timeoutMs);
}

/** Error de una llamada a la API que llegó a recibir respuesta. Lleva el status para
 *  que el llamador distinga "sesión caducada" (401/403) de un fallo cualquiera. */
export class KolibriApiError extends Error {
    readonly status: number;
    constructor(status: number, message: string) {
        super(message);
        this.name = 'KolibriApiError';
        this.status = status;
    }
    /** Django responde 403 (no 401) a una sesión caducada con CSRF ya inválido. */
    get isAuthExpired(): boolean {
        return this.status === 401 || this.status === 403;
    }
}

/** apiFetch + parseo JSON + error legible. */
export async function apiJson<T>(
    session: KolibriSession,
    pathname: string,
    init: RequestInit = {},
    timeoutMs = DEFAULT_TIMEOUT_MS,
): Promise<T> {
    const res = await apiFetch(session, pathname, init, timeoutMs);
    const text = await res.text();
    if (!res.ok) {
        throw new KolibriApiError(res.status,
            `${init.method ?? 'GET'} ${pathname} → HTTP ${res.status}: ${text.slice(0, 400)}`);
    }
    if (!text.trim()) return undefined as unknown as T;
    try {
        return JSON.parse(text) as T;
    } catch {
        throw new Error(`${pathname} devolvió algo que no es JSON: ${text.slice(0, 200)}`);
    }
}

// ─── Origen de contenido (el prerrequisito de proot) ────────────────────────────

interface NetworkLocationRow {
    id?: string;
    base_url?: string;
    nickname?: string;
}

/** Variaciones que Kolibri considera equivalentes: con y sin barra final. */
function urlVariants(url: string): string[] {
    const trimmed = url.replace(/\/+$/, '');
    return [trimmed, `${trimmed}/`];
}

/** ¿Coincide alguna de las filas con el origen buscado? Pura, testeable sin red. */
export function matchesOrigin(rows: NetworkLocationRow[], baseUrl: string): boolean {
    const wanted = new Set(urlVariants(baseUrl));
    return rows.some((row) => {
        if (!row.base_url) return false;
        return wanted.has(row.base_url) || wanted.has(row.base_url.replace(/\/+$/, ''));
    });
}

/**
 * ¿Existe ya una NetworkLocation para el origen de contenido? SOLO LECTURA.
 *
 * Cuenta cualquier location_type, incluidas las 'reserved' que siembra IIAB: el
 * lookup de Kolibri (known_location_for_address) tampoco filtra por tipo.
 *
 * @returns null si no se pudo determinar (Kolibri no respondió al listado).
 */
export async function hasContentOrigin(
    session: KolibriSession,
    baseUrl: string = STUDIO_URL,
): Promise<boolean | null> {
    try {
        const rows = await apiJson<NetworkLocationRow[] | { results?: NetworkLocationRow[] }>(
            session, '/api/discovery/networklocation/');
        const list = Array.isArray(rows) ? rows : (rows.results ?? []);
        return matchesOrigin(list, baseUrl);
    } catch {
        return null;
    }
}

/**
 * Garantiza que exista una NetworkLocation para el origen de contenido. ESCRIBE.
 *
 * Sin esto, 'importcontent' cae al fallback que llama a ifaddr y falla bajo proot.
 * Lo llama el runner, no los endpoints de diagnóstico: un GET no debe mutar estado.
 *
 * @returns 'present' si ya existía, 'created' si la creó, 'failed' si no pudo
 *          (no lanza: el import puede funcionar igual si IIAB ya sembró y solo
 *          falló nuestra comprobación).
 */
export async function ensureContentOrigin(
    session: KolibriSession,
    baseUrl: string = STUDIO_URL,
): Promise<'present' | 'created' | 'failed'> {
    // null (no se pudo listar) cae al POST: crear es idempotente en la práctica,
    // porque un duplicado no rompe el lookup.
    if (await hasContentOrigin(session, baseUrl) === true) return 'present';

    try {
        const res = await apiFetch(session, '/api/discovery/staticnetworklocation/', {
            method: 'POST',
            body: JSON.stringify({
                base_url: baseUrl,
                nickname: 'K2Go content origin',
            }),
        }, 15000);
        if (res.ok) return 'created';
        // 400 suele significar "ya existe" (unique) o validación del serializer.
        return 'failed';
    } catch {
        return 'failed';
    }
}

// ─── Readiness ────────────────────────────────────────────────────────────────

export interface KolibriReadiness {
    ready: boolean;
    reachable: boolean;
    authenticated: boolean;
    canManageContent: boolean;
    provisioned: boolean | null;
    /** true presente, false ausente, null indeterminado. Solo se consulta, nunca
     *  se crea desde aquí: el runner es quien lo garantiza antes de importar. */
    contentOrigin: boolean | null;
    version: string | null;
    /** Motivos por los que ready es false, en lenguaje accionable. */
    blockers: string[];
    credentialOrigin: string;
}

/**
 * El "gate" que la capa de arranque puede sondear: mientras no devuelva
 * ready=true, no tiene sentido lanzar jobs de contenido.
 *
 * No lanza excepciones y NO MUTA NADA: siempre devuelve un diagnóstico. Se puede
 * sondear en bucle sin efectos secundarios.
 */
export async function checkReadiness(): Promise<KolibriReadiness> {
    const out: KolibriReadiness = {
        ready: false,
        reachable: false,
        authenticated: false,
        canManageContent: false,
        provisioned: null,
        contentOrigin: null,
        version: null,
        blockers: [],
        credentialOrigin: getCredential('kolibri').origin,
    };

    // 1. ¿Responde? /api/public/info no requiere autenticación y es barato.
    try {
        const info = await fetchWithTimeout(`${KOLIBRI_BASE}/api/public/info`, {}, 5000);
        out.reachable = info.ok;
        if (info.ok) {
            const body = await info.json() as Record<string, unknown>;
            if (typeof body.kolibri_version === 'string') out.version = body.kolibri_version;
        } else {
            out.blockers.push(`Kolibri respondió HTTP ${info.status} en /api/public/info`);
        }
    } catch (e) {
        out.blockers.push(`Kolibri no responde en ${KOLIBRI_BASE}`);
        return out;   // sin servicio no hay nada más que comprobar
    }

    // 2. ¿Autentica y puede gestionar contenido?
    let session: KolibriSession;
    try {
        session = await login();
        out.authenticated = true;
        out.canManageContent = session.canManageContent;
        if (!session.canManageContent) {
            out.blockers.push(
                `'${session.username}' autentica pero no tiene can_manage_content`);
        }
    } catch (e) {
        const reason = e instanceof KolibriAuthError ? e.reason : 'protocol';
        out.blockers.push(reason === 'credentials'
            ? 'Credenciales de Kolibri incorrectas (actualízalas en /credentials/kolibri)'
            : `Login contra Kolibri falló: ${e instanceof Error ? e.message : String(e)}`);
        return out;
    }

    // 3. ¿Está provisionado el dispositivo? Sin esto la UI mostraría el asistente.
    try {
        const dev = await apiJson<Record<string, unknown>>(session, '/api/device/deviceinfo/');
        // El endpoint solo responde en un dispositivo provisionado; que conteste ya
        // es la señal. Guardamos el nombre si viene, por diagnóstico.
        out.provisioned = !!dev;
    } catch {
        out.provisioned = null;   // no concluyente: no lo marcamos como bloqueador
    }

    // 4. El prerrequisito de proot, en modo consulta. No es un bloqueador: el runner
    //    crea la fila si falta (ensureContentOrigin) antes de encolar, así que su
    //    ausencia aquí no impide arrancar. Se reporta para diagnóstico.
    out.contentOrigin = await hasContentOrigin(session);

    out.ready = out.blockers.length === 0;
    return out;
}
