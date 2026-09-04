// sockets/service-heal.ts — ADFA-5343 (ADR-5343a §10)
//
// "The box heals itself": a small in-proot watcher that probes the content services on
// loopback and, on a wedged/down one, issues an in-proot `pdsm restart <svc>` — the same
// actuator the app's manual Retry calls (routes.ts -> services.restartService).
//
// Why server-side, on the box: the recovery endpoint is loopback-only (dash-node-nginx.conf
// `allow 127.0.0.1; deny all`). The client captive page can *detect* a down tile but cannot
// *reach* the restart, so healing cannot live there — the tile only reflects status while the
// box heals itself (§10). The app/reconciler owns box up/down and does NOT manage individual
// box services (§10 layering); this watcher is the dashboard REST core owning its own service
// tree — the single owner of "is my content service tree healthy?".
import { restartService } from './services';

/** A watched content service: its pdsm name and the loopback URL that reflects its health.
 *  kiwix first (the device-confirmed wedge, §10); the others are added here as they are
 *  device-verified — the endpoint already accepts the full supported set. */
interface Watch { svc: string; probeUrl: string; }

// The box fronts content on nginx :8085 (dash-node-nginx.conf); a HEAD on the public path is
// exactly the served page's own probe, run here on loopback. Origin/timing are env-overridable
// so a box on a non-default port needs no code change (mirrors PORT / K2GO_* elsewhere).
const PUBLIC_ORIGIN = process.env.K2GO_PUBLIC_ORIGIN || 'http://127.0.0.1:8085';
const INTERVAL_MS = Number(process.env.K2GO_HEAL_INTERVAL_MS) || 30_000;
const COOLDOWN_MS = Number(process.env.K2GO_HEAL_COOLDOWN_MS) || 60_000;
const PROBE_TIMEOUT_MS = Number(process.env.K2GO_HEAL_PROBE_TIMEOUT_MS) || 4_000;

// php-fpm is probed through its own fastcgi health endpoint (dash-node-nginx.conf ->
// /library/dashboard/health.php), not a static path — a static HEAD would 200 even with the
// pool down. This is the disk-fill fix (§10 "Live residual"): an orphaned php-fpm busy-loops
// logging epoll_wait ENOSYS (~1.3 GB/min); a `pdsm restart php-fpm` reclaims it and a fresh
// pool under the new proot does not busy-loop. NOTE: unlike /kiwix/ (absent -> 404 -> left
// alone), a php-fpm-less box would answer this location with 502, not 404, so this assumes
// php-fpm is a core service on the box; if that ever stops holding, gate the heal on a
// pdsm-known-service check before restart.
const WATCHED: Watch[] = [
    { svc: 'kiwix', probeUrl: `${PUBLIC_ORIGIN}/kiwix/` },
    { svc: 'php-fpm', probeUrl: `${PUBLIC_ORIGIN}/k2go-php-health` },
];

/** Enough time has passed since the last restart attempt to try again. A per-service
 *  cooldown is the only state the loop keeps: it bounds a wedged service to one restart per
 *  window (never a restart storm) and gives the freshly-restarted service time to come back
 *  before it is judged again. Pure, so the timing is unit-tested off device. */
export function dueForRestart(lastAttemptMs: number, nowMs: number, cooldownMs: number): boolean {
    return nowMs - lastAttemptMs >= cooldownMs;
}

export type ProbeResult = 'ok' | 'down' | 'absent';

/** Classify a probe's HTTP status (or null for a network error / timeout) into a heal decision.
 *  Pure, so the not-installed-vs-wedged split is unit-tested off device — the same split the
 *  served page's discovery makes (404 -> the card is not installed, dropped from monitoring):
 *    - 2xx             -> ok     (serving)
 *    - 404             -> absent (the box does not front this service at all; not installed —
 *                                nothing to heal, or we would restart a service that isn't there)
 *    - any other status-> down   (5xx/502/504: installed but the upstream is wedged)
 *    - null (no reply) -> down   (timeout / refused: not serving) */
export function classifyProbe(status: number | null): ProbeResult {
    if (status === null) return 'down';
    if (status >= 200 && status < 300) return 'ok';
    if (status === 404) return 'absent';
    return 'down';
}

/** HEAD the probe URL and classify the outcome. Only a 'down' verdict heals; 'absent' (404,
 *  not installed) and 'ok' are left alone. */
async function probe(url: string): Promise<ProbeResult> {
    try {
        const res = await fetch(url, { method: 'HEAD', signal: AbortSignal.timeout(PROBE_TIMEOUT_MS) });
        return classifyProbe(res.status);
    } catch {
        return classifyProbe(null);
    }
}

/** Start the watcher. Returns the interval handle (unref'd so it never keeps the process
 *  alive on its own). Safe to call once from server.ts after listen(). */
export function startServiceHeal(): NodeJS.Timeout {
    const lastAttempt = new Map<string, number>();

    const tick = async (): Promise<void> => {
        for (const w of WATCHED) {
            if (await probe(w.probeUrl) !== 'down') continue;   // 'ok'/'absent' need no heal
            const now = Date.now();
            if (!dueForRestart(lastAttempt.get(w.svc) ?? 0, now, COOLDOWN_MS)) continue;
            lastAttempt.set(w.svc, now);
            console.log(`[service-heal] ${w.svc} down (${w.probeUrl}); pdsm restart ${w.svc}`);
            restartService(w.svc);
        }
    };

    const handle = setInterval(() => { void tick(); }, INTERVAL_MS);
    handle.unref?.();
    return handle;
}
