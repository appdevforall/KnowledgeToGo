// sockets/aria2-download.ts - K2GO-443
//
// Shared aria2 download primitive for durable-job runners. Extracts the proven mechanism that
// kiwix.exec.ts and maps-base.exec.ts each copied (ADFA-4832 canonical flag set + the withRetry
// OUTER loop that survives a full interface loss): download a set of URLs into a dest dir with
// aria2c, report percent + bytes/sec on the job, and handle pause / cancel / reconnect the same way
// everywhere. New runners (code_assets, and later add-ons) call downloadWithAria2 instead of copying
// the flags a fourth time. kiwix.exec.ts / maps-base.exec.ts keep their copies until migrated here.
//
// Canonical aria2 flags are ALSO mirrored in controller/app/.../Aria2Manager.java (the Android
// downloader). If you change a flag here, change that too (nothing enforces it).
import { RunnerContext, CanceledError, PausedError, classifyStop } from './jobs';
import { withRetry } from './net-retry';

/** The canonical aria2 flag set (minus -d, which the caller supplies per dest). */
export function aria2Args(destDir: string): string[] {
    return [
        '-d', destDir,
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
        // aria2 absorbs in-flight blips itself (a 0-wait retry hammers a struggling server). A FULL
        // interface loss (exit 19, DNS cannot resolve) is handled by the outer withRetry loop below.
        '--max-tries=5',
        '--retry-wait=5',
        '--timeout=60',
        '--connect-timeout=15',
        // No --lowest-speed-limit, on purpose: it turns a slow mobile link into a hard abort, the
        // opposite of resilient on the intermittent links this exists for.
        '-Z',
        '-j', '5',
    ];
}

// Transient aria2 exit codes the outer loop re-runs (aria2 resumes via --continue): 1 unknown,
// 2 timeout, 6 network, 7 unfinished, 19 DNS, 29 HTTP 503. Terminal (not retried): 3/4 not found,
// 9 no space, 13 file exists.
export const ARIA2_TRANSIENT_EXITS = new Set<number>([1, 2, 6, 7, 19, 29]);

// 5 visible reconnect waits (3, 6, 9, 18, 36 s ~ 72 s total), surfaced on the poll as "Reconnecting
// n/5"; the app renders it and can cancel a wait (which pauses via ctx.signal).
const RETRY_DELAYS = [3_000, 6_000, 9_000, 18_000, 36_000];

/** Convert an aria2 rate token ("34MiB", "512KiB", "1.2MB") to bytes/sec. */
export function parseRate(token: string): number {
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

/**
 * Download urls into destDir with aria2c, reporting percent + speed on the job and surviving a
 * Wi-Fi drop (the outer loop re-runs aria2, which resumes via --continue). Resolves on success.
 * Throws PausedError (partial kept) / CanceledError (caller cleans) / Error (real failure).
 *
 * The caller owns the phase label and any verify/finalize: this does the resilient transfer only.
 */
export async function downloadWithAria2(
    ctx: RunnerContext,
    opts: { destDir: string; urls: string[]; phase?: string },
): Promise<void> {
    const phase = opts.phase ?? 'downloading';
    await withRetry(() => new Promise<void>((resolve, reject) => {
        const dl = ctx.spawn('/usr/bin/aria2c', [...aria2Args(opts.destDir), ...opts.urls]);
        const onData = (buf: Buffer) => {
            const text = buf.toString();
            // A single chunk can carry several summary lines; take the LAST %/rate.
            const re = /\((\d+)%\).*?DL:([^\s]+)/g;
            let m: RegExpExecArray | null;
            let lastPct = -1;
            let lastRate = '';
            while ((m = re.exec(text)) !== null) { lastPct = parseInt(m[1], 10); lastRate = m[2]; }
            if (lastPct >= 0) {
                ctx.reportRetry(0, 0);
                ctx.update({ phase, percent: lastPct, speed: parseRate(lastRate) });
            }
        };
        dl.stdout?.on('data', onData);
        dl.stderr?.on('data', onData);
        dl.on('error', reject);
        dl.on('exit', (code, signal) => {
            if (signal === 'SIGKILL' || ctx.isCanceled()) return reject(new CanceledError());
            if (code === 0) return resolve();
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
        onRetry: ({ attempt, err }: { attempt: number; err: unknown }) => {
            ctx.reportRetry(attempt, RETRY_DELAYS.length);
            ctx.log(`[aria2] reconnect ${attempt}/${RETRY_DELAYS.length} after: ${err instanceof Error ? err.message : String(err)}`);
        },
    });
}

/** Map a stop (pause vs cancel) to the error a runner should throw; returns null for a real error. */
export function stopError(ctx: RunnerContext): PausedError | CanceledError | null {
    const stop = classifyStop(ctx);
    if (stop === 'paused') return new PausedError();
    if (stop === 'canceled') return new CanceledError();
    return null;
}
