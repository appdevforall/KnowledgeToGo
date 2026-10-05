// K2GO-451: the pure decision for what a reader of the dash-node rebuild status should report, and
// whether it must heal a stale "running". Kept free of fs / process so it is unit-tested off-box; the
// thin wrapper in routes.ts feeds it the status file's value, the recorded pid's liveness, and how long
// "running" has been set. A "running" status is only trustworthy while a live rebuild backs it:
//   - pid "dead"  : the script was killed (box restart / power loss) -> heal to "error".
//   - pid "none" past the grace : the script died before (or without) recording its pid -> heal to "error".
//   - pid "none" within the grace : a just started run that has not written its pid yet -> leave "running".
//   - pid "alive" : a real rebuild (or a recycled pid) -> leave "running".
// Any non-"running" file value passes through untouched. Healing targets "error" (a crash is a failure,
// and the app's poller treats "error" as terminal; "idle" would read as still running and loop).

export type PidState = 'none' | 'dead' | 'alive';

export interface RebuildResolution {
    /** The effective status a reader should report. */
    state: string;
    /** True when the caller must clear the stale run's state files (status -> "error", drop lock/phase/pid). */
    heal: boolean;
}

export function resolveRebuildState(
    fileState: string,
    pidState: PidState,
    runningAgeMs: number,
    graceMs: number,
): RebuildResolution {
    if (fileState !== 'running') {
        return { state: fileState, heal: false };
    }
    if (pidState === 'dead') {
        return { state: 'error', heal: true };
    }
    if (pidState === 'none' && runningAgeMs > graceMs) {
        return { state: 'error', heal: true };
    }
    return { state: 'running', heal: false };
}
