// routes.ts — ADFA-4838
//
// REST surface over the durable job engine. Short, stateless calls the app (and the
// web UI) use instead of a long-lived socket: start a job, poll its structured status,
// cancel it. The job itself lives in the dashboard process (see sockets/jobs.ts), so
// none of these calls hold state — a client can drop and re-attach by polling the id.
import express, { Router, Request, Response } from 'express';
import { spawn } from 'child_process';
import { jobs, Job, JobType } from './sockets/jobs';
import { searchCatalog, listLibrary, removeBook, listLanguages } from './sockets/books.query';
import { parseBox, parseEstimate } from './sockets/maps.socket';

// ADFA-4879: FQR helpers reached from the app (in-app region download/delete instead of the
// copy-paste-into-a-terminal flow). tile-extract.py is installed on the box by the upstream maps
// role and is NOT modified here — we only talk to its existing CLI (same trusted binary
// maps.exec.ts already spawns): `extract` (interactive, for the estimate) and `delete`.
const MAPS_SCRIPT = '/opt/iiab/maps/tile-extract/tile-extract.py';
const MAPS_NAME_RE = /^[A-Za-z0-9_-]{1,34}$/;

const VALID_TYPES: JobType[] = ['kiwix', 'maps', 'books'];
function isType(t: string): t is JobType {
    return (VALID_TYPES as string[]).includes(t);
}

/** Public shape returned to clients: ids as an array, no raw JSON column. */
function toApi(job: Job) {
    let ids: string[] = [];
    try { ids = JSON.parse(job.target) as string[]; } catch { ids = []; }
    return {
        id: job.id,
        type: job.type,
        ids,
        phase: job.phase,
        percent: job.percent,
        speed: job.speed,
        detail: job.detail,
        error: job.error,
        updated: job.updated,
    };
}

export const apiRouter: Router = express.Router();

// --- Books: direct (non-job) queries over the offline catalog + Calibre-Web library ---------
// ADFA-4850. Ported from the socket.io handlers; the download itself stays a durable job
// (POST /books/download). These paths don't collide with the generic /:type/* routes below.

// Search the offline Gutenberg catalog. ?q= (FTS) | ?filter=educational | (default) top-by-downloads.
apiRouter.get('/books/search', (req: Request, res: Response): void => {
    try {
        const q = String(req.query.q ?? '');
        const filter = String(req.query.filter ?? '');
        const lang = String(req.query.lang ?? '');
        const limit = parseInt(String(req.query.limit ?? '40'), 10);
        res.json(searchCatalog(q, filter, lang, limit));
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'search failed' });
    }
});

// The distinct languages present in the catalog (for the language picker).
apiRouter.get('/books/languages', (_req: Request, res: Response): void => {
    try {
        res.json(listLanguages());
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'languages read failed' });
    }
});

// The local Calibre-Web library (EPUB books) — for "Your books" / Read a Book.
apiRouter.get('/books/library', (_req: Request, res: Response): void => {
    try {
        res.json(listLibrary());
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'library read failed' });
    }
});

// Remove a book from the Calibre-Web library.
apiRouter.post('/books/library/:id/remove', async (req: Request, res: Response): Promise<void> => {
    const id = parseInt(String(req.params.id), 10);
    if (!Number.isFinite(id)) { res.status(400).json({ error: 'bad id' }); return; }
    try {
        await removeBook(id);
        res.json({ ok: true });
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'remove failed' });
    }
});

// --- Maps FQR: estimate + delete (ADFA-4879) --------------------------------------------------
// These are direct (non-job) ops. The download itself stays a durable job (POST /maps/download).
// Declared before the generic /:type/* routes so the literal paths win.

// Size estimate for a region, for the in-app consent step: { transfer, archive, free, free_after }
// (bytes). We do NOT modify tile-extract.py — we drive its existing INTERACTIVE `extract`, read the
// size prompt it prints (parseEstimate), then abort WITHOUT downloading. Hardened default-safe: we
// close stdin immediately, so the script's confirm `input()` raises EOFError and aborts on its own
// even if the prompt wording changes — the estimate is printed before that input(), so we still
// capture it. Needs connectivity + a moment (pmtiles --dry-run).
const MAPS_ESTIMATE_TIMEOUT_MS = 60000;
apiRouter.post('/maps/estimate', (req: Request, res: Response): void => {
    const parsed = parseBox(String((req.body as { box?: unknown })?.box ?? ''));
    if (!parsed.ok) { res.status(400).json({ error: parsed.error }); return; }
    // A unique throwaway name so we never hit the "overwrite existing?" branch.
    const probeName = 'est_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    const p = spawn('sudo', [MAPS_SCRIPT, 'extract', probeName, parsed.box],
        { env: { ...process.env, PYTHONUNBUFFERED: '1' } });
    p.stdin?.end();   // never feed input => any confirm() aborts via EOFError; no download can start

    let out = '', err = '', done = false;
    const finish = (fn: () => void) => {
        if (done) return; done = true; clearTimeout(timer);
        try { p.kill('SIGKILL'); } catch { /* already gone */ }
        fn();
    };
    const timer = setTimeout(() =>
        finish(() => { if (!res.headersSent) res.status(504).json({ error: 'estimate timed out' }); }),
        MAPS_ESTIMATE_TIMEOUT_MS);
    const check = () => {
        if (done) return;
        if (/overlap/i.test(out) || /overlap/i.test(err)) {
            finish(() => { if (!res.headersSent) res.status(409).json({ error: 'overlaps an existing region' }); });
            return;
        }
        const est = parseEstimate(out);
        if (est) finish(() => { if (!res.headersSent) res.json({ ok: true, ...est }); });
    };
    p.stdout.on('data', (d: Buffer) => { out += d.toString(); check(); });
    p.stderr.on('data', (d: Buffer) => { err += d.toString(); check(); });
    p.on('error', (e) => finish(() => { if (!res.headersSent) res.status(500).json({ error: String(e) }); }));
    p.on('exit', () => finish(() => {
        if (res.headersSent) return;
        const est = parseEstimate(out);
        if (est) res.json({ ok: true, ...est });
        else res.status(500).json({ error: err.trim() || 'no size estimate in output', raw: out.slice(-200) });
    }));
});

// Delete a downloaded region. tile-extract.py's `delete` runs update-json itself.
apiRouter.post('/maps/delete', (req: Request, res: Response): void => {
    const name = String((req.body as { name?: unknown })?.name ?? '').trim();
    if (!MAPS_NAME_RE.test(name)) { res.status(400).json({ error: 'invalid region name' }); return; }
    const p = spawn('sudo', [MAPS_SCRIPT, 'delete', name],
        { env: { ...process.env, PYTHONUNBUFFERED: '1' } });
    let err = '';
    p.stderr.on('data', (d: Buffer) => { err += d.toString(); });
    p.on('error', (e) => { if (!res.headersSent) res.status(500).json({ error: String(e) }); });
    p.on('exit', (code) => {
        if (res.headersSent) return;
        if (code === 0) res.json({ ok: true });
        else res.status(500).json({ error: err.trim() || `delete exited ${code}` });
    });
});

// Start a content job → 202 { ...job }
apiRouter.post('/:type/download', (req: Request, res: Response): void => {
    const type = String(req.params.type);
    if (!isType(type)) { res.status(404).json({ error: 'unknown type' }); return; }
    // kiwix sends { ids: ["file.zim"] }; maps/books send { items: [ {...} ] }.
    const body = req.body as { ids?: unknown; items?: unknown };
    const items: unknown[] = Array.isArray(body?.items)
        ? body.items
        : Array.isArray(body?.ids) ? body.ids : [];
    if (items.length === 0) { res.status(400).json({ error: 'items (or ids) required' }); return; }
    res.status(202).json(toApi(jobs.create(type, items)));
});

// Poll one job's structured status.
apiRouter.get('/:type/jobs/:id', (req: Request, res: Response): void => {
    const job = jobs.get(String(req.params.id));
    if (!job || job.type !== String(req.params.type)) { res.status(404).json({ error: 'not found' }); return; }
    res.json(toApi(job));
});

// Live log tail for a job (ADFA-4879). Opt-in: a client polls ?since=<cursor> and appends the
// returned lines, feeding `next` back as the next `since`. Enables a simple live log over REST.
apiRouter.get('/:type/jobs/:id/log', (req: Request, res: Response): void => {
    const job = jobs.get(String(req.params.id));
    if (!job || job.type !== String(req.params.type)) { res.status(404).json({ error: 'not found' }); return; }
    const since = parseInt(String(req.query.since ?? '0'), 10);
    res.json(jobs.getLog(job.id, Number.isFinite(since) ? since : 0));
});

// List a type's jobs (most recent first).
apiRouter.get('/:type/jobs', (req: Request, res: Response): void => {
    const type = String(req.params.type);
    if (!isType(type)) { res.status(404).json({ error: 'unknown type' }); return; }
    res.json(jobs.list(type).map(toApi));
});

// Cancel a running job.
apiRouter.post('/:type/jobs/:id/cancel', (req: Request, res: Response): void => {
    const job = jobs.get(String(req.params.id));
    if (!job || job.type !== String(req.params.type)) { res.status(404).json({ error: 'not found' }); return; }
    jobs.cancel(job.id);
    res.json({ ok: true });
});
