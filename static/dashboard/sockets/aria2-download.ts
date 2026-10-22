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
import { RunnerContext, CanceledError, PausedError, classifyStop, JobPhase } from './jobs';
import { withRetry } from './net-retry';
import http from 'http';
import net from 'net';
import crypto from 'crypto';

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

/** A single aria2 JSON-RPC call over localhost. Lengths and speeds come back as decimal strings. */
function aria2Rpc(port: number, secret: string, method: string, params: unknown[] = []): Promise<unknown> {
    return new Promise((resolve, reject) => {
        const payload = JSON.stringify({ jsonrpc: '2.0', id: 'k2go', method, params: [`token:${secret}`, ...params] });
        const req = http.request(
            { host: '127.0.0.1', port, path: '/jsonrpc', method: 'POST',
              headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) } },
            (res) => {
                let body = '';
                res.on('data', (c) => { body += c; });
                res.on('end', () => {
                    try {
                        const j = JSON.parse(body) as { result?: unknown; error?: { message?: string } };
                        if (j.error) reject(new Error(j.error.message || 'aria2 rpc error'));
                        else resolve(j.result);
                    } catch (e) { reject(e); }
                });
            });
        req.on('error', reject);
        req.setTimeout(3000, () => req.destroy(new Error('aria2 rpc timeout')));
        req.write(payload);
        req.end();
    });
}

/** An ephemeral free localhost port for the aria2 RPC listener. */
function freePort(): Promise<number> {
    return new Promise((resolve, reject) => {
        const srv = net.createServer();
        srv.once('error', reject);
        srv.listen(0, '127.0.0.1', () => {
            const addr = srv.address();
            const port = typeof addr === 'object' && addr ? addr.port : 0;
            srv.close(() => (port ? resolve(port) : reject(new Error('no free port'))));
        });
    });
}

interface Aria2Status { completedLength?: string; totalLength?: string; }

/**
 * Download urls into destDir with aria2c, reporting OVERALL percent + aggregate speed + total ETA on
 * the job and surviving a Wi-Fi drop (the outer loop re-runs aria2, which resumes via --continue).
 * Resolves on success. Throws PausedError (partial kept) / CanceledError (caller cleans) / Error.
 *
 * K2GO-503: progress is aggregated across ALL files through aria2's RPC (piece-accurate
 * completedLength), so a multi-file download shows one bar that climbs 0 -> 100 instead of the last
 * file's percent. The denominator is the caller's totalBytesHint (accurate from the first tick,
 * e.g. the add-ons catalog) raised by what aria2 has learned, and the bar never steps backward. If
 * the RPC is unreachable (an aria2 built without it) the console readout is the fallback. The caller
 * owns the phase label and any verify/finalize: this does the transfer only.
 */
export async function downloadWithAria2(
    ctx: RunnerContext,
    opts: { destDir: string; urls?: string[]; inputFile?: string; phase?: JobPhase; totalBytesHint?: number },
): Promise<void> {
    const phase = opts.phase ?? 'downloading';
    // -i inputFile carries a per-URL `out=` so a serve-relative subdir is preserved (code_assets);
    // urls is the flat form (kiwix/maps, one dir). The out= paths are relative to -d destDir.
    const transferArgs = opts.inputFile ? ['-i', opts.inputFile] : (opts.urls ?? []);

    const hint = typeof opts.totalBytesHint === 'number' && opts.totalBytesHint > 0 ? opts.totalBytesHint : 0;
    const rpcSecret = crypto.randomBytes(9).toString('hex');
    let rpcPort = 0, rpcOk = false, lastPct = -1, polling = false;
    let sawDownloads = false, drainedTicks = 0, shuttingDown = false;
    let sawSpeed = false, stalledTicks = 0;
    const rpcArgs: string[] = [];
    try {
        rpcPort = await freePort();
        rpcArgs.push('--enable-rpc=true', '--rpc-listen-all=false', `--rpc-listen-port=${rpcPort}`, `--rpc-secret=${rpcSecret}`);
    } catch { rpcPort = 0; }

    const pollOnce = async (): Promise<void> => {
        if (polling || rpcPort === 0) return;
        polling = true;
        try {
            const [gs, active, waiting, stopped] = await Promise.all([
                aria2Rpc(rpcPort, rpcSecret, 'aria2.getGlobalStat'),
                aria2Rpc(rpcPort, rpcSecret, 'aria2.tellActive', [['completedLength', 'totalLength']]),
                aria2Rpc(rpcPort, rpcSecret, 'aria2.tellWaiting', [0, 5000, ['completedLength', 'totalLength']]),
                aria2Rpc(rpcPort, rpcSecret, 'aria2.tellStopped', [0, 5000, ['completedLength', 'totalLength']]),
            ]);
            rpcOk = true;
            const g = gs as { downloadSpeed?: string; numActive?: string; numWaiting?: string };
            // aria2 with --enable-rpc does NOT exit on its own when the command-line queue drains; it
            // waits for RPC. Shut it down once the queue is empty (two ticks, so a transient zero mid
            // batch never stops a live download) so the process exits and this step resolves; a file
            // still missing then fails in the caller's finalize, exactly as a plain aria2 exit would.
            const na = Number(g.numActive || 0), nw = Number(g.numWaiting || 0);
            if (na + nw > 0) sawDownloads = true;
            if (sawDownloads && na === 0 && nw === 0) {
                if (++drainedTicks >= 2 && !shuttingDown) {
                    shuttingDown = true;
                    aria2Rpc(rpcPort, rpcSecret, 'aria2.shutdown').catch(() => { /* exiting */ });
                }
            } else {
                drainedTicks = 0;
            }
            const rows = [...(active as Aria2Status[]), ...(waiting as Aria2Status[]), ...(stopped as Aria2Status[])];
            let completed = 0, seenTotal = 0;
            for (const r of rows) {
                completed += Number(r.completedLength || 0);
                seenTotal += Number(r.totalLength || 0);
            }
            const total = Math.max(hint, seenTotal);
            const speed = Number(g.downloadSpeed || 0);
            // Keep the reconnect indicator the withRetry loop used to show: aria2 now rides a blip out
            // internally (it does not exit), so surface "reconnecting" when the transfer is active but
            // stalled (no bytes) for a few seconds, and clear it the moment bytes flow again.
            if (speed > 0) { sawSpeed = true; stalledTicks = 0; }
            else if (sawSpeed && na > 0 && !shuttingDown) { stalledTicks++; }
            const reconnecting = stalledTicks >= 3;
            ctx.reportRetry(reconnecting ? 1 : 0, reconnecting ? 1 : 0);
            if (total > 0) {
                let pct = Math.min(100, Math.round((completed / total) * 100));
                if (pct >= 100 && na + nw > 0) pct = 99;   // never 100% while files remain (no-hint early total)
                if (pct < lastPct) pct = lastPct;   // monotonic: a late file's size must not drop the bar
                lastPct = pct;
                const eta = speed > 0 ? Math.round((total - completed) / speed) : -1;
                ctx.update({ phase, percent: pct, speed, eta });
            } else {
                ctx.update({ phase, speed });
            }
        } catch { /* aria2 not up yet or already gone: try the next tick */ }
        finally { polling = false; }
    };
    const pollTimer = rpcPort !== 0 ? setInterval(() => { void pollOnce(); }, 1000) : null;

    try {
        await withRetry(() => new Promise<void>((resolve, reject) => {
            // Reset the per-run drain/stall flags: withRetry may re-run aria2, and a stale shuttingDown
            // would leave the new instance running for RPC forever (it never exits on its own). lastPct
            // stays (the re-run continues via --continue, so the bar must not drop).
            sawDownloads = false; drainedTicks = 0; shuttingDown = false; sawSpeed = false; stalledTicks = 0;
            const dl = ctx.spawn('/usr/bin/aria2c', [...aria2Args(opts.destDir), ...rpcArgs, ...transferArgs]);
            const onData = (buf: Buffer) => {
                if (rpcOk) return;   // once the RPC answers it drives the bar; the console is the fallback
                const text = buf.toString();
                // A single chunk can carry several summary lines; take the LAST %/rate.
                const re = /\((\d+)%\).*?DL:([^\s]+)/g;
                let m: RegExpExecArray | null;
                let p = -1, rate = '';
                while ((m = re.exec(text)) !== null) { p = parseInt(m[1], 10); rate = m[2]; }
                if (p >= 0) { ctx.reportRetry(0, 0); ctx.update({ phase, percent: p, speed: parseRate(rate) }); }
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
    } finally {
        if (pollTimer) clearInterval(pollTimer);
    }
}

/** Map a stop (pause vs cancel) to the error a runner should throw; returns null for a real error. */
export function stopError(ctx: RunnerContext): PausedError | CanceledError | null {
    const stop = classifyStop(ctx);
    if (stop === 'paused') return new PausedError();
    if (stop === 'canceled') return new CanceledError();
    return null;
}
