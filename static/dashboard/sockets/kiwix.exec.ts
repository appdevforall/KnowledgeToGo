// sockets/kiwix.exec.ts — ADFA-4838
//
// Kiwix runner for the durable job engine: download the requested ZIM(s) with aria2,
// then rebuild the Kiwix library index. Progress is reported structured (percent +
// bytes/sec) instead of streamed as terminal text. Ported from the Phase 1
// kiwix.socket handler, minus the socket/closure lifetime — the job outlives any
// client (see jobs.ts).
import { jobs, RunnerContext, CanceledError } from './jobs';
import { withRetry, Aborted } from './net-retry';
import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';

const ZIMS_DIR = '/library/zims/content/';
// ADFA-5042: root of the Kiwix ZIM mirror. Each requested id MUST carry its own project subdirectory
// (e.g. "zimit/foo.zim", "other/bar.zim", "gutenberg/baz.zim") — ZIMs are not all under wikipedia/.
const BASE_URL = 'https://download.kiwix.org/zim/';
// Allowed id shape: subdir segment(s) + filename. Guards the outbound URL (no "..", no traversal).
const SAFE_ID = /^[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*$/;
const INDEXER = '/usr/bin/iiab-make-kiwix-lib';
const SAFETY_BUFFER_BYTES = 5 * 1024 * 1024 * 1024; // keep >=5 GB free

// ADFA-4832 CANONICAL aria2 flag set — mirrored in three places. If you change a flag here,
// change the others and update this note (nothing enforces it; a silent drift is the risk):
//   1. this file (REST/dash-node kiwix runner)
//   2. controller/app/.../Aria2Manager.java (the Android app downloader)
//   3. the Phase 1 socket handler
// Shared intent: --continue, --check-integrity, --max-connection-per-server=4, --split, no
// --lowest-speed-limit. Two DOCUMENTED divergences from the app, both about the retry model:
//   - retry count: app uses --max-tries=1 (its outer InstallService loop re-drives); REST uses
//     --max-tries=5 so aria2 absorbs in-flight blips itself (see the outer loop below for the
//     total-interface-loss case aria2 can't retry).
//   - timeouts: app 10s/5s (aggressive, its loop re-drives fast); REST 60s/15s (no fast re-drive).
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
    // ADFA-4894: aria2 absorbs in-flight blips itself (retry-wait never 0 — a 0-wait retry hammers a
    // struggling server). A FULL interface loss (exit 19, DNS can't resolve) is not something aria2
    // retries, so that case is handled by the outer withRetry loop below, not here. See the canonical
    // note above for the documented divergence from the app's --max-tries=1 model.
    '--max-tries=5',
    '--retry-wait=5',
    '--timeout=60',
    '--connect-timeout=15',
    // No --lowest-speed-limit, same as the app: it turns a slow link into a hard abort, which is the
    // opposite of resilient on the intermittent 3G this contract exists for.
    '-Z',
    '-j', '5',
];

// ADFA-4894: the outer reconnect loop for a FULL interface loss. aria2's --max-tries handles
// in-flight blips, but when Wi-Fi drops entirely aria2 exits (19 = name resolution failed) and
// can't retry a name it can't resolve. So we re-run aria2 — which resumes via --continue — on the
// transient exit codes, across a mobile handoff. NOT retried: 3/4 (not found), 9 (no space), 13
// (file exists) — those are terminal. Budget ~30s to outlast a Wi-Fi reassociation.
//   1 unknown · 2 timeout · 6 network · 7 unfinished · 19 DNS · 29 HTTP 503
const ARIA2_TRANSIENT_EXITS = new Set<number>([1, 2, 6, 7, 19, 29]);
const KIWIX_DL_TRIES = 5;
const KIWIX_RETRY_BASE_MS = 2_000;
const KIWIX_RETRY_MAX_MS = 15_000;

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

/**
 * ADFA-4894: clean-on-cancel. aria2 keeps a partial `.zim` + `.aria2` control file so a resume can
 * continue — right after a pause, wrong after a cancel, where the user gave the download up. So on
 * cancel we prune each requested file's partial and its control/metadata siblings, leaving nothing
 * half-written. A file with no `.aria2` is treated as already complete and left alone.
 */
function cleanupPartials(files: string[]): void {
    for (const f of files) {
        try {
            const control = path.join(ZIMS_DIR, `${f}.aria2`);
            if (!fs.existsSync(control)) continue;   // no control file -> complete, don't delete it
            fs.unlinkSync(control);
            for (const sibling of [f, `${f}.meta4`, `${f}.torrent`]) {
                const p = path.join(ZIMS_DIR, sibling);
                if (fs.existsSync(p)) fs.unlinkSync(p);
            }
        } catch { /* best-effort */ }
    }
}

const kiwixRunner: (ctx: RunnerContext) => Promise<void> = async (ctx) => {
    // ADFA-5042: keep each id's project subdir for the URL; use basename only for the local file/display.
    const ids = ctx.ids.map(String).map((z) => z.replace(/^\/+/, '')).filter((z) => z.endsWith('.zim'));
    if (ids.length === 0) throw new Error('no ZIMs requested');
    for (const id of ids) {
        // Require the project subdir — every ZIM on the mirror lives under one (/zim/<project>/…).
        if (id.includes('..') || !id.includes('/') || !SAFE_ID.test(id)) {
            throw new Error(`invalid ZIM id (expected "<project>/<file>.zim"): ${id}`);
        }
    }
    const files = ids.map((z) => path.basename(z));

    assertFreeSpace();
    ctx.throwIfCanceled();

    // --- Download phase -----------------------------------------------------
    ctx.update({ phase: 'downloading', percent: 0, speed: 0, detail: files.join(', ') });
    // Each id already carries its project subdir on the mirror (/zim/<project>/<file>).
    const urls = ids.map((z) => BASE_URL + z);

    try {
        await withRetry(() => new Promise<void>((resolve, reject) => {
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
                if (code === 0) return resolve();
                // Carry the aria2 exit code so the outer loop can tell a transient network failure
                // (retry, resuming via --continue) from a terminal one (not found / no space).
                const err = new Error(`aria2 exited with code ${code}`);
                (err as { code?: number }).code = code ?? -1;
                reject(err);
            });
        }), {
            tries: KIWIX_DL_TRIES,
            baseMs: KIWIX_RETRY_BASE_MS,
            maxMs: KIWIX_RETRY_MAX_MS,
            isCanceled: ctx.isCanceled,
            isTransient: (e) => ARIA2_TRANSIENT_EXITS.has((e as { code?: number }).code ?? -1),
            onRetry: ({ attempt, err }) => ctx.log(`[kiwix] reconnect attempt ${attempt} after: ${err instanceof Error ? err.message : String(err)}`),
        });
    } catch (e) {
        // ADFA-4894: a canceled download leaves nothing half-written; a real error KEEPS the partial
        // (+ .aria2) so a later run resumes it via --continue rather than starting from zero.
        if (e instanceof CanceledError || e instanceof Aborted || ctx.isCanceled()) {
            cleanupPartials(files);
            throw new CanceledError();
        }
        throw e;
    }

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
