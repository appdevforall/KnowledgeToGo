// sockets/forgejo.status.ts -- K2GO-422
//
// Read-only status of the box Forgejo, so the app can decide the post-install repos action
// (install repos / update repos / blocked) WITHOUT mutating anything. Every check is an in-process
// HTTP call to Forgejo's API (no CLI, no fork), so it works even when dash-node was relaunched
// off-proot (the fork-heavy seed path cannot -- ADR-5343). Talks to Forgejo DIRECTLY on :3300, like
// forgejo.session.ts (nginx strips the /forgejo prefix). Localhost-only, like all of /k2go-api.
import { getCredential } from './credentials';
import { FORGEJO_BASE } from './forgejo.session';

const API = `${FORGEJO_BASE}/api/v1`;
// The org the seed creates (static/forgejo/orchestration FORGEJO_ORG). Overridable to match the box.
const ORG = process.env.K2GO_FORGEJO_ORG || 'AppDevForAll';
const TIMEOUT_MS = 8000;

export interface ForgejoStatus {
    /** Forgejo answered at all. */
    reachable: boolean;
    /** The K2Go admin user (k2goadmin) is present, even if we cannot authenticate as it. */
    adminExists: boolean;
    /** We can authenticate as the K2Go admin with the credential we know (credentials.ts). */
    adminAuthenticable: boolean;
    /** Repo names under the org (empty when none, or when we cannot read them). */
    repos: string[];
    /**
     * K2Go can administer the forge: either we authenticate as the admin, or the admin does not
     * exist yet and the seed can create it. Blocked only when the admin EXISTS but we cannot
     * authenticate (its password was changed): we must not guess or touch a forge we do not control.
     */
    manageable: boolean;
}

function basicAuth(user: string, pass: string): string {
    return 'Basic ' + Buffer.from(`${user}:${pass}`).toString('base64');
}

async function getJson(url: string, headers: Record<string, string> = {}): Promise<Response> {
    return fetch(url, { headers: { Accept: 'application/json', ...headers }, signal: AbortSignal.timeout(TIMEOUT_MS) });
}

/** Probe the box Forgejo read-only. Never throws: an unreachable box returns all-false/empty. */
export async function forgejoStatus(): Promise<ForgejoStatus> {
    const cred = getCredential('forgejo');
    const auth = basicAuth(cred.username, cred.password);

    let reachable = false;
    let adminExists = false;
    let adminAuthenticable = false;
    let repos: string[] = [];

    // 1. Does the admin user exist? Public user endpoint, no auth: 200 = exists, 404 = absent.
    try {
        const r = await getJson(`${API}/users/${encodeURIComponent(cred.username)}`);
        reachable = true;
        adminExists = r.status === 200;
    } catch {
        return { reachable: false, adminExists: false, adminAuthenticable: false, repos: [], manageable: false };
    }

    // 2. Can we authenticate as the admin? An authed call to /user: 200 = yes, 401 = no.
    try {
        const r = await getJson(`${API}/user`, { Authorization: auth });
        adminAuthenticable = r.status === 200;
        // A definite 200 also proves the admin exists, even if step 1 was blocked by REQUIRE_SIGNIN_VIEW.
        if (adminAuthenticable) adminExists = true;
    } catch { /* leave false */ }

    // 3. Repos under the org (only when we can read them as the admin).
    if (adminAuthenticable) {
        try {
            const r = await getJson(`${API}/orgs/${encodeURIComponent(ORG)}/repos?limit=50`, { Authorization: auth });
            if (r.status === 200) {
                const body = await r.json();
                if (Array.isArray(body)) {
                    repos = body.map((x: any) => (x && typeof x.name === 'string' ? x.name : '')).filter((n: string) => n.length > 0);
                }
            }
        } catch { /* leave empty */ }
    }

    const manageable = adminAuthenticable || !adminExists;
    return { reachable, adminExists, adminAuthenticable, repos, manageable };
}
