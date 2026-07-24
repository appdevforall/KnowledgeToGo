// routes.ts — ADFA-4838
//
// REST surface over the durable job engine. Short, stateless calls the app (and the
// web UI) use instead of a long-lived socket: start a job, poll its structured status,
// cancel it. The job itself lives in the dashboard process (see sockets/jobs.ts), so
// none of these calls hold state — a client can drop and re-attach by polling the id.
import express, { Router, Request, Response } from 'express';
import { jobs, Job, JobType } from './sockets/jobs';

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

// Start a content job → 202 { ...job }
apiRouter.post('/:type/download', (req: Request, res: Response): void => {
    const type = String(req.params.type);
    if (!isType(type)) { res.status(404).json({ error: 'unknown type' }); return; }
    const body = req.body as { ids?: unknown };
    const ids = Array.isArray(body?.ids) ? body.ids.map((x) => String(x)) : [];
    if (ids.length === 0) { res.status(400).json({ error: 'ids required' }); return; }
    res.status(202).json(toApi(jobs.create(type, ids)));
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
