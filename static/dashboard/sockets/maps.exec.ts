// sockets/maps.exec.ts — ADFA-4838
//
// Maps runner for the durable job engine: extract a tile region via tile-extract.py.
// Ported from the Phase 1 maps.socket handler; reuses its box validation. A job item
// is { name, box, noninteractive? }; deletion stays a separate op (not a long job).
import { jobs, RunnerContext, CanceledError, PausedError, classifyStop, JobUpdate } from './jobs';
import { parseBox } from './maps.socket';
import { spawn } from 'child_process';
import fs from 'fs';
import path from 'path';

const SCRIPTS_DIR = '/opt/iiab/maps/tile-extract/';
const EXTRACT_SCRIPT = path.join(SCRIPTS_DIR, 'tile-extract.py');
// ADFA-4896: tile-extract.py extracts each pmtiles into TMP_DIR and only shutil.move()s all three
// into the served dir once ALL have finished, updating extracts.json last. So a killed extract
// leaves half-written pmtiles HERE (never in the catalog) — and the script's own `delete` only
// cleans the served dir, NOT this one. We prune these ourselves. Must match TMP_DIR in the script.
const MAPS_TMP_DIR = '/library/downloads/maps';
// name: letters/digits/hyphen/underscore, 1..34 (same rule as the socket handler).
const NAME_RE = /^[A-Za-z0-9_-]{1,34}$/;

interface MapItem { name?: string; box?: string; noninteractive?: boolean; est?: number; }

/** ADFA-4896: run a tile-extract maintenance command to completion, best-effort. Used for the
 *  stop/cancel cleanup, which must run while the job's own context is being torn down, so it does
 *  NOT go through ctx.spawn (that child would be killed with the job). Never rejects — a failed
 *  purge is logged, not fatal. */
function runMapsCmd(args: string[], log: (l: string) => void): Promise<void> {
    return new Promise((resolve) => {
        const p = spawn('sudo', [EXTRACT_SCRIPT, ...args]);
        p.stdout?.on('data', (d: Buffer) => log(d.toString().trim()));
        p.stderr?.on('data', (d: Buffer) => log(d.toString().trim()));
        p.on('error', () => resolve());
        p.on('exit', () => resolve());
    });
}

/** ADFA-4896: remove a region's half-written pmtiles left in TMP_DIR by a killed extract — the
 *  script's `delete` doesn't touch this dir. Name-scoped (`*.full-region.<name>.pmtiles` and its
 *  K2GO-317 `.resume` journal), so a concurrent extract for another region is untouched. Best-effort. */
function cleanupTmpPartials(name: string, log: (l: string) => void): void {
    try {
        const base = `.full-region.${name}.pmtiles`;
        for (const f of fs.readdirSync(MAPS_TMP_DIR)) {
            // K2GO-317: the partial AND its sibling resume journal (<partial>.resume). The journal
            // alone would linger and mislead resolve_source / a later extract of the same name.
            if (f.endsWith(base) || f.endsWith(base + '.resume')) {
                try { fs.unlinkSync(path.join(MAPS_TMP_DIR, f)); log(`[maps] pruned partial ${f}`); } catch { /* gone */ }
            }
        }
    } catch { /* dir missing / unreadable — nothing to prune */ }
}

/** ADFA-4896 / K2GO-317: discard a region's partial extract and drop it from the catalog. Used for a
 *  CANCEL and for a hard (non-resumable) error only. A pause or a resumable network drop does NOT come
 *  here: those keep the partial + .resume journal so a re-run continues from the checkpoint. Two places
 *  to clean: TMP_DIR (a mid-extract kill) and, via the script's `delete` (which runs update-json itself),
 *  the served dir + extracts.json (the rare race where the extract finished just as the stop landed).
 *  delete on a name that was never served is a harmless no-op. */
async function purgeRegion(name: string, log: (l: string) => void): Promise<void> {
    cleanupTmpPartials(name, log);
    await runMapsCmd(['delete', name], log);
}

const mapsRunner: (ctx: RunnerContext) => Promise<void> = async (ctx) => {
    const item = (ctx.items[0] ?? {}) as MapItem;
    const name = String(item.name ?? '');
    const rawBox = String(item.box ?? '');
    if (!NAME_RE.test(name)) throw new Error('invalid region name (A-Z a-z 0-9 _ -, length 1-34)');
    const parsed = parseBox(rawBox);
    if (!parsed.ok) throw new Error(parsed.error);

    ctx.update({ phase: 'processing', percent: -1, detail: name });
    // K2GO-317: DashNode opts into the custom build's integrator mode. sudo resets the environment
    // (Defaults env_reset, only debian_chroot kept), so the vars are passed THROUGH sudo with `env`,
    // not on the node-side spawn env (which sudo would strip). json = the machine-readable progress
    // parsed below; 8 = the measured throughput knee (tunable upstream via IIAB_PMTILES_DOWNLOAD_THREADS).
    const args = ['env', 'IIAB_PMTILES_PROGRESS=json', 'IIAB_PMTILES_DOWNLOAD_THREADS=8',
        'PYTHONUNBUFFERED=1', EXTRACT_SCRIPT, 'extract', name, parsed.box, 'noninteractive'];

    // tile-extract.py runs three sequential pmtiles extracts (vector, satellite, terrain), each with
    // --progress=json (go-pmtiles states commit): one JSON line per second on stdout carrying
    // bytes_done/bytes_total, speed_bps, eta_s and a per-extract phase (plan|download|finalize|done).
    // We derive ONE overall percent = (completed layers + the current layer's byte fraction) / 3, so
    // the bar climbs 0..100 ONCE instead of the old per-layer 0->100 reset (the "dancing" bar). A layer
    // is complete on its "phase":"done" line. The within-layer fraction is the byte ratio (not a
    // scraped text percent), so it is smooth too. A stop by pause or cancel is read from the engine's
    // job flags (classifyStop in the catch below). A network drop, which is not an engine stop, is read
    // from the last JSON line's exit_code (2 = resumable; the catch keeps the journal for it). speed and
    // eta come from the JSON.
    const TOTAL_LAYERS = 3;
    let layersDone = 0;
    let overall = 0;   // monotonic across the whole FQR
    // K2GO-317: the device passes the total-bytes estimate (the consent "transfer" figure) so the ring and ETA
    // run off CUMULATIVE bytes vs that total : not 1/3-per-layer buckets (which stick on the big layer and jump
    // at layer boundaries) : and off a SMOOTHED speed, not go-pmtiles' raw per-layer eta_s (instantaneous, and
    // reset every layer). estBytes 0 (an old job) falls back to the layer-bucket math.
    const estBytes = Math.max(0, Number(item.est) || 0);
    let completedBytes = 0;    // bytes of the layers already finished this run
    let curLayerTotal = 0;     // the in-progress layer's byte total
    let emaBps = -1;           // EMA of speed_bps; smooths the bursty per-request rate that made the ETA swing
    let speedSamples = 0;      // warm-up guard: hold the ETA until the smoothed speed has settled
    const SPEED_ALPHA = 0.25;  // ~4 s window at one JSON line per second
    const WARMUP_SAMPLES = 3;
    // K2GO-317 follow-up: go-pmtiles prints exit_code on each layer's last JSON line. On a stop we read it
    // to tell a resumable network drop (2) from a hard failure. The last value seen is the relevant one.
    let lastExitCode: number | null = null;
    // K2GO-317: set true when WE proactively SIGTERM a stalled download (watchdog below), so the catch
    // keeps the journal and surfaces PAUSED just like an engine pause.
    let stalled = false;
    // K2GO-317: tile-extract.py's own mirror/catalog HEAD request (between layers) can fail with a transient
    // network error when the phone link blips. go-pmtiles never ran for that layer, so there is no exit_code 2;
    // without this the catch would purge every completed layer, and the r2 skip could not then save them on a
    // retry. Detect the signature in stderr and treat it as resumable (keep partials, surface PAUSED). The
    // rolling tail guards against the signature splitting across stderr chunks.
    let sawNetError = false;
    let stderrTail = '';
    const NET_ERR_RE = /ConnectionError|Max retries exceeded|HTTPSConnectionPool|Failed to establish a new connection|Connection (refused|reset)|Temporary failure in name resolution|Name or service not known/i;

    await new Promise<void>((resolve, reject) => {
        // ADFA-4896: detached so `sudo` is a process-group leader and the engine's group-kill takes
        // the python worker down with it (otherwise pause/cancel only kill the sudo wrapper and the
        // extract finishes in the background).
        const p = ctx.spawn('sudo', args, { env: { ...process.env }, detached: true });
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
        // pause/cancel still wins: it fires first and the catch checks classifyStop before `stalled`.
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
            if (typeof j.exit_code === 'number') lastExitCode = j.exit_code;   // present on each layer's last line
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
            stderrTail = (stderrTail + '\n' + t).slice(-4000);   // bounded tail so a split signature still matches
            if (!sawNetError && NET_ERR_RE.test(stderrTail)) sawNetError = true;
        });
        p.on('error', reject);
        p.on('exit', (code, signal) => {
            clearStall();
            clearIdle();
            if (code === 0) return resolve();
            // Non-zero or killed: an engine pause/cancel (group-killed) or tile-extract's own exit.
            // The stop kind is read from the engine's job flags (classifyStop) in the catch, not from
            // this code: tile-extract.py remaps every non-zero child exit to 1 (see the catch note).
            reject(new Error(signal ? `tile-extract killed by ${signal}` : `tile-extract exited with code ${code}`));
        });
    }).catch(async (e: unknown) => {
        // The custom go-pmtiles build keeps an on-disk resume journal (<output>.resume, the single owner
        // of the checkpoint), so re-running tile-extract continues from it instead of re-extracting from 0.
        // A stop is resumable in two ways; for both we keep the journal and surface PAUSED, so the device
        // offers Resume (which re-runs the same command and continues from the checkpoint):
        //   1. An engine-initiated PAUSE (SIGTERM): the engine flags it, classifyStop sees it.
        //   2. A network drop: our watchdog SIGTERMs the stalled download (stalled=true) so go-pmtiles
        //      flushes the current bytes; if the watchdog did not catch it first, go-pmtiles gives up on its
        //      own and reports exit_code 2 on its last JSON line (tile-extract.py masks the process exit to
        //      1, so we read the JSON line). Either way we keep the journal.
        //   3. tile-extract.py's OWN mirror/catalog request hit a transient network error (sawNetError, from
        //      its stderr). go-pmtiles never ran for that layer, so there is no exit_code 2. We keep every
        //      completed layer so the resume skips it (r2) instead of re-downloading.
        // A cancel, or any other exit, is a hard error: purge.
        const stop = classifyStop(ctx);
        if (stop === 'canceled') { await purgeRegion(name, ctx.log); throw new CanceledError(); }
        if (stop === 'paused') throw new PausedError();     // keep partial: resume continues
        if (stalled) throw new PausedError();               // our stall-pause flushed a full journal
        if (lastExitCode === 2) throw new PausedError();    // go-pmtiles gave up on its own: still resumable
        if (sawNetError) throw new PausedError();           // tile-extract's own mirror request blipped: keep partials, resume skips completed layers
        await purgeRegion(name, ctx.log);                   // hard error: purge and surface
        throw e;
    });

    // A stop can race in exactly as the extract finishes (code 0). Honor it rather than marking
    // done. A cancel purges; a pause keeps the finished partial (resume then re-runs and completes
    // at once — the journal was already removed on success, so nothing half-written is left).
    const lateStop = classifyStop(ctx);
    if (lateStop === 'canceled') { await purgeRegion(name, ctx.log); throw new CanceledError(); }
    if (lateStop === 'paused') throw new PausedError();

    ctx.update({ phase: 'done', percent: 100 });
};

jobs.registerRunner('maps', mapsRunner);

export { mapsRunner };
