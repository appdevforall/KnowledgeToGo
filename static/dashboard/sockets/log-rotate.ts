// sockets/log-rotate.ts — K2GO-386 / ADR-386 (Layer 2: contain logs)
//
// proot has no systemd/cron, so nothing runs logrotate on its own. dash-node — the box's always-up
// process — drives log containment on a fixed timer. Each tick, IN ORDER:
//   1. firehose GUARD: truncate any pathologically huge .log IN PLACE, before logrotate can try to
//      copy it. logrotate's copytruncate would COPY a 6 GB runaway log (doubling disk, pegging CPU on
//      a weak phone) and still not stop the writer — so we reclaim it in seco first (no copy, no gzip).
//      A firehose log is garbage (repeated error spam); we discard it. This is the in-box half of L3
//      (ADR-386 §6): dash-node can DETECT + RECLAIM, but it CANNOT stop an off-proot orphan (device-
//      proven — an in-box kill does not reach it); STOPPING that is app-side. A recurring firehose is
//      flagged for that app-side reap.
//   2. logrotate: rotate the remaining MODERATE logs (copytruncate + size-based, cheap now).
// Coupling the guard to the SAME timer as logrotate gives a deterministic order (guard→rotate) with
// ONE clock — so L2 never meets a firehose, and there is no second timer to drift out of sync.
//
// The config itself is INSTALLED at deploy time (tools/setup-proot-logging.sh), not here and not at
// boot. There is NO work at boot either (setInterval fires first at +interval): boot is the heaviest,
// most fragile moment under proot and we keep it clear. Everything here is best-effort.
import { execFile } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

// Rotate every 10 min (ADR-386 §4). Wide enough to be nearly free; narrow enough to bound growth.
const INTERVAL_MS = 10 * 60 * 1000;

// logrotate lives in /usr/sbin, which dash-node's runtime PATH (set by its pdsm wrapper) may not
// include — call it by absolute path so a reduced PATH can't turn every tick into an ENOENT.
const LOGROTATE_BIN = '/usr/sbin/logrotate';

// A .log past this size is a firehose (a runaway ~GB/min), not a normal log (which are KB–MB). logrotate
// would copy it; we truncate it in place instead. Parametric — tune per device headroom / interval.
const FIREHOSE_BYTES = 1024 * 1024 * 1024; // 1 GiB
// The dirs logrotate's k2go config covers; the guard scans the same surface for *.log files.
const LOG_DIRS = ['/var/log', '/var/log/nginx'];

// Consecutive-truncation count per path, so a RECURRING firehose (an orphan the box cannot stop, that
// just refills) is flagged for the app-side reap (ADR-386 §6). In-memory; cleared when the log is sane.
const firehoseStreak = new Map<string, number>();

let timer: NodeJS.Timeout | null = null;

/** Pre-logrotate guard: truncate any firehose-sized .log IN PLACE so logrotate never has to copy it.
 *  Reclaims instantly (no copy, no compress). Best-effort per file. */
function guardFirehoseLogs(): void {
    for (const dir of LOG_DIRS) {
        let entries: string[];
        try { entries = fs.readdirSync(dir); } catch { continue; }
        for (const name of entries) {
            if (!name.endsWith('.log')) continue;
            const p = path.join(dir, name);
            try {
                const size = fs.statSync(p).size;
                if (size <= FIREHOSE_BYTES) { firehoseStreak.delete(p); continue; }
                fs.truncateSync(p, 0); // reclaim in place — no copy, no compress
                const n = (firehoseStreak.get(p) || 0) + 1;
                firehoseStreak.set(p, n);
                console.warn(
                    `[log-rotate] FIREHOSE: truncated ${p} (${size} B) in place, occurrence #${n}` +
                    (n >= 2 ? ' — recurring; likely an off-proot orphan, needs app-side reap (ADR-386 L3)' : ''),
                );
            } catch { /* best-effort per file */ }
        }
    }
}

/** Run one logrotate pass over the whole config (the K2Go blocks use copytruncate + size, so this is
 *  cheap unless a file actually exceeds its cap). Best-effort. */
function runLogrotateOnce(): void {
    execFile(LOGROTATE_BIN, ['/etc/logrotate.conf'], { timeout: 60_000 }, (err, _stdout, stderr) => {
        if (err) {
            console.error('[log-rotate] logrotate run failed:', err.message, (stderr || '').trim());
        }
    });
}

/** One tick: the firehose guard first (so L2 never meets a firehose), then logrotate the moderate logs. */
function tick(): void {
    try { guardFirehoseLogs(); } catch (e) { console.error('[log-rotate] firehose guard failed', e); }
    runLogrotateOnce();
}

/**
 * Start the periodic log-containment trigger. Idempotent; call once from server.ts at listen time.
 * Runs guard+logrotate every INTERVAL_MS — the FIRST run is at +INTERVAL_MS, never at boot (ADR-386 §4).
 */
export function startLogRotation(): void {
    if (timer) return;
    timer = setInterval(tick, INTERVAL_MS);
    if (typeof timer.unref === 'function') timer.unref(); // don't keep the process alive just for this
    console.log(
        `[log-rotate] scheduled: firehose guard + logrotate every ${INTERVAL_MS / 60000} min (none at boot)`,
    );
}

export function stopLogRotation(): void {
    if (timer) {
        clearInterval(timer);
        timer = null;
    }
}
