// sockets/log-rotate.ts — K2GO-386 / ADR-386 (Layer 2: contain logs)
//
// proot has no systemd/cron, so nothing runs logrotate on its own. dash-node — the box's always-up
// process — triggers `logrotate` on a fixed timer so the K2Go-owned rotation config actually rotates.
// The config itself is INSTALLED at deploy time (tools/setup-proot-logging.sh, run by the rootfs
// install and by rebuild/dev-push), NOT here and NOT at boot — re-configuring on every dash-node start
// would be needless churn (ADR-386 §5). There is NO rotation at boot either (setInterval fires first
// at +interval): boot is the heaviest, most fragile moment under proot and we keep it clear.
// Best-effort — a failure logs and the next tick retries; nothing throws into the caller.
import { execFile } from 'child_process';

// Rotate every 10 min (ADR-386 §4). Wide enough to be nearly free; narrow enough to bound growth.
const INTERVAL_MS = 10 * 60 * 1000;

let timer: NodeJS.Timeout | null = null;

// logrotate lives in /usr/sbin, which dash-node's runtime PATH (set by its pdsm wrapper) may not
// include — call it by absolute path so a reduced PATH can't turn every tick into an ENOENT.
const LOGROTATE_BIN = '/usr/sbin/logrotate';

// Run one logrotate pass over the whole config (the K2Go blocks use copytruncate + size, so this is
// cheap unless a file actually exceeds its cap). Best-effort.
function runLogrotateOnce(): void {
    execFile(LOGROTATE_BIN, ['/etc/logrotate.conf'], { timeout: 60_000 }, (err, _stdout, stderr) => {
        if (err) {
            console.error('[log-rotate] logrotate run failed:', err.message, (stderr || '').trim());
        }
    });
}

/**
 * Start the periodic log-rotation trigger. Idempotent; call once from server.ts at listen time.
 * Rotates every INTERVAL_MS — the FIRST rotation is at +INTERVAL_MS, never at boot (ADR-386 §4).
 * The rotation config is installed separately, at deploy time (ADR-386 §5).
 */
export function startLogRotation(): void {
    if (timer) return;
    timer = setInterval(runLogrotateOnce, INTERVAL_MS);
    if (typeof timer.unref === 'function') timer.unref(); // don't keep the process alive just for this
    console.log(`[log-rotate] scheduled: logrotate every ${INTERVAL_MS / 60000} min (no rotation at boot)`);
}

export function stopLogRotation(): void {
    if (timer) {
        clearInterval(timer);
        timer = null;
    }
}
