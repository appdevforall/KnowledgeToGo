// sockets/rolling-log.ts — ADFA-4879
//
// A small, pure, bounded rolling log tail per key (job id), so a client can show a live log over
// REST by polling with an absolute line cursor. Pure (no DB, no I/O) so it is unit-testable on its
// own; JobManager owns one instance. Bounded two ways: at most `maxLines` per key (oldest lines
// roll off, counted in `dropped`) and at most `maxJobs` keys (the oldest key is evicted).
export interface LogSlice {
    from: number;        // absolute index of the first returned line
    next: number;        // absolute index to feed back as `since` next time
    lines: string[];
    truncated: boolean;  // the caller's `since` fell outside what we still hold (reset the cursor)
}

export class RollingLog {
    private readonly map = new Map<string, { lines: string[]; dropped: number }>();

    constructor(private readonly maxLines = 500, private readonly maxJobs = 50) {}

    /** Append a raw chunk. Splits on CR and/or LF (pmtiles draws progress with bare `\r`), trims
     *  trailing whitespace/carriage returns, and drops empty lines. */
    append(id: string, chunk: string): void {
        if (!chunk) return;
        let buf = this.map.get(id);
        if (!buf) {
            if (this.map.size >= this.maxJobs) {
                const oldest = this.map.keys().next().value;   // Map keeps insertion order
                if (oldest !== undefined) this.map.delete(oldest);
            }
            buf = { lines: [], dropped: 0 };
            this.map.set(id, buf);
        }
        for (const raw of chunk.split(/\r\n|\r|\n/)) {
            const line = raw.replace(/\r/g, '').replace(/\s+$/, '');
            if (line.length) buf.lines.push(line);
        }
        if (buf.lines.length > this.maxLines) {
            const excess = buf.lines.length - this.maxLines;
            buf.lines.splice(0, excess);
            buf.dropped += excess;
        }
    }

    /** Return the tail from an absolute cursor. Feed `next` back as `since` to append incrementally. */
    getSince(id: string, since: number): LogSlice {
        const buf = this.map.get(id);
        if (!buf) return { from: 0, next: 0, lines: [], truncated: false };
        const total = buf.dropped + buf.lines.length;
        const s = Number.isFinite(since) && since > 0 ? Math.floor(since) : 0;
        const startAbs = Math.max(s, buf.dropped);   // can't serve lines already rolled off
        return {
            from: startAbs,
            next: total,
            lines: buf.lines.slice(startAbs - buf.dropped),
            truncated: s < buf.dropped || s > total,
        };
    }
}
