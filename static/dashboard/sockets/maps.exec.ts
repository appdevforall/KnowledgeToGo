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
 *  script's `delete` doesn't touch this dir. Name-scoped (`*.full-region.<name>.pmtiles`), so a
 *  concurrent extract for another region is untouched. Best-effort. */
function cleanupTmpPartials(name: string, log: (l: string) => void): void {
    try {
        const suffix = `.full-region.${name}.pmtiles`;
        for (const f of fs.readdirSync(MAPS_TMP_DIR)) {
            if (f.endsWith(suffix)) {
                try { fs.unlinkSync(path.join(MAPS_TMP_DIR, f)); log(`[maps] pruned partial ${f}`); } catch { /* gone */ }
            }
        }
    } catch { /* dir missing / unreadable — nothing to prune */ }
}

/** ADFA-4896: discard a region's partial extract and drop it from the catalog. There is no maps
 *  checkpoint yet, so pause is stop+restart and cancel is remove — both discard the partial so a
 *  resume/retry re-extracts from a clean slate and nothing half-written is left behind or listed.
 *  Two places to clean: TMP_DIR (a mid-extract kill) and, via the script's `delete` (which runs
 *  update-json itself), the served dir + extracts.json (the rare race where the extract finished
 *  just as the stop landed). delete on a name that was never served is a harmless no-op. */
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
    const args = [EXTRACT_SCRIPT, 'extract', name, parsed.box, 'noninteractive'];

    // ADFA-4879: tile-extract.py runs three sequential pmtiles extracts (vector, satellite,
    // terrain). Each prints an in-layer bar ("fetching chunks NN%") and ends with one
    // "Extract transferred ... archive size of ..." line. We derive an OVERALL percent from the
    // completed-layer count refined by the current layer's %, so the bar climbs smoothly 0..100
    // instead of resetting three times. If the in-layer % doesn't survive the non-TTY pipe, the
    // layer-count alone still advances it (0 -> 33 -> 66), and the runner sets 100 on success.
    const TOTAL_LAYERS = 3;
    const RATE: Record<string, number> = { B: 1, kB: 1e3, MB: 1e6, GB: 1e9 };   // pmtiles unit -> bytes
    let layersDone = 0;
    let lastPct = 0;

    await new Promise<void>((resolve, reject) => {
        // ADFA-4896: detached so `sudo` is a process-group leader and the engine's group-kill takes
        // the python worker down with it (otherwise pause/cancel only kill the sudo wrapper and the
        // extract finishes in the background).
        const p = ctx.spawn('sudo', args, { env: { ...process.env, PYTHONUNBUFFERED: '1' }, detached: true });
        const onData = (buf: Buffer) => {
            const text = buf.toString();
            ctx.log(text.trim());
            const pm = [...text.matchAll(/fetching chunks\s+(\d+)\s*%/g)];
            if (pm.length) lastPct = parseInt(pm[pm.length - 1][1], 10);
            const completed = (text.match(/Extract transferred/g) || []).length;
            if (completed) { layersDone = Math.min(TOTAL_LAYERS, layersDone + completed); lastPct = 0; }
            const overall = Math.min(99, Math.round((layersDone * 100 + lastPct) / TOTAL_LAYERS));
            const patch: JobUpdate = { phase: 'processing', percent: overall, detail: name };
            // pmtiles prints a live transfer rate ("2.4 MB/s"); surface the latest as job.speed
            // (bytes/sec) so the app can show it. Omitted when absent, so it isn't reset to 0.
            const sm = [...text.matchAll(/([\d.]+)\s*(B|kB|MB|GB)\/s/g)];
            if (sm.length) { const last = sm[sm.length - 1]; patch.speed = Math.round(parseFloat(last[1]) * (RATE[last[2]] ?? 1)); }
            ctx.update(patch);
        };
        p.stdout?.on('data', onData);
        p.stderr?.on('data', onData);
        p.on('error', reject);
        p.on('exit', (code, signal) => {
            if (code === 0) return resolve();
            // Non-zero or killed: could be a pause/cancel (group-killed by the engine) or a real
            // failure. The catch below classifies it via classifyStop(ctx); this message is only
            // surfaced for a genuine error.
            reject(new Error(signal ? `tile-extract killed by ${signal}` : `tile-extract exited with code ${code}`));
        });
    }).catch(async (e: unknown) => {
        // ADFA-4896: any non-success purges the partial (no maps checkpoint -> never keep half-work),
        // then re-throws as pause/cancel/error so the engine records the right terminal phase. On
        // pause the job is resumable (resume re-extracts clean); on cancel the region is gone.
        await purgeRegion(name, ctx.log);
        const stop = classifyStop(ctx);
        if (stop === 'paused') throw new PausedError();
        if (stop === 'canceled') throw new CanceledError();
        throw e;
    });

    // A stop can race in exactly as the extract finishes (code 0). Honor it — purge and stop —
    // rather than marking done, so a paused/canceled job never leaves a completed region behind.
    const lateStop = classifyStop(ctx);
    if (lateStop) {
        await purgeRegion(name, ctx.log);
        throw lateStop === 'paused' ? new PausedError() : new CanceledError();
    }

    ctx.update({ phase: 'done', percent: 100 });
};

jobs.registerRunner('maps', mapsRunner);

export { mapsRunner };
