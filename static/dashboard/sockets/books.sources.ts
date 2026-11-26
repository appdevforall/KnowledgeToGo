// sockets/books.sources.ts
//
// Where the books runner downloads one Gutenberg EPUB from. The catalog URL points to
// www.gutenberg.org only. When that site fails (it answered 503 "down for maintenance" for days in
// 2026-11), the same file is on the Gutenberg mirror, which keeps the generated EPUB files under
// the same /cache/epub/<id>/ path. Pure, so it is unit-tested without a network.

/** Gutenberg mirrors that serve /cache/epub/<id>/pg<id>-images-3.epub (checked 2026-11-25). */
export const GUTENBERG_MIRRORS = ['https://gutenberg.pglaf.org'];

/** The download sources for one book: the catalog URL first, then each mirror. Every catalog row
 *  is an "EPUB3 with images" URL (`/ebooks/<id>.epub3.images`); the mirror name of that file is
 *  `pg<id>-images-3.epub`. A mirror URL needs a numeric id: else only the catalog URL is used. */
export function bookSources(id: string, url: string): string[] {
    if (!/^\d+$/.test(id)) return [url];
    const mirrors = GUTENBERG_MIRRORS.map((m) => `${m}/cache/epub/${id}/pg${id}-images-3.epub`);
    return [url, ...mirrors.filter((m) => m !== url)];
}

/** The order in which to try n sources: start at `first` (the last source that worked), then the
 *  others in list order. So after one failure of the main site, the next books go to the mirror
 *  first and do not wait for the failure again. */
export function sourceOrder(n: number, first: number): number[] {
    const start = first >= 0 && first < n ? first : 0;
    return Array.from({ length: n }, (_, k) => (start + k) % n);
}
