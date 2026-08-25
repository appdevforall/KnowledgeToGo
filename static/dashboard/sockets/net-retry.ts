// sockets/net-retry.ts — ADFA-4894
//
// Reconnect/backoff for the REST job runners. A network drop mid-job should recover
// on its own rather than fail the whole job at the first blip — the resilient-download
// contract (ADFA-4893) on an intermittent link, which is the case this exists for.
//
// Pure and dependency-free so it is unit-tested on node without a network: the only
// side effect is waiting, and the wait is injectable (`sleep`) and cancellable.

/** Thrown when a retry loop is stopped by cancellation or an aborted signal. */
export class Aborted extends Error {
    constructor(message = 'aborted') { super(message); this.name = 'Aborted'; }
}

export interface RetryOpts {
    /** Total attempts, including the first. Default 4. */
    tries?: number;
    /** First backoff delay in ms; doubles each attempt. Default 500. */
    baseMs?: number;
    /** Upper bound on a single backoff. Default 8000. */
    maxMs?: number;
    /** ADFA-4893: explicit backoff schedule in ms; overrides baseMs/maxMs/jitter. The wait after a
     *  failed attempt k (1-based) is delaysMs[k-1] (clamped to the last entry). With N delays and
     *  tries defaulting to N+1, this yields N visible reconnect waits. */
    delaysMs?: number[];
    /** Fraction of jitter added to each delay [0..1]. Default 0.2. */
    jitter?: number;
    /** Aborts the loop (between and, via fetch, within attempts). */
    signal?: AbortSignal;
    /** Cooperative cancel check, for runners that carry a flag rather than a signal. */
    isCanceled?: () => boolean;
    /** Which errors are worth another attempt. Default: everything except an abort. */
    isTransient?: (err: unknown) => boolean;
    /** Observability hook, fired before each backoff wait. */
    onRetry?: (info: { attempt: number; delayMs: number; err: unknown }) => void;
    /** Injectable wait, so tests don't spend real time. Default a cancellable setTimeout. */
    sleep?: (ms: number, signal?: AbortSignal) => Promise<void>;
}

/** A cancellable delay: rejects with {@link Aborted} if the signal fires first. */
export function delay(ms: number, signal?: AbortSignal): Promise<void> {
    return new Promise<void>((resolve, reject) => {
        if (signal?.aborted) { reject(new Aborted()); return; }
        const t = setTimeout(() => { cleanup(); resolve(); }, ms);
        const onAbort = () => { cleanup(); reject(new Aborted()); };
        const cleanup = () => { clearTimeout(t); signal?.removeEventListener('abort', onAbort); };
        signal?.addEventListener('abort', onAbort, { once: true });
    });
}

function aborted(opts: RetryOpts): boolean {
    return !!opts.signal?.aborted || !!opts.isCanceled?.();
}

/**
 * Run {@code fn} with retry + exponential backoff. Returns its value on the first success;
 * rethrows the last error once the attempts run out or the error is not transient; throws
 * {@link Aborted} the moment cancellation is observed (never sits in a backoff after that).
 *
 * @param fn receives the 1-based attempt number.
 */
export async function withRetry<T>(fn: (attempt: number) => Promise<T>, opts: RetryOpts = {}): Promise<T> {
    const tries = Math.max(1, opts.tries ?? (opts.delaysMs ? opts.delaysMs.length + 1 : 4));
    const baseMs = opts.baseMs ?? 500;
    const maxMs = opts.maxMs ?? 8000;
    const jitter = opts.jitter ?? 0.2;
    const isTransient = opts.isTransient ?? ((e) => !(e instanceof Aborted));
    const sleep = opts.sleep ?? ((ms, sig) => delay(ms, sig));

    let lastErr: unknown;
    for (let attempt = 1; attempt <= tries; attempt++) {
        if (aborted(opts)) throw new Aborted();
        try {
            return await fn(attempt);
        } catch (err) {
            lastErr = err;
            if (err instanceof Aborted || aborted(opts)) throw new Aborted();
            if (attempt >= tries || !isTransient(err)) throw err;
            const delayMs = opts.delaysMs
                ? opts.delaysMs[Math.min(attempt - 1, opts.delaysMs.length - 1)]
                : Math.round(Math.min(maxMs, baseMs * 2 ** (attempt - 1)) * (1 + Math.random() * jitter));
            opts.onRetry?.({ attempt, delayMs, err });
            await sleep(delayMs, opts.signal);   // throws Aborted if the signal fires mid-backoff
        }
    }
    throw lastErr;
}
