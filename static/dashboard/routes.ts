// routes.ts — ADFA-4838
//
// REST surface over the durable job engine. Short, stateless calls the app (and the
// web UI) use instead of a long-lived socket: start a job, poll its structured status,
// cancel it. The job itself lives in the dashboard process (see sockets/jobs.ts), so
// none of these calls hold state — a client can drop and re-attach by polling the id.
import express, { Router, Request, Response } from 'express';
import { jobs, Job, JobType } from './sockets/jobs';
import { searchCatalog, listLibrary, removeBook } from './sockets/books.query';

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
        const limit = parseInt(String(req.query.limit ?? '40'), 10);
        res.json(searchCatalog(q, filter, limit));
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'search failed' });
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
