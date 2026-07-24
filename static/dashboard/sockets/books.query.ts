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
import fs from 'fs';
import path from 'path';

const CALIBRE_LIB_PATH = '/library/calibre-web/';
const CALIBRE_DB_PATH = path.join(CALIBRE_LIB_PATH, 'metadata.db');
const BOOKS_DIR = '/library/dashboard/books/';
const CATALOG_DB_PATH = path.join(BOOKS_DIR, 'catalog.db');

const CALIBRE_WEB_LOCAL_URL = 'http://127.0.0.1:8083';
const CALIBRE_WEB_USER = 'Admin';
const CALIBRE_WEB_PASS = 'changeme';

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

/** FTS/browse the offline catalog. query MATCH (prefix) | 'educational' | top-by-downloads. */
export function searchCatalog(q: string, filter: string, limit: number): CatalogBook[] {
    if (!fs.existsSync(CATALOG_DB_PATH)) throw new Error('catalog database not found (sync first)');
    const lim = Math.max(1, Math.min(200, Number.isFinite(limit) ? limit : 40));
    const cols = 'gutenberg_id, title, author, language, download_url, description';
    const db = new Database(CATALOG_DB_PATH, { readonly: true });
    try {
        let rows: any[];
        if (q && q.trim().length > 0) {
            rows = db.prepare(
                `SELECT ${cols} FROM catalog WHERE catalog MATCH ? ORDER BY rank LIMIT ?`
            ).all(q.trim() + '*', lim);
        } else if (filter === 'educational') {
            rows = db.prepare(
                `SELECT ${cols} FROM catalog WHERE bookshelves LIKE '%Children%' OR bookshelves LIKE '%Education%' ORDER BY downloads DESC LIMIT ?`
            ).all(lim);
        } else {
            rows = db.prepare(
                `SELECT ${cols} FROM catalog ORDER BY downloads DESC LIMIT ?`
            ).all(lim);
        }
        return rows.map((r) => ({ ...r, cover_url: coverUrl(r.gutenberg_id) })) as CatalogBook[];
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

async function getCalibreSession(): Promise<{ cookie: string; csrfToken: string }> {
    const loginPageRes = await fetch(`${CALIBRE_WEB_LOCAL_URL}/login`);
    const initialCookies = loginPageRes.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');
    const loginHtml = await loginPageRes.text();
    const csrfMatch = loginHtml.match(/name="csrf_token" value="(.*?)"/);
    if (!csrfMatch) throw new Error('Could not find CSRF token on login page');
    const csrfToken = csrfMatch[1];

    const loginData = new URLSearchParams();
    loginData.append('csrf_token', csrfToken);
    loginData.append('username', CALIBRE_WEB_USER);
    loginData.append('password', CALIBRE_WEB_PASS);

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
