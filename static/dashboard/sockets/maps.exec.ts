// sockets/maps.exec.ts — ADFA-4838
//
// Maps runner for the durable job engine: extract a tile region via tile-extract.py.
// Ported from the Phase 1 maps.socket handler; reuses its box validation. A job item
// is { name, box, noninteractive? }; deletion stays a separate op (not a long job).
import { jobs, RunnerContext, CanceledError } from './jobs';
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

    await new Promise<void>((resolve, reject) => {
        const p = ctx.spawn('sudo', args, { env: { ...process.env, PYTHONUNBUFFERED: '1' } });
        const onData = (buf: Buffer) => {
            const text = buf.toString();
            ctx.log(text.trim());
            // tile-extract prints progress lines; surface a % if present, else stay indeterminate.
            const re = /(\d+)\s*%/g;
            let m: RegExpExecArray | null;
            let last = -1;
            while ((m = re.exec(text)) !== null) last = parseInt(m[1], 10);
            if (last >= 0) ctx.update({ phase: 'processing', percent: last });
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
