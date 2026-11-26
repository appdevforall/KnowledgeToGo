/// <reference types="node" />
// The download sources of the books runner (no network: fetch is a fake).
import test from 'node:test';
import assert from 'node:assert/strict';
import { bookSources, sourceOrder, fetchFirst, FetchFirstOpts } from './books.sources';

const MAIN = 'https://www.gutenberg.org/ebooks/11.epub3.images';
const MIRROR = 'https://gutenberg.pglaf.org/cache/epub/11/pg11-images-3.epub';

test('a numeric id gets the mirror file first, then the catalog URL', () => {
    assert.deepEqual(bookSources('11', MAIN), [MIRROR, MAIN]);
});

test('an id that is not a number gets only the catalog URL', () => {
    assert.deepEqual(bookSources('11/../x', 'https://example.org/a.epub'), ['https://example.org/a.epub']);
    assert.deepEqual(bookSources('', 'https://example.org/a.epub'), ['https://example.org/a.epub']);
});

test('a catalog URL in another format, or of another book, gets no mirror', () => {
    assert.deepEqual(bookSources('11', 'https://www.gutenberg.org/ebooks/11.epub.noimages'), ['https://www.gutenberg.org/ebooks/11.epub.noimages']);
    assert.deepEqual(bookSources('11', 'https://www.gutenberg.org/ebooks/12.epub3.images'), ['https://www.gutenberg.org/ebooks/12.epub3.images']);
    assert.deepEqual(bookSources('11', MIRROR), [MIRROR]);
});

test('the order starts at the host that worked last', () => {
    assert.deepEqual(sourceOrder([MAIN, MIRROR], null), [MAIN, MIRROR]);
    assert.deepEqual(sourceOrder([MAIN, MIRROR], 'gutenberg.pglaf.org'), [MIRROR, MAIN]);
    assert.deepEqual(sourceOrder([MAIN, MIRROR], 'www.gutenberg.org'), [MAIN, MIRROR]);
    // A book with only the catalog URL does not lose the preference of the job.
    assert.deepEqual(sourceOrder(['https://example.org/a.epub'], 'gutenberg.pglaf.org'), ['https://example.org/a.epub']);
});

type Reply = { status: number; chunks?: string[]; hang?: boolean };

/** A fake fetch: each URL gives its reply; `hang` sends nothing after the headers. */
function fakeFetch(replies: Record<string, Reply>, calls: string[]): typeof fetch {
    return (async (input: unknown, init?: RequestInit) => {
        const url = String(input);
        calls.push(url);
        const r = replies[url];
        const signal = init?.signal ?? undefined;
        const body = new ReadableStream<Uint8Array>({
            start(c) {
                for (const s of r.chunks ?? []) c.enqueue(new TextEncoder().encode(s));
                if (!r.hang) c.close();
                signal?.addEventListener('abort', () => c.error(signal.reason), { once: true });
            },
        });
        return new Response(body, { status: r.status });
    }) as typeof fetch;
}

function opts(f: typeof fetch, extra: Partial<FetchFirstOpts> = {}): FetchFirstOpts {
    return { fetch: f, headers: {}, signal: new AbortController().signal, stallMs: 50, preferredHost: null, log: () => undefined, ...extra };
}

test('a 503 from the first source moves on to the next one at once', async () => {
    const calls: string[] = [];
    const f = fakeFetch({ [MAIN]: { status: 503 }, [MIRROR]: { status: 200, chunks: ['PK', 'data'] } }, calls);
    const r = await fetchFirst([MAIN, MIRROR], opts(f));
    assert.equal(r.host, 'gutenberg.pglaf.org');
    assert.equal(Buffer.from(r.body).toString(), 'PKdata');
    assert.deepEqual(calls, [MAIN, MIRROR]);
});

test('a failed mirror moves on to the catalog URL at once', async () => {
    const calls: string[] = [];
    const f = fakeFetch({ [MIRROR]: { status: 503 }, [MAIN]: { status: 200, chunks: ['ok'] } }, calls);
    const r = await fetchFirst(bookSources('11', MAIN), opts(f));
    assert.equal(r.host, 'www.gutenberg.org');
    assert.deepEqual(calls, [MIRROR, MAIN]);
});

test('the preferred host is tried first', async () => {
    const calls: string[] = [];
    const f = fakeFetch({ [MAIN]: { status: 503 }, [MIRROR]: { status: 200, chunks: ['ok'] } }, calls);
    await fetchFirst([MAIN, MIRROR], opts(f, { preferredHost: 'gutenberg.pglaf.org' }));
    assert.deepEqual(calls, [MIRROR]);
});

test('a slow source that keeps sending data finishes; a stalled one moves on', async () => {
    const calls: string[] = [];
    const f = fakeFetch({ [MAIN]: { status: 200, chunks: ['x'], hang: true }, [MIRROR]: { status: 200, chunks: ['ok'] } }, calls);
    const r = await fetchFirst([MAIN, MIRROR], opts(f));
    assert.equal(r.host, 'gutenberg.pglaf.org');
    assert.deepEqual(calls, [MAIN, MIRROR]);
});

test('when all sources fail, the retryable error wins over a final 4xx', async () => {
    const f = fakeFetch({ [MAIN]: { status: 503 }, [MIRROR]: { status: 404 } }, []);
    await assert.rejects(fetchFirst([MAIN, MIRROR], opts(f)), (e: unknown) => (e as { status?: number }).status === 503);
    const g = fakeFetch({ [MAIN]: { status: 404 }, [MIRROR]: { status: 404 } }, []);
    await assert.rejects(fetchFirst([MAIN, MIRROR], opts(g)), (e: unknown) => (e as { status?: number }).status === 404);
});

test('a cancel of the job stops at once and does not try the next source', async () => {
    const calls: string[] = [];
    const ac = new AbortController();
    const f = fakeFetch({ [MAIN]: { status: 200, chunks: ['x'], hang: true }, [MIRROR]: { status: 200, chunks: ['ok'] } }, calls);
    const p = fetchFirst([MAIN, MIRROR], opts(f, { signal: ac.signal, stallMs: 5_000 }));
    setTimeout(() => ac.abort(), 20);
    await assert.rejects(p);
    assert.deepEqual(calls, [MAIN]);
});
