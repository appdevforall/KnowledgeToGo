// sockets/books.exec.ts — ADFA-4838
//
// Books runner for the durable job engine: for each requested Gutenberg book, download
// the EPUB and upload it to the local Calibre-Web. Ported from the Phase 1 books.socket
// download_books_batch handler (auth/CSRF + fetch + upload), made durable and reporting
// structured per-book progress. A job item is { id, title, url }.
import { jobs, RunnerContext } from './jobs';
import fs from 'fs';
import path from 'path';

const CALIBRE_WEB_LOCAL_URL = 'http://127.0.0.1:8083';
const TMP_DIR = '/tmp/books_downloader/';
const SYSTEM_USER_AGENT = 'K2Go Dashboard/1.0 (https://github.com/appdevforall/KnowledgeToGo)';
// Defaults match the Phase 1 handler; a credential override can be added later if needed.
const CALIBRE_WEB_USER = 'Admin';
const CALIBRE_WEB_PASS = 'changeme';

interface BookItem { id?: string; title?: string; url?: string; }

/** Authenticate against Calibre-Web and return a usable cookie + fresh CSRF token. */
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

    if (authRes.status !== 302 && authRes.status !== 303) {
        throw new Error('Invalid Calibre-Web credentials');
    }

    const authCookieString = authRes.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');

    const homePageRes = await fetch(`${CALIBRE_WEB_LOCAL_URL}/`, { headers: { Cookie: authCookieString } });
    const homeHtml = await homePageRes.text();
    const finalCsrfMatch =
        homeHtml.match(/name="csrf_token"\s+value="([^"]+)"/i) ||
        homeHtml.match(/value="([^"]+)"\s+name="csrf_token"/i);
    const finalCsrfToken = finalCsrfMatch ? finalCsrfMatch[1] : csrfToken;

    return { cookie: authCookieString, csrfToken: finalCsrfToken };
}

const booksRunner: (ctx: RunnerContext) => Promise<void> = async (ctx) => {
    if (!fs.existsSync(TMP_DIR)) fs.mkdirSync(TMP_DIR, { recursive: true });

    const books = ctx.items
        .map((x) => x as BookItem)
        .filter((b) => b && typeof b.url === 'string' && typeof b.id === 'string');
    if (books.length === 0) throw new Error('no books requested');

    ctx.update({ phase: 'processing', percent: 0 });

    let session: { cookie: string; csrfToken: string };
    try {
        session = await getCalibreSession();
    } catch (e) {
        throw new Error('Calibre-Web authentication failed');
    }

    let done = 0;
    for (const book of books) {
        ctx.throwIfCanceled();
        const id = String(book.id);
        const title = String(book.title ?? id);
        const url = String(book.url);
        ctx.update({ phase: 'processing', detail: title, percent: Math.round((done / books.length) * 100) });

        const tmp = path.join(TMP_DIR, `pg_${id}.epub`);
        try {
            const response = await fetch(url, {
                headers: { 'User-Agent': SYSTEM_USER_AGENT, Accept: 'application/epub+zip' },
            });
            if (!response.ok) throw new Error(`HTTP ${response.status} from Gutenberg`);

            const fileBuffer = await response.arrayBuffer();
            fs.writeFileSync(tmp, Buffer.from(fileBuffer));

            const form = new FormData();
            form.append('csrf_token', session.csrfToken);
            form.append('btn-upload', new Blob([fileBuffer], { type: 'application/epub+zip' }), `${title}.epub`);

            const uploadRes = await fetch(`${CALIBRE_WEB_LOCAL_URL}/upload`, {
                method: 'POST',
                headers: { Cookie: session.cookie, Referer: `${CALIBRE_WEB_LOCAL_URL}/` },
                body: form,
            });
            if (!uploadRes.ok) throw new Error(`Calibre-Web rejected upload: ${uploadRes.status}`);
        } finally {
            if (fs.existsSync(tmp)) fs.unlinkSync(tmp);
        }

        done++;
        ctx.update({ phase: 'processing', percent: Math.round((done / books.length) * 100) });
    }

    ctx.update({ phase: 'done', percent: 100 });
};

jobs.registerRunner('books', booksRunner);

export { booksRunner };
