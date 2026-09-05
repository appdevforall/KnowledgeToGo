// sockets/log-rotate.ts — K2GO-386 / ADR-386 (Layer 2: contain logs)
//
// proot has no systemd/cron, so nothing runs logrotate on its own. dash-node — the box's always-up
// process — installs the K2Go-owned rotation config once (light: it moves snippets + writes one file
// + validates; it does NOT rotate) and then triggers `logrotate` on a fixed timer so the config
// actually rotates. There is NO rotation at boot (setInterval fires first at +interval): boot is the
// heaviest, most fragile moment under proot and we keep it clear. Everything here is best-effort — a
// failure logs and the next tick retries; nothing throws into the caller.
import { execFile } from 'child_process';
import * as path from 'path';

// Rotate every 10 min (ADR-386 §5). Wide enough to be nearly free; narrow enough to bound growth.
const INTERVAL_MS = 10 * 60 * 1000;

// The configurator ships in the repo under tools/; on an installed box it lives in the clone that
// rebuild-dashboard.sh / dev-push-dashboard.sh keep current (default /opt/iiab-android).
const CLONE_DIR = process.env.K2GO_CLONE_DIR || '/opt/iiab-android';
const CONFIGURATOR = path.join(CLONE_DIR, 'tools', 'setup-proot-logging.sh');

let timer: NodeJS.Timeout | null = null;

// Install the rotation config once (idempotent, no rotation). Best-effort.
function installConfigOnce(): void {
    execFile('sh', [CONFIGURATOR], { timeout: 30_000 }, (err, stdout, stderr) => {
        if (err) {
            console.error('[log-rotate] config install failed:', err.message, (stderr || '').trim());
        } else if ((stdout || '').trim()) {
            console.log('[log-rotate] config:', stdout.trim());
        }
    });
}

// Run one logrotate pass over the whole config (the K2Go blocks use copytruncate + size, so this is
// cheap unless a file actually exceeds its cap). Best-effort.
function runLogrotateOnce(): void {
    execFile('logrotate', ['/etc/logrotate.conf'], { timeout: 60_000 }, (err, _stdout, stderr) => {
        if (err) {
            console.error('[log-rotate] logrotate run failed:', err.message, (stderr || '').trim());
        }
    });
}

/**
 * Start the periodic log-rotation trigger. Idempotent; call once from server.ts at listen time.
 * Installs the config immediately (light), then rotates every INTERVAL_MS — the FIRST rotation is at
 * +INTERVAL_MS, never at boot (ADR-386 §5).
 */
export function startLogRotation(): void {
    if (timer) return;
    installConfigOnce();
    timer = setInterval(runLogrotateOnce, INTERVAL_MS);
    if (typeof timer.unref === 'function') timer.unref(); // don't keep the process alive just for this
    console.log(
        `[log-rotate] scheduled: logrotate every ${INTERVAL_MS / 60000} min (config installed; no rotation at boot)`,
    );
}

export function stopLogRotation(): void {
    if (timer) {
        clearInterval(timer);
        timer = null;
    }
}
