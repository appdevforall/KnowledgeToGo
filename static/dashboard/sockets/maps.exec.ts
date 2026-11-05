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

interface MapItem { name?: string; box?: string; noninteractive?: boolean; }

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
    // K2GO-317 follow-up: go-pmtiles prints exit_code on each layer's last JSON line. On a stop we read it
    // to tell a resumable network drop (2) from a hard failure. The last value seen is the relevant one.
    let lastExitCode: number | null = null;

    await new Promise<void>((resolve, reject) => {
        // ADFA-4896: detached so `sudo` is a process-group leader and the engine's group-kill takes
        // the python worker down with it (otherwise pause/cancel only kill the sudo wrapper and the
        // extract finishes in the background).
        const p = ctx.spawn('sudo', args, { env: { ...process.env }, detached: true });
        // pmtiles writes the JSON progress on stdout, one line per second. A 'data' chunk can split a
        // line across two events, so buffer stdout and act only on complete '\n'-terminated lines: an
        // unbuffered split would drop that second's progress. stderr carries pmtiles/script logs (never
        // the JSON), so it is logged as-is.
        const handleLine = (line: string) => {
            const s = line.trim();
            if (!s) return;
            ctx.log(s);
            if (!s.startsWith('{') || s.indexOf('"phase"') < 0) return;
            let j: { phase?: string; bytes_done?: number; bytes_total?: number; speed_bps?: number; eta_s?: number | null; exit_code?: number };
            try { j = JSON.parse(s); } catch { return; }
            if (typeof j.exit_code === 'number') lastExitCode = j.exit_code;   // present on each layer's last line
            if (typeof j.phase !== 'string') return;
            let frac = 0;
            if (j.phase === 'download' && (j.bytes_total ?? 0) > 0) {
                frac = Math.min(1, (j.bytes_done ?? 0) / (j.bytes_total as number));
            } else if (j.phase === 'finalize' || j.phase === 'done') {
                frac = 1;
            }
            overall = Math.max(overall, Math.min(99, Math.round(((layersDone + frac) / TOTAL_LAYERS) * 100)));
            const patch: JobUpdate = { phase: 'processing', percent: overall, detail: name };
            if (typeof j.speed_bps === 'number' && j.speed_bps > 0) patch.speed = j.speed_bps;
            if (typeof j.eta_s === 'number' && j.eta_s >= 0) patch.eta = Math.round(j.eta_s);
            ctx.update(patch);
            if (j.phase === 'done') layersDone = Math.min(TOTAL_LAYERS, layersDone + 1);
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
        p.stderr?.on('data', (buf: Buffer) => { const t = buf.toString().trim(); if (t) ctx.log(t); });
        p.on('error', reject);
        p.on('exit', (code, signal) => {
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
        //   2. A network drop: go-pmtiles gives up, keeps the journal, and reports exit_code 2 on its last
        //      JSON line. tile-extract.py masks the process exit to 1 (K2GO-317 follow-up), so we read the
        //      JSON line, not the exit status. A cancel, or any other exit, is a hard error: purge.
        const stop = classifyStop(ctx);
        if (stop === 'canceled') { await purgeRegion(name, ctx.log); throw new CanceledError(); }
        if (stop === 'paused') throw new PausedError();     // keep partial: resume continues
        if (lastExitCode === 2) throw new PausedError();    // resumable network drop: keep partial + journal
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
