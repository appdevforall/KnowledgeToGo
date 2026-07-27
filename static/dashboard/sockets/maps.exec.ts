// sockets/maps.exec.ts — ADFA-4838
//
// Maps runner for the durable job engine: extract a tile region via tile-extract.py.
// Ported from the Phase 1 maps.socket handler; reuses its box validation. A job item
// is { name, box, noninteractive? }; deletion stays a separate op (not a long job).
import { jobs, RunnerContext, CanceledError, JobUpdate } from './jobs';
import { parseBox } from './maps.socket';
import path from 'path';

const SCRIPTS_DIR = '/opt/iiab/maps/tile-extract/';
const EXTRACT_SCRIPT = path.join(SCRIPTS_DIR, 'tile-extract.py');
// name: letters/digits/hyphen/underscore, 1..34 (same rule as the socket handler).
const NAME_RE = /^[A-Za-z0-9_-]{1,34}$/;

interface MapItem { name?: string; box?: string; noninteractive?: boolean; }

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
        const p = ctx.spawn('sudo', args, { env: { ...process.env, PYTHONUNBUFFERED: '1' } });
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
            if (signal === 'SIGKILL' || ctx.isCanceled()) return reject(new CanceledError());
            if (code === 0) resolve();
            else reject(new Error(`tile-extract exited with code ${code}`));
        });
    });

    ctx.update({ phase: 'done', percent: 100 });
};

jobs.registerRunner('maps', mapsRunner);

export { mapsRunner };
