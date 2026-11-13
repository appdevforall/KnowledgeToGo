// sockets/maps.exec.ts — ADFA-4838
//
// Maps runner for the durable job engine: extract a tile region via tile-extract.py.
// A job item is { name, box, est? }; deletion stays a separate op (not a long job).
import { jobs, Job, RunnerContext, CanceledError, PausedError, classifyStop, throwIfStopped, JobUpdate } from './jobs';
import {
    parseBox, listFqrRegions, REGION_NAME_RE, extractArgs, deleteArgs, installedHasModernCli,
    classifyExtractEnd, LOCK_BUSY_RE, jobOwnsRegion,
} from './maps.socket';
import { delay } from './net-retry';
import { spawn, StdioOptions } from 'child_process';
import fs from 'fs';
import path from 'path';

// Older CLI only (a rootfs baked before iiab#4574): tile-extract.py downloads each pmtiles into
// TMP_DIR and moves them into the served dir when all are done, and its `delete` does not clean
// TMP_DIR, so a killed extract leaves half-written files here. The current `delete` removes them
// itself, under the script's lock. Must match TMP_DIR in the script.
const MAPS_TMP_DIR = '/library/downloads/maps';

interface MapItem { name?: string; box?: string; est?: number; }

/** Older CLI only: remove a region's half-written pmtiles and their K2GO-317 `.resume` journals from
 *  TMP_DIR. Name-scoped (`*.full-region.<name>.pmtiles`), so another region's download is untouched. */
function cleanupTmpPartials(name: string, log: (l: string) => void): void {
    try {
        const base = `.full-region.${name}.pmtiles`;
        for (const f of fs.readdirSync(MAPS_TMP_DIR)) {
            if (f.endsWith(base) || f.endsWith(base + '.resume')) {
                try { fs.unlinkSync(path.join(MAPS_TMP_DIR, f)); log(`[maps] pruned partial ${f}`); } catch { /* gone */ }
            }
        }
    } catch { /* dir missing / unreadable — nothing to prune */ }
}

export interface DeleteResult { ok: boolean; busy: boolean; timedOut: boolean; error: string; }

const DELETE_TIMEOUT_MS = 30_000;

/** Delete the region `name` and the files of its unfinished download. Used by the app's delete
 *  (POST /maps/delete) and by the runner's purge. Never rejects. `busy` = the script's lock was held
 *  by another Full Quality Region download or delete, so nothing was deleted. */
export function deleteRegion(name: string, log: (l: string) => void = () => { /* quiet */ }): Promise<DeleteResult> {
    const modern = installedHasModernCli();
    if (!modern) cleanupTmpPartials(name, log);
    // --yes needs no stdin: with no pipe there is nothing to break (no EPIPE).
    const stdio: StdioOptions = [modern ? 'ignore' : 'pipe', 'pipe', 'pipe'];
    return new Promise((resolve) => {
        const p = spawn('sudo', deleteArgs(name, modern), { env: { ...process.env, PYTHONUNBUFFERED: '1' }, stdio, detached: true });
        let err = '', done = false;
        const finish = (r: DeleteResult) => { if (done) return; done = true; clearTimeout(timer); resolve(r); };
        // Guards a child that stays blocked (for example on a question this caller does not answer).
        const timer = setTimeout(() => {
            // detached: sudo leads a group, so this also stops the python worker and frees its lock
            try { if (p.pid) process.kill(-p.pid, 'SIGKILL'); } catch { /* group already gone */ }
            try { p.kill('SIGKILL'); } catch { /* already gone */ }
            finish({ ok: false, busy: false, timedOut: true, error: 'delete timed out' });
        }, DELETE_TIMEOUT_MS);
        if (!modern) {
            // The older delete asks one question. The child can exit before it reads stdin; that
            // write error is not the result (the exit code is), so it is ignored.
            p.stdin?.on('error', () => { /* EPIPE: the exit code reports the result */ });
            p.stdin?.end('y\n');
        }
        p.stdout?.on('data', (d: Buffer) => log(d.toString().trim()));
        p.stderr?.on('data', (d: Buffer) => { const t = d.toString(); err += t; log(t.trim()); });
        p.on('error', (e) => finish({ ok: false, busy: false, timedOut: false, error: String(e) }));
        p.on('exit', (code) => finish({
            ok: code === 0, busy: LOCK_BUSY_RE.test(err), timedOut: false,
            error: err.trim() || `delete exited ${code}`,
        }));
    });
}

const PURGE_TRIES = 3;
const PURGE_RETRY_MS = 2_000;

/** ADFA-4896 / K2GO-317: discard a job's own work after a CANCEL or a hard error: its unfinished
 *  download and, only when this job made it, its region. A region of the same name that this job did
 *  not make (another job finished it, or it was there before) is kept, because the delete would
 *  remove it too. A pause or a resumable network drop does NOT come here. Best-effort: logs only. */
async function purgeRegion(name: string, ownsRegion: boolean, log: (l: string) => void): Promise<void> {
    if (!ownsRegion && name in listFqrRegions()) {
        log(`[maps] keep the region "${name}": this job did not make it`);
        return;
    }
    for (let attempt = 1; ; attempt++) {
        const r = await deleteRegion(name, log);
        if (r.ok) return;
        if (!r.busy || attempt >= PURGE_TRIES) { log(`[maps] purge of "${name}" failed: ${r.error}`); return; }
        await delay(PURGE_RETRY_MS);
    }
}

// One maps job runs tile-extract.py at a time on this server. The script allows one Full Quality
// Region download or delete per box (iiab#4574), so a second job waits here as 'queued' instead of
// failing on the lock, and a job's purge ends before the next job starts. In memory only: a job
// takes the turn when it is free and gives it back in its `finally`; a restart starts with no holder.
let turnHolder: string | null = null;
const TURN_POLL_MS = 2_000;

async function waitForTurn(ctx: RunnerContext, holder: string, onWait: () => void): Promise<void> {
    if (turnHolder !== null && turnHolder !== holder) onWait();
    while (turnHolder !== null && turnHolder !== holder) {
        await delay(TURN_POLL_MS, ctx.signal).catch(() => { /* stopped: checked next */ });
        throwIfStopped(ctx);
    }
    turnHolder = holder;
}

function releaseTurn(holder: string): void {
    if (turnHolder === holder) turnHolder = null;
}

// The box lock is held outside this server (a delete from the app, or a terminal command):
// try again after this wait. Each try fetches the map catalog, so the wait is not short.
const BUSY_RETRY_MS = 30_000;

const mapsRunner: (ctx: RunnerContext) => Promise<void> = async (ctx) => {
    const item = (ctx.items[0] ?? {}) as MapItem;
    const name = String(item.name ?? '');
    const rawBox = String(item.box ?? '');
    if (!REGION_NAME_RE.test(name)) throw new Error('invalid region name (A-Z a-z 0-9 _ -, length 1-34)');
    const parsed = parseBox(rawBox);
    if (!parsed.ok) throw new Error(parsed.error);

    try {
        await waitForTurn(ctx, ctx.job.id, () => ctx.update({ phase: 'queued', percent: -1, detail: name }));
    } catch (e) {
        // Canceled while it waited: a resumed job can still have the unfinished files of its pause.
        if (e instanceof CanceledError) discardRegion(name, ctx.job.id);
        throw e;
    }
    try {
        await runRegion(ctx, name, parsed.box, Math.max(0, Number(item.est) || 0));
    } finally {
        releaseTurn(ctx.job.id);
    }
};

async function runRegion(ctx: RunnerContext, name: string, box: string, estBytes: number): Promise<void> {
    ctx.update({ phase: 'processing', percent: -1, detail: name });
    // Read once the job has its turn: a purge deletes the region only when this job made it.
    const existedAtStart = name in listFqrRegions();
    // K2GO-317: DashNode opts into the custom go-pmtiles build's integrator mode: json = the
    // machine-readable progress parsed below. The arguments follow the installed script's CLI.
    const modern = installedHasModernCli();
    const args = extractArgs(name, box, modern);

    // tile-extract.py runs three sequential pmtiles extracts (vector, satellite, terrain), each with
    // --progress=json (go-pmtiles states commit): one JSON line per second on stdout carrying
    // bytes_done/bytes_total, speed_bps, eta_s and a per-extract phase (plan|download|finalize|done).
    // We derive ONE overall percent = (completed layers + the current layer's byte fraction) / 3, so
    // the bar climbs 0..100 ONCE instead of the old per-layer 0->100 reset (the "dancing" bar). A layer
    // is complete on its "phase":"done" line. The within-layer fraction is the byte ratio (not a
    // scraped text percent), so it is smooth too. How a non-zero end is handled is decided by
    // classifyExtractEnd (maps.socket.ts) from the engine's flags, the watchdog, the last JSON
    // exit_code and the script's own exit code and messages. speed and eta come from the JSON.
    const TOTAL_LAYERS = 3;
    let layersDone = 0;
    let overall = 0;   // monotonic across the whole FQR
    // K2GO-317: the device passes the total-bytes estimate (the consent "transfer" figure) so the ring and ETA
    // run off CUMULATIVE bytes vs that total : not 1/3-per-layer buckets (which stick on the big layer and jump
    // at layer boundaries) : and off a SMOOTHED speed, not go-pmtiles' raw per-layer eta_s (instantaneous, and
    // reset every layer). estBytes 0 (an old job) falls back to the layer-bucket math.
    let completedBytes = 0;    // bytes of the layers already finished this run
    let curLayerTotal = 0;     // the in-progress layer's byte total
    let emaBps = -1;           // EMA of speed_bps; smooths the bursty per-request rate that made the ETA swing
    let speedSamples = 0;      // warm-up guard: hold the ETA until the smoothed speed has settled
    const SPEED_ALPHA = 0.25;  // ~4 s window at one JSON line per second
    const WARMUP_SAMPLES = 3;

    interface RunEnd { code: number | null; signal: NodeJS.Signals | null; stalled: boolean; lastJsonExit: number | null; stderrTail: string; }

    const runOnce = () => new Promise<RunEnd>((resolve, reject) => {
        // K2GO-317 follow-up: go-pmtiles prints exit_code on each layer's last JSON line. On a stop we read it
        // to tell a resumable network drop (2) from a hard failure. The last value seen is the relevant one.
        let lastJsonExit: number | null = null;
        // K2GO-317: set true when WE proactively SIGTERM a stalled download (watchdog below), so the end
        // keeps the journal and surfaces PAUSED just like an engine pause.
        let stalled = false;
        // A bounded tail of stderr: the script's own messages (lock busy, network errors) are read from it.
        let stderrTail = '';
        // ADFA-4896: detached so `sudo` is a process-group leader and the engine's group-kill takes
        // the python worker down with it (otherwise pause/cancel only kill the sudo wrapper and the
        // extract finishes in the background). No stdin: with --yes the script asks nothing.
        const p = ctx.spawn('sudo', args, { env: { ...process.env }, detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
        // K2GO-317 (progress retention): go-pmtiles' own network give-up checkpoints a STALE journal and
        // loses the bytes since its last periodic checkpoint. A SIGTERM makes it flush the CURRENT bytes
        // losslessly (measured: it resumes from exactly where it stopped), so BOTH watchdogs below SIGTERM
        // the download group to turn a hang into a lossless, resumable pause.
        // STALL (byte): fires when the transfer is truly WEDGED : no byte progress AND no retry progress for
        // STALL_MS, in phase:download. A normal network drop is NOT a wedge : go-pmtiles retries (bumping
        // `retries`), which we count as activity and re-arm, so this does not preempt its retry; that drop
        // takes go-pmtiles' own retry-then-exit_code-2 path. STALL_MS is long (30 s) because several normal
        // phases make no bytes for a while (plan, finalize/fsync, the next layer's start, a 5 s retry wait
        // plus a slow new connection); a shorter window false-pauses, which re-plans the layer and, before
        // the go-pmtiles skip fix, re-downloads every completed layer : worse than no watchdog.
        // IDLE (line): go-pmtiles emits one JSON line per second in EVERY phase, so NO line for IDLE_MS means
        // the process is frozen (e.g. storage stopped responding in plan or finalize, which the byte
        // watchdog, armed only in download, would miss). Re-armed on every progress line; disarmed once all
        // layers are done, so the final move (which emits no lines) does not false-fire. An engine
        // pause/cancel still wins: it fires first and classifyExtractEnd checks the engine flags first.
        const STALL_MS = 30_000;
        const IDLE_MS = 30_000;
        let lastBytes = -1;
        let lastRetries = -1;   // a change in go-pmtiles' retry count is activity, not a stall
        let stallTimer: ReturnType<typeof setTimeout> | null = null;
        let idleTimer: ReturnType<typeof setTimeout> | null = null;
        const clearStall = () => { if (stallTimer) { clearTimeout(stallTimer); stallTimer = null; } };
        const clearIdle = () => { if (idleTimer) { clearTimeout(idleTimer); idleTimer = null; } };
        // Turn a stall or freeze into a lossless, resumable pause: SIGTERM the group so go-pmtiles flushes a
        // complete journal. The `stalled` guard makes either watchdog fire at most once.
        const sigtermPause = (msg: string): void => {
            if (stalled) return;
            stalled = true;
            ctx.log(msg);
            try { if (p.pid) process.kill(-p.pid, 'SIGTERM'); } catch { /* group already gone */ }
            try { p.kill('SIGTERM'); } catch { /* already gone */ }
        };
        const armStall = () => {
            clearStall();
            stallTimer = setTimeout(() => sigtermPause('[maps] download stalled; flushing the resume journal and pausing (no progress lost)'), STALL_MS);
        };
        const armIdle = () => {
            clearIdle();
            idleTimer = setTimeout(() => sigtermPause('[maps] no progress line for 30 s (extract frozen); flushing the resume journal and pausing'), IDLE_MS);
        };
        // pmtiles writes the JSON progress on stdout, one line per second. A 'data' chunk can split a
        // line across two events, so buffer stdout and act only on complete '\n'-terminated lines: an
        // unbuffered split would drop that second's progress. stderr carries pmtiles/script logs (never
        // the JSON), so it is logged as-is.
        const handleLine = (line: string) => {
            const s = line.trim();
            if (!s) return;
            ctx.log(s);
            if (!s.startsWith('{') || s.indexOf('"phase"') < 0) return;
            let j: { phase?: string; bytes_done?: number; bytes_total?: number; speed_bps?: number; exit_code?: number; retries?: number };
            try { j = JSON.parse(s); } catch { return; }
            if (typeof j.exit_code === 'number') lastJsonExit = j.exit_code;   // present on each layer's last line
            if (typeof j.phase !== 'string') return;
            armIdle();   // any progress line is a heartbeat: re-arm the frozen-process watchdog
            let etaOut = -1;   // seconds remaining, -1 = unknown (jobs.ts holds the previous value)
            if (j.phase === 'download' && (j.bytes_total ?? 0) > 0) {
                curLayerTotal = j.bytes_total as number;
                // bytes_done ALREADY includes resumed_bytes (a resumed layer starts at the resumed amount and
                // climbs to bytes_total), so cumulative progress is just completedBytes + bytes_done.
                const bd = j.bytes_done ?? 0;
                // Re-arm on byte progress OR a rise in the retry count (go-pmtiles is retrying, not wedged),
                // so a retry cycle with no new bytes does not trip the stall watchdog.
                const rt = j.retries ?? 0;
                if (bd !== lastBytes || rt !== lastRetries) { lastBytes = bd; lastRetries = rt; armStall(); }
                const doneBytes = completedBytes + bd;
                const sp = (typeof j.speed_bps === 'number' && j.speed_bps > 0) ? j.speed_bps : 0;
                if (sp > 0) { emaBps = emaBps < 0 ? sp : SPEED_ALPHA * sp + (1 - SPEED_ALPHA) * emaBps; speedSamples++; }
                if (estBytes > 0) {
                    // On the last layer every size is known, so the exact sum is the true total (this corrects
                    // an over/under estimate); on earlier layers trust the whole-region estimate (it covers the
                    // layers not seen yet). max() keeps a low estimate from reading over 100 before then.
                    const known = completedBytes + curLayerTotal;
                    const total = (layersDone >= TOTAL_LAYERS - 1) ? known : Math.max(estBytes, known);
                    overall = Math.max(overall, Math.min(99, Math.round((doneBytes / total) * 100)));
                    if (speedSamples >= WARMUP_SAMPLES && emaBps > 0) etaOut = Math.max(0, Math.round((total - doneBytes) / emaBps));
                } else {
                    const frac = Math.min(1, bd / (j.bytes_total as number));   // fallback: per-layer bucket
                    overall = Math.max(overall, Math.min(99, Math.round(((layersDone + frac) / TOTAL_LAYERS) * 100)));
                    if (speedSamples >= WARMUP_SAMPLES && emaBps > 0 && curLayerTotal > bd) etaOut = Math.max(0, Math.round((curLayerTotal - bd) / emaBps));
                }
            } else if (j.phase === 'finalize' || j.phase === 'done') {
                clearStall();   // nothing downloading now: do not let a stale watchdog fire
                if ((j.bytes_total ?? 0) > 0) curLayerTotal = j.bytes_total as number;   // a layer with no download line
                const doneBytes = completedBytes + curLayerTotal;
                if (estBytes > 0) {
                    const total = (layersDone >= TOTAL_LAYERS - 1) ? doneBytes : Math.max(estBytes, doneBytes);
                    overall = Math.max(overall, Math.min(99, Math.round((doneBytes / total) * 100)));
                } else {
                    overall = Math.max(overall, Math.min(99, Math.round(((layersDone + 1) / TOTAL_LAYERS) * 100)));
                }
            }
            const patch: JobUpdate = { phase: 'processing', percent: overall, detail: name };
            if (emaBps > 0) patch.speed = Math.round(emaBps);   // smoothed, so the "size : speed" line stops jumping
            if (etaOut >= 0) patch.eta = etaOut;
            ctx.update(patch);
            if (j.phase === 'done') {
                layersDone = Math.min(TOTAL_LAYERS, layersDone + 1);
                completedBytes += curLayerTotal;   // roll the finished layer into the cumulative base
                curLayerTotal = 0;
                if (layersDone >= TOTAL_LAYERS) clearIdle();   // all layers done: the final move emits no lines
            }
        };
        let stdoutBuf = '';
        p.stdout?.on('data', (buf: Buffer) => {
            stdoutBuf += buf.toString();
            let nl: number;
            while ((nl = stdoutBuf.indexOf('\n')) >= 0) {
                handleLine(stdoutBuf.slice(0, nl));
                stdoutBuf = stdoutBuf.slice(nl + 1);
            }
        });
        p.stderr?.on('data', (buf: Buffer) => {
            const t = buf.toString().trim();
            if (!t) return;
            ctx.log(t);
            stderrTail = (stderrTail + '\n' + t).slice(-4000);   // bounded, so a message split across chunks still matches
        });
        p.on('error', reject);
        p.on('exit', (code, signal) => {
            clearStall();
            clearIdle();
            resolve({ code, signal, stalled, lastJsonExit, stderrTail });
        });
    });

    for (;;) {
        let end: RunEnd;
        try {
            end = await runOnce();
        } catch (e) {
            // The spawn itself failed (no sudo, no script): nothing ran, so there is nothing to purge.
            throwIfStopped(ctx);
            throw e;
        }
        if (end.code === 0) break;
        const verdict = classifyExtractEnd({
            stop: classifyStop(ctx), stalled: end.stalled, lastJsonExit: end.lastJsonExit,
            exitCode: end.code, stderrTail: end.stderrTail,
        });
        if (verdict === 'busy') {
            ctx.log('[maps] another Full Quality Region download or delete is running on the box; waiting');
            ctx.update({ phase: 'queued', percent: overall > 0 ? overall : -1, detail: name });
            await delay(BUSY_RETRY_MS, ctx.signal).catch(() => { /* stopped: checked next */ });
            if (classifyStop(ctx) === 'canceled') { await purgeRegion(name, false, ctx.log); throw new CanceledError(); }
            throwIfStopped(ctx);
            ctx.update({ phase: 'processing', percent: overall > 0 ? overall : -1, detail: name });
            continue;
        }
        // The custom go-pmtiles build keeps an on-disk resume journal (<output>.resume, the single owner
        // of the checkpoint), so re-running tile-extract continues from it instead of re-extracting from 0.
        if (verdict === 'paused') throw new PausedError();
        // A cancel or a hard error discards this job's work: its unfinished download, and the region
        // dir only if this job made it (a cancel or a failure during the final move).
        await purgeRegion(name, jobOwnsRegion(existedAtStart, end.stderrTail), ctx.log);
        if (verdict === 'canceled') throw new CanceledError();
        const last = end.stderrTail.trim().split('\n').pop() ?? '';
        throw new Error(end.signal ? `tile-extract killed by ${end.signal}`
            : `tile-extract exited with code ${end.code}${last ? `: ${last}` : ''}`);
    }

    // A stop can race in exactly as the extract finishes (code 0). Honor it rather than marking
    // done. A cancel removes the region this job just made; a pause keeps it (resume then re-runs
    // and ends at once: the region exists, so the script does nothing and exits 0).
    const lateStop = classifyStop(ctx);
    if (lateStop === 'canceled') { await purgeRegion(name, !existedAtStart, ctx.log); throw new CanceledError(); }
    if (lateStop === 'paused') throw new PausedError();

    // With --yes the script exits 0 and downloads nothing when the box overlaps a region (a queued
    // job meets the region an earlier job made). Report that, not 'done'. Current CLI only: an older
    // rootfs can keep its regions in a layout that listFqrRegions does not read.
    if (modern && !(name in listFqrRegions())) {
        await purgeRegion(name, false, ctx.log);   // no region: this removes only unfinished files
        throw new Error(`nothing was downloaded for "${name}": the box overlaps a downloaded region`);
    }

    ctx.update({ phase: 'done', percent: 100 });
}

/** Remove the unfinished download of a job that is not running (it was paused, or it was canceled
 *  while it waited). It waits for the turn, so it never deletes under a running job. Fire-and-forget:
 *  a restart before the turn comes drops it, and the files stay until the next delete of that name. */
function discardRegion(name: string, jobId: string): void {
    const holder = `discard:${jobId}`;
    const log = (l: string) => { if (l) console.log(`[maps] ${jobId}: ${l}`); };
    void (async () => {
        while (turnHolder !== null) await delay(TURN_POLL_MS);
        turnHolder = holder;
        try { await purgeRegion(name, false, log); } finally { releaseTurn(holder); }
    })();
}

/** Cancel of a PAUSED maps job: its runner already exited, so nothing else removes the unfinished
 *  download it kept for Resume. */
function discardPausedRegion(job: Job): void {
    let name = '';
    try { name = String((JSON.parse(job.target) as MapItem[])[0]?.name ?? ''); } catch { /* bad row */ }
    if (REGION_NAME_RE.test(name)) discardRegion(name, job.id);
}

jobs.registerRunner('maps', mapsRunner);
jobs.registerDiscard('maps', discardPausedRegion);

export { mapsRunner };
