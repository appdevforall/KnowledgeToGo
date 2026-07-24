// sockets/kiwix.exec.ts — ADFA-4838
//
// Kiwix runner for the durable job engine: download the requested ZIM(s) with aria2,
// then rebuild the Kiwix library index. Progress is reported structured (percent +
// bytes/sec) instead of streamed as terminal text. Ported from the Phase 1
// kiwix.socket handler, minus the socket/closure lifetime — the job outlives any
// client (see jobs.ts).
import { jobs, RunnerContext, CanceledError } from './jobs';
import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';

const ZIMS_DIR = '/library/zims/content/';
const BASE_URL = 'https://download.kiwix.org/zim/wikipedia/';
const INDEXER = '/usr/bin/iiab-make-kiwix-lib';
const SAFETY_BUFFER_BYTES = 5 * 1024 * 1024 * 1024; // keep >=5 GB free

// ADFA-4832 sync note: these aria2 flags mirror the app-side downloader
// (controller/app/.../Aria2Manager.java) and the Phase 1 socket handler. Keep the
// three in sync; if you change one, change the others and document any divergence.
const ARIA2_ARGS: string[] = [
    '-d', ZIMS_DIR,
    '--continue=true',
    '--allow-overwrite=true',
    '--auto-file-renaming=false',
    '--max-connection-per-server=4',
    '--split=16',
    '--follow-metalink=mem',
    '--check-integrity=true',
    '--console-log-level=warn',
    '--summary-interval=1',
    '--download-result=hide',
    '--async-dns=false',
    '-Z',
    '-j', '5',
];

/** Convert an aria2 rate token ("34MiB", "512KiB", "1.2MB") to bytes/sec. */
function parseRate(token: string): number {
    const m = /^([\d.]+)\s*([KMGT]?i?B)?/i.exec(token);
    if (!m) return 0;
    const val = parseFloat(m[1]);
    const unit = (m[2] || 'B').toUpperCase();
    const mult: Record<string, number> = {
        B: 1, KIB: 1024, MIB: 1024 ** 2, GIB: 1024 ** 3, TIB: 1024 ** 4,
        KB: 1000, MB: 1e6, GB: 1e9, TB: 1e12,
    };
    return Math.round(val * (mult[unit] ?? 1));
}

/** Best-effort free-space guard so we never fill the disk. */
function assertFreeSpace(): void {
    try {
        const df = execSync('df -k /').toString().trim().split('\n');
        const cols = df[df.length - 1].split(/\s+/);
        const availableBytes = parseInt(cols[3], 10) * 1024;
        if (availableBytes < SAFETY_BUFFER_BYTES) {
            const freeGB = (availableBytes / 1024 ** 3).toFixed(1);
            throw new Error(`Not enough free space (${freeGB} GB); need a >5 GB buffer.`);
        }
    } catch (e) {
        if (e instanceof Error && e.message.startsWith('Not enough')) throw e;
        // df failed for some OS reason — proceed with caution rather than blocking.
    }
    // TODO(ADFA-4838): also subtract the requested ZIMs' catalog sizes once the
    // catalog is wired into the engine (Phase 1 did this with getKiwixCatalog()).
}

function cleanupMetadata(): void {
    try {
        for (const f of fs.readdirSync(ZIMS_DIR)) {
            if (/\.(meta4|aria2|torrent)$/.test(f)) fs.unlinkSync(path.join(ZIMS_DIR, f));
        }
    } catch { /* non-fatal */ }
}

const kiwixRunner: (ctx: RunnerContext) => Promise<void> = async (ctx) => {
    const zims = ctx.ids.map((z) => path.basename(z)).filter((z) => z.endsWith('.zim'));
    if (zims.length === 0) throw new Error('no ZIMs requested');

    assertFreeSpace();
    ctx.throwIfCanceled();

    // --- Download phase -----------------------------------------------------
    ctx.update({ phase: 'downloading', percent: 0, speed: 0, detail: zims.join(', ') });
    const urls = zims.map((z) => BASE_URL + z);

    await new Promise<void>((resolve, reject) => {
        const dl = ctx.spawn('/usr/bin/aria2c', [...ARIA2_ARGS, ...urls]);
        const onData = (buf: Buffer) => {
            const text = buf.toString();
            // A single chunk can carry several summary lines; take the LAST %/rate.
            const re = /\((\d+)%\).*?DL:([^\s]+)/g;
            let m: RegExpExecArray | null;
            let lastPct = -1;
            let lastRate = '';
            while ((m = re.exec(text)) !== null) { lastPct = parseInt(m[1], 10); lastRate = m[2]; }
            if (lastPct >= 0) ctx.update({ phase: 'downloading', percent: lastPct, speed: parseRate(lastRate) });
        };
        dl.stdout?.on('data', onData);
        dl.stderr?.on('data', onData);
        dl.on('error', reject);
        dl.on('exit', (code, signal) => {
            if (signal === 'SIGKILL' || ctx.isCanceled()) return reject(new CanceledError());
            if (code === 0) resolve();
            else reject(new Error(`aria2 exited with code ${code}`));
        });
    });

    cleanupMetadata();
    ctx.throwIfCanceled();

    // --- Index phase --------------------------------------------------------
    if (fs.existsSync(INDEXER)) {
        ctx.update({ phase: 'indexing', percent: -1, speed: 0 });
        await new Promise<void>((resolve, reject) => {
            const idx = ctx.spawn(INDEXER, []);
            idx.stdout?.on('data', (d: Buffer) => ctx.log(d.toString().trim()));
            idx.stderr?.on('data', (d: Buffer) => ctx.log(d.toString().trim()));
            idx.on('error', reject);
            idx.on('exit', (code, signal) => {
                if (signal === 'SIGKILL' || ctx.isCanceled()) return reject(new CanceledError());
                resolve(); // indexing exit code is advisory; the catalog reload is the real signal
            });
        });
    }

    ctx.update({ phase: 'done', percent: 100, speed: 0 });
};

jobs.registerRunner('kiwix', kiwixRunner);

export { kiwixRunner };
