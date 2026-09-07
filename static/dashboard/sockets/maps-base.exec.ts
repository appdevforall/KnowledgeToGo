// sockets/maps-base.exec.ts -- K2GO-394
//
// Base-map runner for the durable job engine: download the selected GLOBAL map pmtiles
// (vector / satellite / terrain) with aria2, straight into the maps serve dir. This is a
// drop-in replacement for the aria2c the maps runrole used to run in-proot, which wedged
// at CN:0 on a full network drop and never recovered (K2GO-394): once the files are here,
// the runrole's `creates: dest_path` and the .meta4 size-probe both SKIP the download, so
// the role only post-processes (symlinks, maps-config.js).
//
// Kept SEPARATE from the FQR `maps` runner (maps.exec.ts / tile-extract.py) -- same engine,
// different concern. Reuses the PROVEN kiwix mechanism (see kiwix.exec.ts): the canonical
// aria2 flag set plus the withRetry OUTER loop that re-runs aria2 (which resumes via
// --continue) on a full-interface-loss exit (19 = DNS cannot resolve), surfaced on the poll
// as "Reconnecting n/5". No --lowest-speed-limit, on purpose (it turns a slow mobile link
// into a hard abort).
import { jobs, RunnerContext, CanceledError, PausedError, classifyStop } from './jobs';
import { withRetry } from './net-retry';
import fs from 'fs';
import path from 'path';

// = maps_serve_path = dest_base_path in roles/maps; the runrole moves each pmtiles here.
const MAPS_DIR = '/library/www/maps';
// The app passes one catalog_file_url per selected layer. Only plain https URLs (no shell is
// used -- spawn takes an argv -- so this guards SSRF/log-shape, not shell injection).
const SAFE_URL = /^https:\/\/[^\s'"`$<>|;()]+$/;

// ADFA-4832 CANONICAL aria2 flag set -- an EXACT copy of kiwix.exec.ts (only -d differs). The kiwix
// runner recovers from a Wi-Fi drop (aria2 --max-tries=5 absorbs in-flight blips; a FULL interface
// loss makes aria2 exit and the withRetry OUTER loop re-runs it, resuming via --continue), so this
// mirrors it exactly rather than inventing a retry model. Notably NO --lowest-speed-limit (it turns
// a slow mobile link into a hard abort). If you change a flag here, change kiwix.exec.ts and
// controller/app/.../Aria2Manager.java too.
const ARIA2_ARGS: string[] = [
    '-d', MAPS_DIR,
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
    '--max-tries=5',
    '--retry-wait=5',
    '--timeout=60',
    '--connect-timeout=15',
    '-Z',
    '-j', '5',
];

// The transient aria2 exit codes the OUTER loop re-runs (aria2 resumes via --continue): 1 unknown,
// 2 timeout, 6 network, 7 unfinished, 19 DNS, 29 HTTP 503. Terminal (not retried): 3/4 not found,
// 9 no space, 13 file exists.
const ARIA2_TRANSIENT_EXITS = new Set<number>([1, 2, 6, 7, 19, 29]);
// 5 VISIBLE reconnect waits (3, 6, 9, 18, 36 s ~= 72 s total), surfaced as "Reconnecting n/5".
const RETRY_DELAYS = [3_000, 6_000, 9_000, 18_000, 36_000];

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

/** Remove the download artifacts aria2 leaves next to a completed pmtiles. */
function cleanupMetadata(): void {
    try {
        for (const f of fs.readdirSync(MAPS_DIR)) {
            if (/\.(meta4|aria2)$/.test(f)) fs.unlinkSync(path.join(MAPS_DIR, f));
        }
    } catch { /* non-fatal */ }
}

// Clean-on-cancel (mirrors kiwix.exec.ts): a canceled download must not leave a partial behind. aria2
// preallocates the FULL file size, so a canceled partial looks complete to the maps role's
// `creates: dest_path` and to the is_proot presence assert -- the role would then serve a truncated
// pmtiles. Prune each requested file's partial and its .aria2/.meta4 siblings. A file with no .aria2
// is already complete, so leave it. An ERROR keeps the partial on purpose (resume via --continue).
function cleanupPartials(files: string[]): void {
    for (const f of files) {
        try {
            const control = path.join(MAPS_DIR, `${f}.aria2`);
            if (!fs.existsSync(control)) continue;   // no control file -> complete, do not delete it
            fs.unlinkSync(control);
            for (const sibling of [f, `${f}.meta4`]) {
                const p = path.join(MAPS_DIR, sibling);
                if (fs.existsSync(p)) fs.unlinkSync(p);
            }
        } catch { /* best-effort */ }
    }
}

const mapsBaseRunner: (ctx: RunnerContext) => Promise<void> = async (ctx) => {
    const urls = ctx.ids.map(String).filter((u) => u.length > 0);
    if (urls.length === 0) throw new Error('no base-map URLs requested');
    for (const u of urls) if (!SAFE_URL.test(u)) throw new Error(`unsafe base-map URL: ${u}`);
    // aria2 saves each URL under its basename in MAPS_DIR (no --out); the same basename the maps role
    // expects at dest_path. Used to prune the right partial on cancel.
    const files = urls.map((u) => path.basename(u));
    fs.mkdirSync(MAPS_DIR, { recursive: true });

    ctx.throwIfCanceled();
    ctx.update({ phase: 'downloading', speed: 0, detail: files.join(', ') });

    // Pass the DIRECT pmtiles URL (not <url>.meta4), the same way the kiwix runner passes the .zim
    // URL directly. aria2 downloads it into MAPS_DIR under its own basename -- exactly the runrole's
    // dest_path, so the role's `creates:` then skips the download. (An explicit .meta4 metalink is
    // what the in-proot runrole used, and metalink downloads are what wedged aria2 on a network drop
    // -- K2GO-394; --follow-metalink=mem still honors a metalink the mirror serves on its own.)
    try {
        await withRetry(() => new Promise<void>((resolve, reject) => {
            const dl = ctx.spawn('/usr/bin/aria2c', [...ARIA2_ARGS, ...urls]);
            const onData = (buf: Buffer) => {
                const text = buf.toString();
                const re = /\((\d+)%\).*?DL:([^\s]+)/g;
                let m: RegExpExecArray | null;
                let lastPct = -1;
                let lastRate = '';
                while ((m = re.exec(text)) !== null) { lastPct = parseInt(m[1], 10); lastRate = m[2]; }
                if (lastPct >= 0) { ctx.reportRetry(0, 0); ctx.update({ phase: 'downloading', percent: lastPct, speed: parseRate(lastRate) }); }
            };
            dl.stdout?.on('data', onData);
            dl.stderr?.on('data', onData);
            dl.on('error', reject);
            dl.on('exit', (code, signal) => {
                if (signal === 'SIGKILL' || ctx.isCanceled()) return reject(new CanceledError());
                if (code === 0) return resolve();
                // Carry the exit code so the outer loop tells a transient network failure (retry,
                // resuming via --continue) from a terminal one (not found / no space).
                const err = new Error(`aria2 exited with code ${code}`);
                (err as { code?: number }).code = code ?? -1;
                reject(err);
            });
        }), {
            delaysMs: RETRY_DELAYS,
            tries: RETRY_DELAYS.length + 1,
            signal: ctx.signal,
            isCanceled: ctx.isCanceled,
            isTransient: (e) => ARIA2_TRANSIENT_EXITS.has((e as { code?: number }).code ?? -1),
            onRetry: ({ attempt, err }) => {
                ctx.reportRetry(attempt, RETRY_DELAYS.length);
                ctx.log(`[basemaps] reconnect ${attempt}/${RETRY_DELAYS.length} after: ${err instanceof Error ? err.message : String(err)}`);
            },
        });
    } catch (e) {
        // pause KEEPS the partial (+ .aria2) so resume continues via --continue; a real error also
        // keeps it so a retry/reconcile resumes rather than restarting from zero.
        const stop = classifyStop(ctx);
        if (stop === 'paused') throw new PausedError();
        if (stop === 'canceled') { cleanupPartials(files); throw new CanceledError(); }
        throw e;
    }

    cleanupMetadata();
    ctx.update({ phase: 'done', percent: 100, speed: 0 });
};

jobs.registerRunner('basemaps', mapsBaseRunner);

export { mapsBaseRunner };
