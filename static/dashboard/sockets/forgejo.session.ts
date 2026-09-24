// sockets/forgejo.session.ts — authenticated web session against Forgejo (K2GO-212)
//
// Forgejo's login is a form POST. Unlike older Gitea, Forgejo 15's login form has
// NO _csrf field and the login route is CSRF-exempt (verified on device: a POST of
// just user_name + password returns 303 and the session cookie). So we do not seed
// or send a CSRF token:
//   POST /user/login (form-encoded: user_name, password)
//     success -> 303/302 redirect to the dashboard, with a 'session' cookie;
//     wrong password -> the form re-renders with HTTP 200.
// The box talks to Forgejo DIRECTLY on :3300 (nginx strips the /forgejo prefix via
// its trailing-slash proxy_pass), so the paths carry no prefix here. We return the
// whole Set-Cookie jar (the session cookie name is Forgejo's own) and verify by the
// redirect; the app re-homes the cookies to path=/ when it injects them (SessionCookies).
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

/**
 * Drops "clear" directives (empty value, e.g. `session=; Max-Age=0`) from a Set-Cookie list.
 * Forgejo rotates the session by clearing it at one path and setting it at another in the SAME
 * response; mergeCookies keys by name only, so without this a last-wins merge could keep the empty
 * clear and inject a logged-out cookie. Keeping only non-empty values makes the merge order-independent.
 */
function dropClears(setCookies: string[]): string[] {
    return setCookies.filter((raw) => {
        const first = raw.split(';')[0];
        const eq = first.indexOf('=');
        return eq >= 0 && first.slice(eq + 1).trim() !== '';
    });
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

    // Seed any cookies the login page sets (harmless; login itself is CSRF-exempt).
    let cookie = '';
    try {
        const seed = await fetchWithTimeout(`${FORGEJO_BASE}/user/login`, { headers: { ...agent } });
        cookie = mergeCookies('', dropClears(seed.headers.getSetCookie()));
    } catch (e) {
        throw new ForgejoAuthError('unreachable',
            `Forgejo did not respond at ${FORGEJO_BASE}: ${e instanceof Error ? e.message : String(e)}`);
    }

    // POST the login form. Do NOT follow the redirect: a 302/303 is success, a 200
    // means the form re-rendered (wrong credentials).
    let res: Response;
    try {
        const body = new URLSearchParams({ user_name: cred.username, password: cred.password });
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

    cookie = mergeCookies(cookie, dropClears(res.headers.getSetCookie()));
    return { cookie, username: cred.username };
}
