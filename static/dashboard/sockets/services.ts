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
    'nginx', 'php-fpm', 'mariadb', 'kolibri', 'kiwix', 'calibre-web',
];

/** A name is restartable only when it is exactly one of the known upstream content
 *  services. Pure and exact-match, so a service name can never carry a shell
 *  metacharacter into the spawn below (defence in depth — spawn already runs no shell). */
export function isRestartableService(name: string): boolean {
    return RESTARTABLE_SERVICES.includes(name);
}

const PDSM = '/usr/local/bin/pdsm';

/** Fire-and-forget `pdsm restart <svc>` in-proot. setsid => own session, mirroring the
 *  dash-node rebuild call (routes.ts): a restart can never kill the run that issued it.
 *  Detached + stdio ignored + unref so dash-node does not wait on it. The caller MUST have
 *  validated `svc` with isRestartableService first. Spawn failures are logged, never thrown:
 *  an unheard 'error' event would otherwise crash the REST core. */
export function restartService(svc: string): void {
    try {
        const child = spawn('setsid', [PDSM, 'restart', svc], { detached: true, stdio: 'ignore' });
        child.on('error', (e) => console.error(`[services] restart ${svc} failed to spawn: ${e}`));
        child.unref();
    } catch (e) {
        console.error(`[services] restart ${svc} spawn threw: ${e}`);
    }
}
