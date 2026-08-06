// sockets/kolibri.session.ts — authenticated session against the Kolibri API
//
// Same pattern as getCalibreSession() in books.exec.ts: we authenticate against a
// local service over HTTP and keep cookie + CSRF. Kolibri differs from
// Calibre-Web in four things that break the first attempt if you do not know them:
//
//   1. The cookies are NOT the Django standard ones:
//        session → 'kolibri'             (not 'sessionid')
//        CSRF    → 'kolibri_csrftoken'   (not 'csrftoken')
//   2. Django ROTATES the CSRF token on login. It has to be re-read afterwards.
//   3. Login is JSON (not form-urlencoded like Calibre-Web) and returns
//      'can_manage_content', which is the permission actually needed to
//      import content: a user can authenticate and still NOT be able to import.
//   4. The viewset demands a same-origin 'Referer' (csrf_protect).
//
// The non-obvious proot requirement also lives here: 'importcontent' ALWAYS
// calls lookup_channel_listing_status(), which goes through
// NetworkClient.discover_from_address(). If no NetworkLocation exists whose
// base_url matches the content origin, that path falls to the URL-variations
// fallback, which invokes ifaddr.get_adapters() — and netlink is blocked
// under proot on Android >= 13, so it blows up after tens of seconds.
//
// IIAB solves that by seeding 'reserved' rows with `kolibri manage shell`.
// We do not need to: known_location_for_address() is
//     NetworkLocation.objects.filter(base_url__in=candidates).first()
// and does NOT filter by location_type. A 'static' NetworkLocation created over
// REST satisfies the lookup just as well, and StaticNetworkLocationViewSet is a
// full ModelViewSet. That is why ensureContentOrigin() can do it over HTTP alone.
import { getCredential } from './credentials';

export const KOLIBRI_BASE = process.env.K2GO_KOLIBRI_URL || 'http://127.0.0.1:8009';
export const STUDIO_URL = process.env.K2GO_STUDIO_URL
    || 'https://studio.learningequality.org';

const SESSION_COOKIE = 'kolibri';
const CSRF_COOKIE = 'kolibri_csrftoken';
const CSRF_HEADER = 'X-CSRFToken';

/** Loopback: generous for Django's startup, short enough not to hang the UI. */
const DEFAULT_TIMEOUT_MS = 8000;

export interface KolibriSession {
    /** Cookie header, already assembled, to reuse on every request. */
    cookie: string;
    csrfToken: string;
    username: string;
    canManageContent: boolean;
}

/** Error with a distinguishable cause, so the routes return the right status:
 *  'unreachable' → 503 (the service is not ready; a startup-layer problem)
 *  'credentials' → 401 (wrong username or password)
 *  'permission'  → 403 (authenticates, but cannot manage content) */
export type KolibriAuthReason = 'unreachable' | 'credentials' | 'permission' | 'protocol';

export class KolibriAuthError extends Error {
    readonly reason: KolibriAuthReason;
    constructor(reason: KolibriAuthReason, message: string) {
        super(message);
        this.name = 'KolibriAuthError';
        this.reason = reason;
    }
}

/** Extracts a cookie value from the Set-Cookie headers of a response. */
export function cookieValue(setCookies: string[], name: string): string | null {
    for (const raw of setCookies) {
        const first = raw.split(';')[0];
        const eq = first.indexOf('=');
        if (eq < 0) continue;
        if (first.slice(0, eq).trim() === name) return first.slice(eq + 1).trim();
    }
    return null;
}

/** Merges name=value pairs into a single Cookie header, the last one winning.
 *  Exported because it is pure logic and is tested without the network. */
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
    // AbortSignal.timeout exists in Node 18+; the rootfs runs Node 22.
    return fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
}

/**
 * Authenticates against Kolibri and returns a reusable session.
 *
 * @param override explicit credentials (used by the validation endpoint before
 *                 persisting them); if omitted, they are taken from the store.
 */
export async function login(
    override?: { username: string; password: string },
): Promise<KolibriSession> {
    const cred = override ?? getCredential('kolibri');

    // 1. Seed the CSRF cookie. The session viewset carries @ensure_csrf_cookie,
    //    so a GET is enough.
    let cookie = '';
    let csrfToken: string | null = null;
    try {
        const seed = await fetchWithTimeout(`${KOLIBRI_BASE}/api/auth/session/current/`);
        const setCookies = seed.headers.getSetCookie();
        cookie = mergeCookies('', setCookies);
        csrfToken = cookieValue(setCookies, CSRF_COOKIE);
    } catch (e) {
        throw new KolibriAuthError('unreachable',
            `Kolibri did not respond at ${KOLIBRI_BASE}: ${e instanceof Error ? e.message : String(e)}`);
    }
    if (!csrfToken) {
        throw new KolibriAuthError('protocol',
            `Kolibri did not return the ${CSRF_COOKIE} cookie`);
    }

    // 2. Login. JSON, with a same-origin Referer (csrf_protect demands it).
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
            `Kolibri dropped the connection during login: ${e instanceof Error ? e.message : String(e)}`);
    }

    if (res.status === 401) {
        // 401 also shows up in "app mode" if allow_other_browsers_to_connect=False
        // and there is no app-key cookie. On an nginx-served box it does not apply,
        // but the message mentions it so the diagnosis does not take hours.
        throw new KolibriAuthError('credentials',
            `Kolibri rejected the credentials for '${cred.username}'. `
            + 'If the device is in app mode, check allow_other_browsers_to_connect.');
    }
    if (res.status === 400) {
        // 400 is the serializer rejecting the request (missing field, unknown
        // facility...), not a wrong password. Reporting it as an invalid credential
        // sends the operator off to change a password that was already fine.
        const detail = await res.text().catch(() => '');
        throw new KolibriAuthError('protocol',
            `Kolibri rejected the login request (HTTP 400), not the credentials: `
            + (detail.slice(0, 200) || 'no detail'));
    }
    if (!res.ok) {
        throw new KolibriAuthError('protocol', `Login returned HTTP ${res.status}`);
    }

    // 3. Django rotates the CSRF token on login: it has to be re-read or the first
    //    POST after it will fail with 403.
    const loginCookies = res.headers.getSetCookie();
    cookie = mergeCookies(cookie, loginCookies);
    csrfToken = cookieValue(loginCookies, CSRF_COOKIE) ?? csrfToken;

    if (!cookie.includes(`${SESSION_COOKIE}=`)) {
        throw new KolibriAuthError('protocol',
            `Login did not return the session cookie '${SESSION_COOKIE}'`);
    }

    let canManageContent = false;
    let username = cred.username;
    try {
        const data = await res.json() as Record<string, unknown>;
        canManageContent = data.can_manage_content === true;
        if (typeof data.username === 'string') username = data.username;
    } catch {
        // Unexpected body: we do not invalidate the session, but the permission stays
        // false and the caller decides. Better that than assuming it can.
    }

    return { cookie, csrfToken, username, canManageContent };
}

/** Same as login(), but also demands the content-management permission.
 *  Used by the runner: without can_manage_content, /api/tasks/ rejects the job. */
export async function loginForContent(
    override?: { username: string; password: string },
): Promise<KolibriSession> {
    const session = await login(override);
    if (!session.canManageContent) {
        throw new KolibriAuthError('permission',
            `User '${session.username}' authenticates but does not have can_manage_content, `
            + 'so it cannot import content.');
    }
    return session;
}

/** Authenticated request. Re-injects cookie + CSRF + Referer on every call. */
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

/** Error from an API call that did receive a response. It carries the status so the
 *  caller can tell an "expired session" (401/403) from any other failure. */
export class KolibriApiError extends Error {
    readonly status: number;
    constructor(status: number, message: string) {
        super(message);
        this.name = 'KolibriApiError';
        this.status = status;
    }
    /** Django answers 403 (not 401) to an expired session with an invalid CSRF. */
    get isAuthExpired(): boolean {
        return this.status === 401 || this.status === 403;
    }
}

/** apiFetch + JSON parsing + readable error. */
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
        throw new Error(`${pathname} returned something that is not JSON: ${text.slice(0, 200)}`);
    }
}

// ─── Content origin (the proot prerequisite) ────────────────────────────────────

interface NetworkLocationRow {
    id?: string;
    base_url?: string;
    nickname?: string;
}

/** Variants Kolibri treats as equivalent: with and without a trailing slash. */
function urlVariants(url: string): string[] {
    const trimmed = url.replace(/\/+$/, '');
    return [trimmed, `${trimmed}/`];
}

/** Does any of the rows match the origin we want? Pure, testable without a network. */
export function matchesOrigin(rows: NetworkLocationRow[], baseUrl: string): boolean {
    const wanted = new Set(urlVariants(baseUrl));
    return rows.some((row) => {
        if (!row.base_url) return false;
        return wanted.has(row.base_url) || wanted.has(row.base_url.replace(/\/+$/, ''));
    });
}

/**
 * Is there already a NetworkLocation for the content origin? READ ONLY.
 *
 * Any location_type counts, including the 'reserved' rows IIAB seeds: Kolibri's
 * own lookup (known_location_for_address) does not filter by type either.
 *
 * @returns null if it could not be determined (Kolibri did not answer the listing).
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
 * Guarantees a NetworkLocation exists for the content origin. THIS WRITES.
 *
 * Without this, 'importcontent' falls to the ifaddr fallback and fails under proot.
 * The runner calls it, not the diagnostic endpoints: a GET must not mutate state.
 *
 * @returns 'present' if it already existed, 'created' if it created it, 'failed'
 *          if it could not (does not throw: the import may still work if IIAB
 *          already seeded the row and only our check failed).
 */
export async function ensureContentOrigin(
    session: KolibriSession,
    baseUrl: string = STUDIO_URL,
): Promise<'present' | 'created' | 'failed'> {
    // null (the listing failed) falls through to the POST: creating is idempotent
    // in practice, because a duplicate does not break the lookup.
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
        // 400 usually means "already exists" (unique) or serializer validation.
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
    /** true present, false absent, null undetermined. Only read, never created
     *  from here: the runner is the one that guarantees it before importing. */
    contentOrigin: boolean | null;
    version: string | null;
    /** Reasons why ready is false, in actionable language. */
    blockers: string[];
    credentialOrigin: string;
}

/**
 * The "gate" the startup layer can poll: as long as it does not return
 * ready=true, there is no point launching content jobs.
 *
 * It throws no exceptions and MUTATES NOTHING: it always returns a diagnosis. It
 * can be polled in a loop with no side effects.
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

    // 1. Does it respond? /api/public/info needs no authentication and is cheap.
    try {
        const info = await fetchWithTimeout(`${KOLIBRI_BASE}/api/public/info`, {}, 5000);
        out.reachable = info.ok;
        if (info.ok) {
            const body = await info.json() as Record<string, unknown>;
            if (typeof body.kolibri_version === 'string') out.version = body.kolibri_version;
        } else {
            out.blockers.push(`Kolibri returned HTTP ${info.status} at /api/public/info`);
        }
    } catch (e) {
        out.blockers.push(`Kolibri is not responding at ${KOLIBRI_BASE}`);
        return out;   // with no service there is nothing else to check
    }

    // 2. Does it authenticate and can it manage content?
    let session: KolibriSession;
    try {
        session = await login();
        out.authenticated = true;
        out.canManageContent = session.canManageContent;
        if (!session.canManageContent) {
            out.blockers.push(
                `'${session.username}' authenticates but lacks can_manage_content`);
        }
    } catch (e) {
        const reason = e instanceof KolibriAuthError ? e.reason : 'protocol';
        out.blockers.push(reason === 'credentials'
            ? 'Wrong Kolibri credentials (update them at /credentials/kolibri)'
            : `Login against Kolibri failed: ${e instanceof Error ? e.message : String(e)}`);
        return out;
    }

    // 3. Is the device provisioned? Without this the UI would show the wizard.
    try {
        const dev = await apiJson<Record<string, unknown>>(session, '/api/device/deviceinfo/');
        // The endpoint only responds on a provisioned device; an answer is itself
        // the signal. We keep the name if it comes, for diagnostics.
        out.provisioned = !!dev;
    } catch {
        out.provisioned = null;   // inconclusive: we do not mark it as a blocker
    }

    // 4. The proot prerequisite, in read mode. Not a blocker: the runner creates
    //    the row if it is missing (ensureContentOrigin) before queueing, so its
    //    absence here does not prevent starting. Reported for diagnostics.
    out.contentOrigin = await hasContentOrigin(session);

    out.ready = out.blockers.length === 0;
    return out;
}
