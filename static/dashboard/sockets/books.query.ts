// sockets/books.query.ts — ADFA-4850
//
// Direct (non-job) REST helpers for Books, ported from the socket.io handlers in
// books.socket.ts so the durable REST engine covers the whole flow:
//   - searchCatalog(): FTS over the synced OFFLINE Gutenberg catalog.db (no internet needed
//     to search; only covers + the actual EPUB download need internet).
//   - listLibrary(): the local Calibre-Web library (EPUB books) for "Your books" / Read a Book.
//   - removeBook(): delete from Calibre-Web.
// The cover URL is DERIVED from the Gutenberg id (standard Gutenberg cover path) so we never
// bloat the catalog with image blobs; the client loads it online with an offline fallback.
import Database from 'better-sqlite3';
import { getCredential } from './credentials';
import fs from 'fs';
import path from 'path';

const CALIBRE_LIB_PATH = '/library/calibre-web/';
const CALIBRE_DB_PATH = path.join(CALIBRE_LIB_PATH, 'metadata.db');
const BOOKS_DIR = '/library/dashboard/books/';
const CATALOG_DB_PATH = path.join(BOOKS_DIR, 'catalog.db');

const CALIBRE_WEB_LOCAL_URL = 'http://127.0.0.1:8083';
// ADFA-4949: credentials come from the shared store (see sockets/credentials.ts).

/**
 * ADFA-4893: normalized titles already in the Calibre-Web library, so the books runner is idempotent —
 * a resume (or a re-run after process death) skips books already uploaded instead of duplicating them.
 * Title is the only reliable key today (Gutenberg id isn't persisted as a Calibre identifier). Returns
 * an empty set if the library DB isn't present yet (nothing to skip).
 */
export function presentTitles(): Set<string> {
    const out = new Set<string>();
    if (!fs.existsSync(CALIBRE_DB_PATH)) return out;
    const db = new Database(CALIBRE_DB_PATH, { readonly: true });
    try {
        const rows = db.prepare('SELECT title FROM books').all() as { title: string }[];
        for (const r of rows) if (r.title) out.add(r.title.trim().toLowerCase());
    } finally {
        db.close();
    }
    return out;
}

export interface CatalogBook {
    gutenberg_id: number | string;
    title: string;
    author: string;
    language: string;
    download_url: string;
    description: string;
    cover_url: string;
}

function coverUrl(id: number | string): string {
    return `https://www.gutenberg.org/cache/epub/${id}/pg${id}.cover.medium.jpg`;
}

/** FTS/browse the offline catalog. query MATCH (prefix) | 'educational' | top-by-downloads.
 *  An optional ISO language code (e.g. "en") narrows every branch to that language. */
export function searchCatalog(q: string, filter: string, lang: string, limit: number): CatalogBook[] {
    if (!fs.existsSync(CATALOG_DB_PATH)) throw new Error('catalog database not found (sync first)');
    const lim = Math.max(1, Math.min(200, Number.isFinite(limit) ? limit : 40));
    const cols = 'gutenberg_id, title, author, language, download_url, description';
    const useLang = typeof lang === 'string' && lang.trim().length > 0;
    const langArg: any[] = useLang ? [lang.trim()] : [];
    const db = new Database(CATALOG_DB_PATH, { readonly: true });
    try {
        let rows: any[];
        if (q && q.trim().length > 0) {
            const langClause = useLang ? ' AND language = ?' : '';
            rows = db.prepare(
                `SELECT ${cols} FROM catalog WHERE catalog MATCH ?${langClause} ORDER BY rank LIMIT ?`
            ).all(q.trim() + '*', ...langArg, lim);
        } else if (filter === 'educational') {
            const langClause = useLang ? ' AND language = ?' : '';
            rows = db.prepare(
                `SELECT ${cols} FROM catalog WHERE (bookshelves LIKE '%Children%' OR bookshelves LIKE '%Education%')${langClause} ORDER BY downloads DESC LIMIT ?`
            ).all(...langArg, lim);
        } else {
            const whereLang = useLang ? ' WHERE language = ?' : '';
            rows = db.prepare(
                `SELECT ${cols} FROM catalog${whereLang} ORDER BY downloads DESC LIMIT ?`
            ).all(...langArg, lim);
        }
        return rows.map((r) => ({ ...r, cover_url: coverUrl(r.gutenberg_id) })) as CatalogBook[];
    } finally {
        db.close();
    }
}

/** The distinct languages present in the catalog, most-stocked first — so the picker only ever
 *  offers languages that actually have books (Gutenberg is mostly English). */
export function listLanguages(): any[] {
    if (!fs.existsSync(CATALOG_DB_PATH)) throw new Error('catalog database not found (sync first)');
    const db = new Database(CATALOG_DB_PATH, { readonly: true });
    try {
        return db.prepare(
            `SELECT language AS code, COUNT(*) AS count FROM catalog
             WHERE language IS NOT NULL AND language != '' GROUP BY language ORDER BY count DESC`
        ).all();
    } finally {
        db.close();
    }
}

/** The local Calibre-Web library — books that have an EPUB, newest first. */
export function listLibrary(): any[] {
    if (!fs.existsSync(CALIBRE_DB_PATH)) return [];
    const db = new Database(CALIBRE_DB_PATH, { readonly: true });
    try {
        return db.prepare(`
            SELECT
                books.id,
                books.title,
                strftime('%Y', books.pubdate) as year,
                (SELECT name FROM authors
                 JOIN books_authors_link ON authors.id = books_authors_link.author
                 WHERE book = books.id LIMIT 1) as author
            FROM books
            WHERE EXISTS (
                SELECT 1 FROM data WHERE data.book = books.id AND data.format = 'EPUB'
            )
            ORDER BY books.id DESC
        `).all();
    } finally {
        db.close();
    }
}

/** Log into Calibre-Web with the given credentials and return the authenticated session.
 *  A successful login answers with a 302/303 redirect; anything else means the credentials were
 *  rejected (thrown as 'Invalid Calibre-Web credentials'). A connection error (service down) throws
 *  the underlying fetch error, so callers can tell "wrong password" from "not running". */
async function loginCalibre(username: string, password: string): Promise<{ cookie: string; csrfToken: string }> {
    const loginPageRes = await fetch(`${CALIBRE_WEB_LOCAL_URL}/login`);
    const initialCookies = loginPageRes.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');
    const loginHtml = await loginPageRes.text();
    const csrfMatch = loginHtml.match(/name="csrf_token" value="(.*?)"/);
    if (!csrfMatch) throw new Error('Could not find CSRF token on login page');
    const csrfToken = csrfMatch[1];

    const loginData = new URLSearchParams();
    loginData.append('csrf_token', csrfToken);
    loginData.append('username', username);
    loginData.append('password', password);
    // ADFA-5043: request Flask-Login's persistent "remember me" so the response also sets a
    // `remember_token` cookie. Calibre-Web allows anonymous (guest) browsing, so the session cookie
    // alone doesn't stick in the WebView; the remember_token re-authenticates as admin reliably.
    loginData.append('remember_me', 'on');

    const authRes = await fetch(`${CALIBRE_WEB_LOCAL_URL}/login`, {
        method: 'POST',
        headers: {
            Cookie: initialCookies,
            'Content-Type': 'application/x-www-form-urlencoded',
            Referer: `${CALIBRE_WEB_LOCAL_URL}/login`,
        },
        body: loginData,
        redirect: 'manual',
    });
    if (authRes.status !== 302 && authRes.status !== 303) throw new Error('Invalid Calibre-Web credentials');

    const authCookieString = authRes.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');
    const homeHtml = await (await fetch(`${CALIBRE_WEB_LOCAL_URL}/`, { headers: { Cookie: authCookieString } })).text();
    const finalCsrfMatch =
        homeHtml.match(/name="csrf_token"\s+value="([^"]+)"/i) ||
        homeHtml.match(/value="([^"]+)"\s+name="csrf_token"/i);
    return { cookie: authCookieString, csrfToken: finalCsrfMatch ? finalCsrfMatch[1] : csrfToken };
}

export async function getCalibreSession(): Promise<{ cookie: string; csrfToken: string }> {
    const cred = getCredential('calibre');
    return loginCalibre(cred.username, cred.password);
}

/** ADFA-5044: check credentials against the live Calibre-Web before persisting them. Resolves on a
 *  successful login; throws 'Invalid Calibre-Web credentials' when rejected, or the fetch error when
 *  the service is unreachable (so the route can save-unverified instead of reporting a bad password). */
export async function verifyCalibreCredentials(username: string, password: string): Promise<void> {
    await loginCalibre(username, password);
}

/** Remove a book from Calibre-Web by its library id. */
export async function removeBook(id: number): Promise<void> {
    const s = await getCalibreSession();
    const body = new URLSearchParams();
    body.append('csrf_token', s.csrfToken);
    const res = await fetch(`${CALIBRE_WEB_LOCAL_URL}/delete/${id}`, {
        method: 'POST',
        headers: {
            Cookie: s.cookie,
            'Content-Type': 'application/x-www-form-urlencoded',
            Referer: `${CALIBRE_WEB_LOCAL_URL}/`,
        },
        body,
    });
    if (!res.ok) throw new Error(`Calibre-Web rejected deletion: ${res.status}`);
}
