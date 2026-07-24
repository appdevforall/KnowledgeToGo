// sockets/jobs.ts — ADFA-4838 (Phase 2 of ADFA-4832)
//
// Durable, module-scoped job engine for live content operations. Unlike the Phase 1
// socket handlers (job state lived in the socket closure and was SIGKILLed on
// disconnect), a job here is owned by the dashboard process and persisted to sqlite,
// so it survives a client disconnect AND a dashboard restart. Each content type
// (kiwix/maps/books) registers a Runner; the manager owns the lifecycle, persistence,
// cancellation and boot-time reconciliation. Progress is stored structured (phase +
// percent + bytes/sec), so clients never scrape terminal text.
import Database from 'better-sqlite3';
import { spawn, ChildProcess, SpawnOptions } from 'child_process';
import fs from 'fs';
import path from 'path';

export type JobType = 'kiwix' | 'maps' | 'books';
export type JobPhase =
    | 'queued' | 'downloading' | 'indexing' | 'processing'
    | 'done' | 'error' | 'canceled';

/** A persisted job row. `percent` is -1 when indeterminate; `speed` is bytes/sec. */
export interface Job {
    id: string;
    type: JobType;
    target: string;        // JSON-encoded string[] of item ids
    phase: JobPhase;
    percent: number;
    speed: number;
    detail: string | null; // free-form current item / message
    error: string | null;
    created: number;
    updated: number;
}

export interface JobUpdate {
    phase?: JobPhase;
    percent?: number;
    speed?: number;
    detail?: string | null;
    error?: string | null;
}

/** Passed to a runner: report progress, check cancellation, spawn tracked children. */
export interface RunnerContext {
    readonly job: Job;
    readonly ids: string[];
    update(patch: JobUpdate): void;
    isCanceled(): boolean;
    throwIfCanceled(): void;
    /** Spawn a child that is killed automatically on cancel and untracked on exit. */
    spawn(cmd: string, args: string[], opts?: SpawnOptions): ChildProcess;
    log(line: string): void;
}

export type Runner = (ctx: RunnerContext) => Promise<void>;

/** Thrown by throwIfCanceled(); mapped to phase 'canceled' rather than 'error'. */
export class CanceledError extends Error {
    constructor() { super('canceled'); this.name = 'CanceledError'; }
}

const DB_PATH = process.env.K2GO_JOBS_DB || '/library/dashboard/jobs.db';
const ACTIVE: JobPhase[] = ['queued', 'downloading', 'indexing', 'processing'];

class JobManager {
    private db: Database.Database;
    private runners = new Map<JobType, Runner>();
    private runtime = new Map<string, { canceled: boolean; procs: Set<ChildProcess> }>();

    constructor() {
        try { fs.mkdirSync(path.dirname(DB_PATH), { recursive: true }); } catch { /* best effort */ }
        this.db = new Database(DB_PATH);
        this.db.pragma('journal_mode = WAL');
        this.db.exec(`CREATE TABLE IF NOT EXISTS jobs (
            id TEXT PRIMARY KEY,
            type TEXT NOT NULL,
            target TEXT NOT NULL,
            phase TEXT NOT NULL,
            percent INTEGER NOT NULL DEFAULT -1,
            speed INTEGER NOT NULL DEFAULT 0,
            detail TEXT,
            error TEXT,
            created INTEGER NOT NULL,
            updated INTEGER NOT NULL
        );`);
    }

    registerRunner(type: JobType, runner: Runner): void {
        this.runners.set(type, runner);
    }

    /** Create + start a job. Returns the persisted row immediately (phase 'queued'). */
    create(type: JobType, ids: string[]): Job {
        const now = Date.now();
        const id = `${type}-${now}-${Math.random().toString(36).slice(2, 8)}`;
        const job: Job = {
            id, type, target: JSON.stringify(ids), phase: 'queued',
            percent: -1, speed: 0, detail: null, error: null, created: now, updated: now,
        };
        this.db.prepare(
            `INSERT INTO jobs (id,type,target,phase,percent,speed,detail,error,created,updated)
             VALUES (@id,@type,@target,@phase,@percent,@speed,@detail,@error,@created,@updated)`
        ).run(job);
        this.launch(job);
        return job;
    }

    get(id: string): Job | undefined {
        return this.db.prepare(`SELECT * FROM jobs WHERE id = ?`).get(id) as Job | undefined;
    }

    list(type?: JobType): Job[] {
        return (type
            ? this.db.prepare(`SELECT * FROM jobs WHERE type = ? ORDER BY created DESC`).all(type)
            : this.db.prepare(`SELECT * FROM jobs ORDER BY created DESC`).all()) as Job[];
    }

    /** Cancel a running job (kills its children) and marks it 'canceled'. */
    cancel(id: string): boolean {
        const job = this.get(id);
        if (!job) return false;
        const rt = this.runtime.get(id);
        if (rt) {
            rt.canceled = true;
            for (const p of rt.procs) { try { p.kill('SIGKILL'); } catch { /* already gone */ } }
        }
        if (ACTIVE.includes(job.phase)) this.patch(id, { phase: 'canceled' });
        return true;
    }

    /** Resume jobs that were mid-flight when the dashboard last stopped. */
    reconcileOnBoot(): void {
        const stuck = this.db.prepare(
            `SELECT * FROM jobs WHERE phase IN ('queued','downloading','indexing','processing')`
        ).all() as Job[];
        for (const job of stuck) this.launch(job);
    }

    private patch(id: string, patch: JobUpdate): void {
        const cur = this.get(id);
        if (!cur) return;
        const next: Job = {
            ...cur,
            phase: patch.phase ?? cur.phase,
            percent: patch.percent ?? cur.percent,
            speed: patch.speed ?? cur.speed,
            detail: patch.detail !== undefined ? patch.detail : cur.detail,
            error: patch.error !== undefined ? patch.error : cur.error,
            updated: Date.now(),
        };
        this.db.prepare(
            `UPDATE jobs SET phase=@phase,percent=@percent,speed=@speed,detail=@detail,error=@error,updated=@updated WHERE id=@id`
        ).run(next);
    }

    private launch(job: Job): void {
        const runner = this.runners.get(job.type);
        if (!runner) { this.patch(job.id, { phase: 'error', error: `no runner for ${job.type}` }); return; }

        const rt = { canceled: false, procs: new Set<ChildProcess>() };
        this.runtime.set(job.id, rt);

        let ids: string[] = [];
        try { ids = JSON.parse(job.target) as string[]; } catch { ids = []; }

        const ctx: RunnerContext = {
            job, ids,
            update: (p) => this.patch(job.id, p),
            isCanceled: () => rt.canceled,
            throwIfCanceled: () => { if (rt.canceled) throw new CanceledError(); },
            spawn: (cmd, args, opts) => {
                const child = opts ? spawn(cmd, args, opts) : spawn(cmd, args);
                rt.procs.add(child);
                child.on('exit', () => rt.procs.delete(child));
                return child;
            },
            log: (line) => console.log(`[job ${job.id}] ${line}`),
        };

        Promise.resolve()
            .then(() => runner(ctx))
            .then(() => {
                const cur = this.get(job.id);
                if (cur && ACTIVE.includes(cur.phase)) this.patch(job.id, { phase: 'done', percent: 100 });
            })
            .catch((err: unknown) => {
                if (err instanceof CanceledError || rt.canceled) {
                    this.patch(job.id, { phase: 'canceled' });
                } else {
                    const msg = err instanceof Error ? err.message : String(err);
                    this.patch(job.id, { phase: 'error', error: msg });
                }
            })
            .finally(() => { this.runtime.delete(job.id); });
    }
}

/** Singleton shared by every type's runner and the REST routes. */
export const jobs = new JobManager();
