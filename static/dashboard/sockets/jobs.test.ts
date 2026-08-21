/// <reference types="node" />
// Run with `npm run test:db` — this exercises the JobManager, which loads better-sqlite3's native
// binding, so it needs the package built (present wherever the dashboard is built/deployed). It is
// kept out of the default `npm test` so that suite stays green on hosts without the native build.
//
// Use an isolated throwaway DB so the test never touches the real /library jobs.db. Must be set
// BEFORE ./jobs is loaded (the JobManager opens the DB in its constructor) — hence the require()
// below runs after this line, rather than a hoisted top-of-file import.
process.env.K2GO_JOBS_DB = `/tmp/jobs-test-${process.pid}-${Date.now()}.db`;

import test from 'node:test';
import assert from 'node:assert/strict';
import type { RunnerContext } from './jobs';

// require (not import): a static import is hoisted above the env line; a dynamic import() goes
// through the ESM loader, which needs a file extension ts-node's CJS require does not.
const { jobs, CanceledError, PausedError } = require('./jobs') as typeof import('./jobs');

const tick = (ms = 30) => new Promise((r) => setTimeout(r, ms));

/**
 * A fake runner that reports 'downloading' then blocks on a gate until either released (→ done) or
 * the job's AbortSignal fires (pause/cancel → the matching engine error). Models a real runner's
 * pause-vs-cancel handling without a child process or network.
 */
function gatedRunner() {
    let release: (() => void) | null = null;
    const runner = async (ctx: RunnerContext) => {
        ctx.update({ phase: 'downloading', percent: 10 });
        try {
            await new Promise<void>((resolve, reject) => {
                release = resolve;
                ctx.signal.addEventListener('abort', () => reject(new Error('abort')), { once: true });
            });
        } catch {
            if (ctx.isPaused()) throw new PausedError();
            if (ctx.isCanceled()) throw new CanceledError();
            throw new Error('unexpected stop');
        }
        ctx.update({ phase: 'done', percent: 100 });
    };
    return { runner, release: () => release?.() };
}

test('pause marks paused; resume re-runs to done', async () => {
    const g = gatedRunner();
    jobs.registerRunner('kiwix', g.runner);

    const job = jobs.create('kiwix', ['wikipedia/x.zim']);
    await tick();
    assert.equal(jobs.get(job.id)?.phase, 'downloading');

    assert.equal(jobs.pause(job.id), true);
    await tick();
    assert.equal(jobs.get(job.id)?.phase, 'paused');   // paused, not canceled

    assert.equal(jobs.resume(job.id), true);
    await tick();                                        // let the relaunched run reach its gate
    g.release();
    await tick();
    assert.equal(jobs.get(job.id)?.phase, 'done');
});

test('retry re-runs a job that ended in error', async () => {
    let attempt = 0;
    jobs.registerRunner('kiwix', async (ctx: RunnerContext) => {
        attempt++;
        ctx.update({ phase: 'downloading' });
        if (attempt === 1) throw new Error('boom');   // first run fails
        ctx.update({ phase: 'done', percent: 100 });  // retry succeeds
    });

    const job = jobs.create('kiwix', ['wikipedia/y.zim']);
    await tick();
    assert.equal(jobs.get(job.id)?.phase, 'error');

    assert.equal(jobs.retry(job.id), true);
    await tick();
    assert.equal(jobs.get(job.id)?.phase, 'done');
    assert.equal(attempt, 2);
});

test('a stopped job ignores late updates (no phase resurrection)', async () => {
    // Models the maps bug (ADFA-4896): a slow-dying worker emits one more progress line right after
    // pause. The engine must drop it so the phase stays 'paused' instead of flipping back to active.
    jobs.registerRunner('kiwix', async (ctx: RunnerContext) => {
        ctx.update({ phase: 'downloading', percent: 10 });
        await new Promise<void>((_resolve, reject) => {
            ctx.signal.addEventListener('abort', () => {
                ctx.update({ phase: 'downloading', percent: 50 });   // late line after pause
                reject(new Error('abort'));
            }, { once: true });
        }).catch(() => {
            if (ctx.isPaused()) throw new PausedError();
            throw new CanceledError();
        });
    });

    const job = jobs.create('kiwix', ['wikipedia/late.zim']);
    await tick();
    assert.equal(jobs.pause(job.id), true);
    await tick();
    assert.equal(jobs.get(job.id)?.phase, 'paused');   // the late 'downloading' update was dropped
});

test('the verbs no-op outside their phase', async () => {
    jobs.registerRunner('kiwix', async (ctx: RunnerContext) => {
        ctx.update({ phase: 'done', percent: 100 });   // completes immediately
    });

    const job = jobs.create('kiwix', ['wikipedia/z.zim']);
    await tick();
    assert.equal(jobs.get(job.id)?.phase, 'done');

    assert.equal(jobs.pause(job.id), false);   // not active
    assert.equal(jobs.resume(job.id), false);  // not paused
    assert.equal(jobs.retry(job.id), false);   // not in error
});
