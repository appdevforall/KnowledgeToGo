// sockets/forgejo.session.ts — authenticated web session against Forgejo (K2GO-212)
//
// Forgejo (a Gitea fork) uses a classic form login with a CSRF token, not a JSON
// API like Kolibri:
//   1. GET /user/login seeds the '_csrf' cookie and renders a hidden
//      <input name="_csrf" value="..."> whose value must match that cookie.
//   2. POST /user/login (form-encoded: _csrf, user_name, password) authenticates.
//      Success is a 302 redirect (with a new session cookie); a wrong password
//      re-renders the form with HTTP 200.
// The box talks to Forgejo DIRECTLY on :3300; nginx strips the /forgejo prefix
// (trailing-slash proxy_pass), so the paths carry no prefix here. The session
// cookie name is Forgejo's own (default 'i_like_gitea'); we do not hardcode it —
// we return the whole Set-Cookie jar and verify success by the redirect. The app
// re-homes the cookies to path=/ when it injects them (SessionCookies).
import { getCredential } from './credentials';
import { mergeCookies } from './kolibri.session';

export const FORGEJO_BASE = process.env.K2GO_FORGEJO_URL || 'http://127.0.0.1:3300';
const DEFAULT_TIMEOUT_MS = 8000;

export interface ForgejoSession {
    /** Cookie header, already assembled, to inject into the WebView. */
    cookie: string;
    username: string;
}

/** 'unreachable' -> 503 (service not ready), 'credentials' -> 401, 'protocol' -> 503. */
export type ForgejoAuthReason = 'unreachable' | 'credentials' | 'protocol';

export class ForgejoAuthError extends Error {
    readonly reason: ForgejoAuthReason;
    constructor(reason: ForgejoAuthReason, message: string) {
        super(message);
        this.name = 'ForgejoAuthError';
        this.reason = reason;
    }
}

async function fetchWithTimeout(
    url: string,
    init: RequestInit = {},
    timeoutMs = DEFAULT_TIMEOUT_MS,
): Promise<Response> {
    return fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
}

/** The hidden _csrf token in the login form; it must match the _csrf cookie. */
export function csrfFromHtml(html: string): string | null {
    const m = html.match(/name="_csrf"[^>]*\svalue="([^"]+)"/i)
        || html.match(/\svalue="([^"]+)"[^>]*\sname="_csrf"/i);
    return m ? m[1] : null;
}

/**
 * Authenticates against Forgejo and returns a reusable web session.
 *
 * @param override explicit credentials (used before persisting them); if omitted,
 *                 they are taken from the store.
 * @param userAgent the agent that will USE the session (the app's WebView), so the
 *                 login is minted for it (mirrors the Kolibri/Calibre endpoints).
 */
export async function login(
    override?: { username: string; password: string },
    userAgent?: string,
): Promise<ForgejoSession> {
    const cred = override ?? getCredential('forgejo');
    const agent: Record<string, string> = userAgent ? { 'User-Agent': userAgent } : {};

    // 1. Seed the _csrf cookie and read the matching form token.
    let cookie = '';
    let csrf: string | null = null;
    try {
        const seed = await fetchWithTimeout(`${FORGEJO_BASE}/user/login`, { headers: { ...agent } });
        cookie = mergeCookies('', seed.headers.getSetCookie());
        csrf = csrfFromHtml(await seed.text());
    } catch (e) {
        throw new ForgejoAuthError('unreachable',
            `Forgejo did not respond at ${FORGEJO_BASE}: ${e instanceof Error ? e.message : String(e)}`);
    }
    if (!csrf) {
        throw new ForgejoAuthError('protocol', 'Forgejo login page carried no _csrf token');
    }

    // 2. POST the login form. Do NOT follow the redirect: a 302/303 is success,
    //    a 200 means the form re-rendered (wrong credentials).
    let res: Response;
    try {
        const body = new URLSearchParams({
            _csrf: csrf,
            user_name: cred.username,
            password: cred.password,
        });
        res = await fetchWithTimeout(`${FORGEJO_BASE}/user/login`, {
            method: 'POST',
            redirect: 'manual',
            headers: {
                ...agent,
                'Content-Type': 'application/x-www-form-urlencoded',
                Cookie: cookie,
            },
            body: body.toString(),
        });
    } catch (e) {
        throw new ForgejoAuthError('unreachable',
            `Forgejo dropped the connection during login: ${e instanceof Error ? e.message : String(e)}`);
    }

    if (res.status === 200) {
        throw new ForgejoAuthError('credentials',
            `Forgejo rejected the credentials for '${cred.username}'`);
    }
    if (res.status !== 302 && res.status !== 303) {
        throw new ForgejoAuthError('protocol', `Forgejo login returned HTTP ${res.status}`);
    }

    cookie = mergeCookies(cookie, res.headers.getSetCookie());
    return { cookie, username: cred.username };
}
