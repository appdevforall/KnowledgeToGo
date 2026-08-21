// sockets/books.exec.ts — ADFA-4838
//
// Books runner for the durable job engine: for each requested Gutenberg book, download
// the EPUB and upload it to the local Calibre-Web. Ported from the Phase 1 books.socket
// download_books_batch handler (auth/CSRF + fetch + upload), made durable and reporting
// structured per-book progress. A job item is { id, title, url }.
import { jobs, RunnerContext, CanceledError, PausedError, classifyStop } from './jobs';
import { getCredential } from './credentials';
import { withRetry } from './net-retry';
import fs from 'fs';
import path from 'path';

// ADFA-4894: a stalled Gutenberg fetch must not hang the job forever, and a transient
// drop must not fail the whole batch at one book. Per-book: bound each attempt with a
// timeout, retry the transient ones with backoff, and make the job's cancel abort an
// in-flight fetch (ctx.signal). A Gutenberg EPUB is a few MB, so a failed attempt is
// re-fetched whole rather than byte-ranged — resume at the item granularity, not the byte.
const FETCH_TIMEOUT_MS = 60_000;
// The retry budget has to outlast a real mobile Wi-Fi drop, not just a sub-second blip: on a
// device, disabling Wi-Fi and re-associating takes several seconds (measured on ADFA-4894).
// 6 tries with 1/2/4/8/15s backoff ≈ a 30s window across the handoff, then the item fails.
const BOOK_TRIES = 6;
const BOOK_RETRY_BASE_MS = 1_000;
const BOOK_RETRY_MAX_MS = 15_000;

const CALIBRE_WEB_LOCAL_URL = 'http://127.0.0.1:8083';
const TMP_DIR = '/tmp/books_downloader/';
const SYSTEM_USER_AGENT = 'K2Go Dashboard/1.0 (https://github.com/appdevforall/KnowledgeToGo)';
// ADFA-4949: the credential override the original comment anticipated. Values now
// come from the shared store (env -> persisted override -> the same Admin/changeme
// factory default), so a device whose Calibre-Web password changed keeps working
// without a rebuild. Behaviour on an untouched device is identical.

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
    const cred = getCredential('calibre');
    loginData.append('username', cred.username);
    loginData.append('password', cred.password);

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
            await withRetry(async () => {
                // Cancel (ctx.signal) is terminal; a per-attempt timeout is retryable. Combine both
                // for the fetch so a stall aborts, then let withRetry decide whether to try again.
                const signal = AbortSignal.any([ctx.signal, AbortSignal.timeout(FETCH_TIMEOUT_MS)]);

                const response = await fetch(url, {
                    headers: { 'User-Agent': SYSTEM_USER_AGENT, Accept: 'application/epub+zip' },
                    signal,
                });
                if (!response.ok) {
                    const e = new Error(`HTTP ${response.status} from Gutenberg`);
                    (e as { status?: number }).status = response.status;
                    throw e;
                }

                const fileBuffer = await response.arrayBuffer();
                fs.writeFileSync(tmp, Buffer.from(fileBuffer));

                const form = new FormData();
                form.append('csrf_token', session.csrfToken);
                form.append('btn-upload', new Blob([fileBuffer], { type: 'application/epub+zip' }), `${title}.epub`);

                const uploadRes = await fetch(`${CALIBRE_WEB_LOCAL_URL}/upload`, {
                    method: 'POST',
                    headers: { Cookie: session.cookie, Referer: `${CALIBRE_WEB_LOCAL_URL}/` },
                    body: form,
                    signal,
                });
                if (!uploadRes.ok) {
                    const e = new Error(`Calibre-Web rejected upload: ${uploadRes.status}`);
                    (e as { status?: number }).status = uploadRes.status;
                    throw e;
                }
            }, {
                tries: BOOK_TRIES,
                baseMs: BOOK_RETRY_BASE_MS,
                maxMs: BOOK_RETRY_MAX_MS,
                signal: ctx.signal,
                isCanceled: ctx.isCanceled,
                // Retry network drops and timeouts and 5xx; a 4xx is the server's final word.
                isTransient: (err) => {
                    const status = (err as { status?: number })?.status;
                    if (typeof status === 'number') return status >= 500;
                    return true;
                },
                onRetry: ({ attempt, err }) => ctx.log(`[books] retry ${attempt} for "${title}": ${err instanceof Error ? err.message : String(err)}`),
            });
        } catch (err) {
            // ADFA-4894: pause stops the fetch but keeps nothing mid-file (books resumes per item on
            // the next launch); cancel is terminal. Either way, distinguish from a real error via the
            // job flags so the phase lands on 'paused' / 'canceled', not 'error'.
            const stop = classifyStop(ctx);
            if (stop === 'paused') throw new PausedError();
            if (stop === 'canceled') throw new CanceledError();
            throw err;
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
