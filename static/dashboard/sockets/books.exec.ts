// sockets/books.exec.ts — ADFA-4838
//
// Books runner for the durable job engine: for each requested Gutenberg book, download
// the EPUB and upload it to the local Calibre-Web. Ported from the Phase 1 books.socket
// download_books_batch handler (auth/CSRF + fetch + upload), made durable and reporting
// structured per-book progress. A job item is { id, title, url }.
import { jobs, RunnerContext, CanceledError, PausedError, classifyStop } from './jobs';
import { withRetry } from './net-retry';
import { presentTitles, getCalibreSession } from './books.query';
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
// ADFA-4949: the credentials come from the shared store (env -> persisted override -> the
// same Admin/changeme factory default), so a device whose Calibre-Web password changed keeps
// working without a rebuild. Read inside getCalibreSession (books.query.ts).
// ADFA-5361: this file used to carry its OWN copy of that login. Two implementations of "an
// authenticated Calibre-Web session" drifted exactly as expected — the remember_me of ADFA-5043
// reached one and not the other — so the copy is gone and there is one source. This runner
// consumes the session itself, so it passes no consumer User-Agent and keeps Node's own agent.

interface BookItem { id?: string; title?: string; url?: string; }

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
    // ADFA-4893: idempotent resume — a relaunch skips books already in the library instead of
    // re-downloading + re-uploading them (which duplicated entries and reset the percent to 0).
    const present = presentTitles();
    for (const book of books) {
        ctx.throwIfCanceled();
        const id = String(book.id);
        const title = String(book.title ?? id);
        const url = String(book.url);
        ctx.update({ phase: 'processing', detail: title, percent: Math.round((done / books.length) * 100) });

        if (present.has(title.trim().toLowerCase())) {
            ctx.log(`[books] skip "${title}" — already in the library (idempotent resume)`);
            done++;
            ctx.update({ phase: 'processing', percent: Math.round((done / books.length) * 100) });
            continue;
        }

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
                onRetry: ({ attempt, err }) => {
                    ctx.reportRetry(attempt, BOOK_TRIES - 1);   // ADFA-4893: surface "Reconnecting… n of N" on the poll
                    ctx.log(`[books] retry ${attempt}/${BOOK_TRIES - 1} for "${title}": ${err instanceof Error ? err.message : String(err)}`);
                },
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

        ctx.reportRetry(0, 0);   // ADFA-4893: item completed → clear any reconnect state
        done++;
        ctx.update({ phase: 'processing', percent: Math.round((done / books.length) * 100) });
    }

    ctx.update({ phase: 'done', percent: 100 });
};

jobs.registerRunner('books', booksRunner);

export { booksRunner };
