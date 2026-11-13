// sockets/services.ts — ADFA-5343 (ADR-5343a §10)
//
// In-proot per-service restart for content-service recovery. A content service that was
// orphaned off proot by an environment relaunch loses proot's syscall emulation
// (epoll_wait -> ENOSYS) and wedges (kiwix-serve hangs, nginx crashes, php-fpm busy-loops).
// The fix is to recover it IN PLACE, inside the one living proot, via `pdsm restart <svc>`
// — never a host-side reap and never a proot relaunch (ADR-5343a §10). Because the service
// is never detached from the proot, the orphan -> ENOSYS class cannot arise.
//
// Supported services — the SOURCE OF TRUTH is upstream; this is only a mirror. It tracks
// `pdsm_installed_services` in iiab/iiab roles/proot_services/defaults/main.yml. That list
// lives upstream and will grow — extend this mirror when it does, rather than inventing
// names here. `dash-node` is deliberately excluded: it is k2go's own service (this repo),
// not a content service, and bouncing it would kill the process serving the request and this
// heal loop — its restart already lives in the rebuild path (routes.ts).
import { spawn } from 'child_process';

export const RESTARTABLE_SERVICES: readonly string[] = [
    'nginx', 'php-fpm', 'mariadb', 'kolibri', 'kiwix', 'calibre-web', 'forgejo',
];

/** A name is restartable only when it is exactly one of the known upstream content
 *  services. Pure and exact-match, so a service name can never carry a shell
 *  metacharacter into the spawn below (defence in depth — spawn already runs no shell). */
export function isRestartableService(name: string): boolean {
    return RESTARTABLE_SERVICES.includes(name);
}

const PDSM = '/usr/local/bin/pdsm';

/** One restart per service at a time. A request while one runs is folded into ONE more run after
 *  it, so two quick requests never interleave their stop/start steps. In memory only: if dash-node
 *  dies, nothing is lost (a config change is already on disk; the service reads it at its next start). */
export function createRestartQueue(run: (svc: string, done: () => void) => void): (svc: string) => void {
    const running = new Set<string>();
    const again = new Set<string>();
    const start = (svc: string): void => {
        running.add(svc);
        run(svc, () => {
            running.delete(svc);
            if (again.delete(svc)) start(svc);
        });
    };
    return (svc: string) => {
        if (running.has(svc)) again.add(svc);
        else start(svc);
    };
}

/** `pdsm restart <svc>` in-proot. setsid => own session, mirroring the dash-node rebuild call
 *  (routes.ts): a restart can never kill the run that issued it. `-w` waits for the restart, so the
 *  queue knows when it ends; a non-zero exit is logged. Spawn failures are logged, never thrown: an
 *  unheard 'error' event would otherwise crash the REST core. */
function runRestart(svc: string, done: () => void): void {
    let finished = false;
    const finish = () => { if (!finished) { finished = true; done(); } };
    try {
        const child = spawn('setsid', ['-w', PDSM, 'restart', svc], { detached: true, stdio: 'ignore' });
        child.on('error', (e) => { console.error(`[services] restart ${svc} failed to spawn: ${e}`); finish(); });
        child.on('exit', (code) => {
            if (code !== 0) console.error(`[services] restart ${svc} exited ${code}`);
            finish();
        });
        child.unref();
    } catch (e) {
        console.error(`[services] restart ${svc} spawn threw: ${e}`);
        finish();
    }
}

/** Fire-and-forget, serialized per service. The caller MUST have validated `svc` with
 *  isRestartableService first. */
export const restartService = createRestartQueue(runRestart);
