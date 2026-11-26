// sockets/books.sources.ts
//
// Where the books runner downloads one Gutenberg EPUB from. The mirror comes first: Project
// Gutenberg asks automated clients to use a mirror, not the main site, and the main site was down
// for days in 2026-11 (503 "down for maintenance"). The mirror keeps the generated EPUB files under
// the same /cache/epub/<id>/ path; the catalog URL on www.gutenberg.org is the fallback. No network
// of its own (fetch is passed in), so it is unit-tested without a network.

/** Gutenberg mirrors that serve /cache/epub/<id>/pg<id>-images-3.epub (checked 2026-11-25). */
export const GUTENBERG_MIRRORS = ['https://gutenberg.pglaf.org'];

/** The download sources for one book: each mirror first, then the catalog URL. Every catalog row
 *  is an "EPUB3 with images" URL (`/ebooks/<id>.epub3.images`); the mirror name of that file is
 *  `pg<id>-images-3.epub`. A mirror URL needs a numeric id and an "EPUB3 with images" catalog
 *  URL: else only the catalog URL is used, so the mirror never changes the format. */
export function bookSources(id: string, url: string): string[] {
    if (!/^\d+$/.test(id) || !url.endsWith(`/ebooks/${id}.epub3.images`)) return [url];
    const mirrors = GUTENBERG_MIRRORS.map((m) => `${m}/cache/epub/${id}/pg${id}-images-3.epub`);
    return [...mirrors, url];
}

export function hostOf(url: string): string {
    try { return new URL(url).host; } catch { return url; }
}

/** The order in which to try the sources: the source on `preferredHost` (the host that worked
 *  last) first, then the others in list order. So after one failure of a source, the next books
 *  of the job go to the source that worked and do not wait for the failure again. */
export function sourceOrder(sources: string[], preferredHost: string | null): string[] {
    const first = sources.findIndex((s) => hostOf(s) === preferredHost);
    return first <= 0 ? sources.slice() : [sources[first], ...sources.filter((_, i) => i !== first)];
}

/** Retry network drops, timeouts and 5xx; a 4xx is the server's final word. */
export function isTransient(err: unknown): boolean {
    const status = (err as { status?: number })?.status;
    if (typeof status === 'number') return status >= 500;
    return true;
}

export interface FetchFirstOpts {
    fetch: typeof fetch;
    headers: Record<string, string>;
    /** Cancel of the job: terminal, never moves on to the next source. */
    signal: AbortSignal;
    /** Abort a source when no data comes for this long (a stall, not a cap on the whole file:
     *  the mirror can be slow, and a large EPUB on a slow link must still finish). */
    stallMs: number;
    preferredHost: string | null;
    log: (line: string) => void;
}

/** Download one file: try each source one time, in sourceOrder. A failure (HTTP error, network
 *  error, stall) moves on to the next source at once. When all sources fail, throw the error that
 *  is worth a retry (network, stall, 5xx) before a final 4xx. */
export async function fetchFirst(sources: string[], o: FetchFirstOpts): Promise<{ body: Uint8Array<ArrayBuffer>; host: string }> {
    let failure: unknown = null;
    for (const url of sourceOrder(sources, o.preferredHost)) {
        const host = hostOf(url);
        const stall = new AbortController();
        const onStall = () => stall.abort(new Error(`no data from ${host} for ${Math.round(o.stallMs / 1000)} s`));
        let timer = setTimeout(onStall, o.stallMs);
        const signal = AbortSignal.any([o.signal, stall.signal]);
        try {
            const response = await o.fetch(url, { headers: o.headers, signal });
            if (!response.ok || !response.body) {
                await response.body?.cancel().catch(() => undefined);   // free the connection
                const e = new Error(`HTTP ${response.status} from ${host}`);
                (e as { status?: number }).status = response.status;
                throw e;
            }
            const chunks: Uint8Array[] = [];
            const reader = response.body.getReader();
            for (;;) {
                const { done, value } = await reader.read();
                if (done) break;
                chunks.push(value);
                clearTimeout(timer);
                timer = setTimeout(onStall, o.stallMs);
            }
            const body = new Uint8Array(chunks.reduce((n, c) => n + c.length, 0));
            let at = 0;
            for (const c of chunks) { body.set(c, at); at += c.length; }
            return { body, host };
        } catch (err) {
            if (o.signal.aborted) throw err;
            const e = signal.aborted && signal.reason instanceof Error ? signal.reason : err;
            o.log(`[books] ${host}: ${e instanceof Error ? e.message : String(e)}`);
            if (failure === null || !isTransient(failure)) failure = e;
        } finally {
            clearTimeout(timer);
        }
    }
    throw failure;
}
