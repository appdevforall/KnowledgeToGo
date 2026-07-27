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
import { parseBox } from './sockets/maps.socket';

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
// "New region will be X downloaded, Y on disk ... leave about Z free" prompt it prints, then answer
// "n" so it aborts WITHOUT downloading. Default-safe: if the prompt never arrives we kill the child
// (it is blocked on stdin, so nothing downloads). Needs connectivity + a moment (pmtiles --dry-run).
const EST_UNITS: Record<string, number> = { kB: 1e3, MB: 1e6, GB: 1e9, TB: 1e12 };
apiRouter.post('/maps/estimate', (req: Request, res: Response): void => {
    const parsed = parseBox(String((req.body as { box?: unknown })?.box ?? ''));
    if (!parsed.ok) { res.status(400).json({ error: parsed.error }); return; }
    // A unique throwaway name so we never hit the "overwrite existing?" branch.
    const probeName = 'est_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    const p = spawn('sudo', [MAPS_SCRIPT, 'extract', probeName, parsed.box],
        { env: { ...process.env, PYTHONUNBUFFERED: '1' } });

    let out = '', err = '', answered = false, done = false;
    const toBytes = (n: string, u: string) => Math.round(parseFloat(n) * (EST_UNITS[u] || 1));
    const finish = (fn: () => void) => {
        if (done) return; done = true; clearTimeout(timer);
        try { p.kill('SIGKILL'); } catch { /* already gone */ }
        fn();
    };
    const timer = setTimeout(() =>
        finish(() => { if (!res.headersSent) res.status(504).json({ error: 'estimate timed out' }); }), 60000);
    const tryParse = (): boolean => {
        const m = out.match(/New region will be ([\d.]+)\s*(kB|MB|GB|TB) downloaded, ([\d.]+)\s*(kB|MB|GB|TB) on disk\.\s*This will\s+leave about ([\d.]+)\s*(kB|MB|GB|TB) free/);
        if (!m) return false;
        const transfer = toBytes(m[1], m[2]), archive = toBytes(m[3], m[4]), freeAfter = toBytes(m[5], m[6]);
        finish(() => { if (!res.headersSent) res.json({ ok: true, transfer, archive, free: freeAfter + archive, free_after: freeAfter }); });
        return true;
    };
    const check = () => {
        if (done) return;
        if (/overlap/i.test(out) || /overlap/i.test(err)) {
            finish(() => { if (!res.headersSent) res.status(409).json({ error: 'overlaps an existing region' }); });
            return;
        }
        if (tryParse()) return;
        // Any y/n prompt gets "n" so nothing is ever downloaded (estimate-only).
        if (!answered && /Continue\?|Download anyway\?|Overwrite it\?/i.test(out)) {
            answered = true;
            try { p.stdin?.write('n\n'); } catch { /* stdin closed */ }
        }
    };
    p.stdout.on('data', (d: Buffer) => { out += d.toString(); check(); });
    p.stderr.on('data', (d: Buffer) => { err += d.toString(); check(); });
    p.on('error', (e) => finish(() => { if (!res.headersSent) res.status(500).json({ error: String(e) }); }));
    p.on('exit', () => finish(() => {
        if (res.headersSent) return;
        if (!tryParse()) res.status(500).json({ error: err.trim() || 'no size estimate in output', raw: out.slice(-200) });
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
