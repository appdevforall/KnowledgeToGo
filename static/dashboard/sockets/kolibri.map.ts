// sockets/kolibri.map.ts — pure translation helpers, Kolibri <-> the jobs engine
//
// Split out from kolibri.exec.ts for the same reason maps.socket.ts is split from
// maps.exec.ts: there is no network, no SQLite and no native dependency here, so it
// can be tested with `node --test` without building better-sqlite3 — which is
// exactly what CI does with `npm ci --ignore-scripts`.
//
// Everything in this file encapsulates one specific trap in Kolibri's API.

/** The task that imports metadata + files in a single job.
 *  The old names (startremotechannelimport, startchannelupdate...) belong to a
 *  pre-0.15 API and no longer exist in the backend: they survive only in Kolibri's
 *  frontend tests. */
export const TASK_REMOTE_IMPORT = 'kolibri.core.content.tasks.remoteimport';
export const TASK_DELETE_CHANNEL = 'kolibri.core.content.tasks.deletechannel';

/** Kolibri's terminal states (kolibri/core/tasks/job.py, class State). */
export const TERMINAL_STATES = new Set(['COMPLETED', 'FAILED', 'CANCELED']);

/** States in which the job has not yet been picked up by a worker. */
export const PRE_RUN_STATES = new Set(['PENDING', 'SCHEDULED', 'QUEUED']);

/** Phases of the K2Go jobs engine. The type is duplicated rather than imported
 *  from jobs.ts so this pure module does not drag in better-sqlite3. */
export type Phase =
    | 'queued' | 'downloading' | 'indexing' | 'processing'
    | 'done' | 'error' | 'canceled';

const HEX32 = /^[0-9a-f]{32}$/;

/**
 * Normalises a channel or node identifier to 32 lowercase hex characters.
 *
 * Kolibri accepts UUIDs with or without hyphens and normalises them itself
 * (HexOnlyUUIDField), but validating here keeps rubbish out of the queue and
 * reports the error where it belongs. Returns null for proquint tokens: those are
 * not channel_ids, and passing one through would end in an unexplained 404 from
 * the downloader.
 */
export function normalizeUuid(raw: unknown): string | null {
    if (typeof raw !== 'string') return null;
    const v = raw.trim().replace(/-/g, '').toLowerCase();
    return HEX32.test(v) ? v : null;
}

/** Phases a runner may report while it works. The terminal phases belong to the jobs engine. */
export type ActivePhase = 'queued' | 'downloading' | 'processing';

/**
 * Translates a Kolibri state into a jobs-engine phase while the job runs.
 *
 * Never returns a terminal phase: a COMPLETED task still has to be verified, so the
 * runner decides the outcome and the engine writes it (K2GO-475).
 */
export function mapPhase(kolibriStatus: string): ActivePhase {
    switch (kolibriStatus) {
        case 'PENDING':
        case 'SCHEDULED':
        case 'QUEUED':
        case 'SELECTED':
            return 'queued';
        case 'RUNNING':
            return 'downloading';
        default:
            // Terminal or CANCELING states, and any state a future version adds.
            return 'processing';
    }
}

/**
 * Kolibri reports `percentage` as a 0-1 float; the jobs engine uses a 0-100 int.
 * Conflating the two is the easiest mistake to make against this API.
 * Returns -1 (indeterminate, the jobs.ts convention) when there is no value.
 */
export function mapPercent(percentage: number | null | undefined): number {
    if (typeof percentage !== 'number' || Number.isNaN(percentage)) return -1;
    return Math.max(0, Math.min(100, Math.round(percentage * 100)));
}

/** Overall progress when one K2Go job spans several channels: each channel gets
 *  its own band, so the bar climbs 0->100 once instead of resetting per channel.
 *  Capped at 99 so that the runner's completion is what sets 100. */
export function overallPercent(index: number, total: number, localPercent: number): number {
    if (total <= 1) return localPercent;
    if (localPercent < 0) return -1;
    return Math.min(99, Math.round(((index - 1) * 100 + localPercent) / total));
}

export interface TaskPayloadInput {
    channelId: string;
    channelName: string;
    nodeIds?: string[];
    excludeNodeIds?: string[];
    allThumbnails?: boolean;
}

/**
 * Builds the body of the POST to /api/tasks/tasks/.
 *
 * Encapsulates three traps verified against Kolibri's source:
 *
 *   1. `channel_name` is MANDATORY (serializers.CharField with no required=False).
 *      Omitting it returns 400 even though the field is only used for display
 *      metadata.
 *   2. `peer` does NOT accept null (PrimaryKeyRelatedField with no
 *      allow_null=True): sending `"peer": null` returns 400 "This field may not be
 *      null". The key must be OMITTED.
 *   3. `node_ids: []` means ZERO NODES, not "the whole channel". Absent means the
 *      whole channel. An empty list would download nothing and report success.
 */
export function buildTaskPayload(item: TaskPayloadInput): Record<string, unknown> {
    const payload: Record<string, unknown> = {
        type: TASK_REMOTE_IMPORT,
        channel_id: item.channelId,
        channel_name: item.channelName,
        // Studio has dead files (a missing thumbnail returns 404). With true, one of them
        // aborts the whole import. The runner verifies the result in Kolibri's db instead.
        fail_on_error: false,
        renderable_only: true,
    };
    if (item.nodeIds && item.nodeIds.length > 0) payload.node_ids = item.nodeIds;
    if (item.excludeNodeIds && item.excludeNodeIds.length > 0) {
        payload.exclude_node_ids = item.excludeNodeIds;
    }
    // Thumbnails of every topic in the channel. Only when the caller asks: on a small pick
    // it costs far more than the pick itself.
    if (item.allThumbnails) payload.all_thumbnails = true;
    return payload;
}

/** Resource counts of one import, read from Kolibri's db after the task completes. */
export interface ImportResult {
    /** Resources in the selection. */
    requested: number;
    /** Resources Kolibri marks available. */
    available: number;
    /** Requested node ids that the channel no longer has. */
    notFound: number;
}

/**
 * The outcome of an import, from what is really in Kolibri's db.
 *
 * COMPLETED alone proves nothing: with fail_on_error off, missing files are skipped, and
 * unknown node ids finish with no transfer at all.
 */
export function importOutcome(r: ImportResult): 'complete' | 'partial' | 'failed' {
    if (r.available <= 0) return 'failed';
    return r.available < r.requested || r.notFound > 0 ? 'partial' : 'complete';
}

/** The pieces of a Kolibri task the adoption check reads. */
export interface KolibriTaskRow {
    type?: string;
    status?: string;
    kwargs?: { node_ids?: string[] | null; exclude_node_ids?: string[] | null };
    extra_metadata?: { channel_id?: string };
}

function sameIds(a: string[] | null | undefined, b: string[]): boolean {
    const x = (a ?? []).map(normalizeUuid).filter((v): v is string => v !== null).sort();
    const y = [...b].sort();
    return x.length === y.length && x.every((v, i) => v === y[i]);
}

/**
 * True when a task already in Kolibri is the import this item asks for and is still
 * running. The runner then polls that task instead of queueing a duplicate (after a
 * dashboard restart). A CANCELING task is not adopted: it ends as CANCELED.
 */
export function isSameImport(task: KolibriTaskRow, channelId: string,
                             nodeIds: string[], excludeNodeIds: string[]): boolean {
    if (task.type !== TASK_REMOTE_IMPORT) return false;
    const status = task.status ?? '';
    if (TERMINAL_STATES.has(status) || status === 'CANCELING') return false;
    if (normalizeUuid(task.extra_metadata?.channel_id) !== channelId) return false;
    return sameIds(task.kwargs?.node_ids, nodeIds)
        && sameIds(task.kwargs?.exclude_node_ids, excludeNodeIds);
}

/**
 * The most informative message available for a failed Kolibri task.
 *
 * Kolibri's `exception` field is the class name alone — a job that failed on a
 * channel that does not exist reports `HTTPError`, which tells the reader
 * nothing. The detail is in the last line of `traceback`, in Python's usual
 * `module.ExceptionType: message` form, so that line is preferred when it says
 * more than the class name already did. The module prefix is dropped: an
 * operator does not need `requests.exceptions.` to understand the problem.
 *
 * Falls back to the class name, and then to a plain marker, so the caller always
 * has something to put in front of a person.
 */
export function failureMessage(exception?: string | null,
                               traceback?: string | null): string {
    const cls = (exception || '').trim();

    const lastLine = (traceback || '')
        .split('\n')
        .map((l) => l.trim())
        .filter((l) => l.length > 0)
        .pop() || '';

    // "requests.exceptions.HTTPError: 404 Client Error" -> "HTTPError: 404 Client Error"
    const match = /^([\w.]*?)(\w+(?:Error|Exception|Warning)):\s*(.+)$/.exec(lastLine);
    if (match && match[3]) {
        return `${match[2]}: ${match[3]}`;
    }

    // A traceback line that carries detail without matching the usual shape is
    // still better than a bare class name, as long as it is not the class name.
    if (lastLine && lastLine !== cls) {
        return lastLine;
    }
    return cls || 'no detail';
}

/** A channel as the picker needs it, whichever endpoint it came from. */
export interface RemoteChannel {
    id: string;
    name: string;
    description: string;
    version: number | null;
    language: string | null;
    totalResources: number | null;
    publishedSize: number | null;
}

/**
 * Reads a channel row into {@link RemoteChannel}.
 *
 * Lives here rather than next to the fetch because of the trap it encapsulates:
 * **the same channel arrives under two different sets of field names.** Studio's
 * own API says `total_resource_count` and `published_size`; Kolibri's
 * `/api/content/remotechannel/` proxy renames them to `total_resources` and
 * `total_file_size` on the way through. Reading only Studio's names against the
 * proxy silently yields `null` for both — which is exactly what shipped, and
 * what a device test caught. Both spellings are accepted, Kolibri's first,
 * because that is the endpoint the box actually calls.
 *
 * Pure: no network here, so the renaming is covered by a unit test and cannot
 * regress unnoticed.
 */
export function toRemoteChannel(row: Record<string, unknown>): RemoteChannel {
    const num = (v: unknown): number | null =>
        typeof v === 'number' ? v : (typeof v === 'string' && v.trim() !== '' ? Number(v) : null);
    const first = (...vs: unknown[]): number | null => {
        for (const v of vs) {
            const n = num(v);
            if (n !== null) return n;
        }
        return null;
    };
    return {
        id: String(row.id ?? ''),
        name: String(row.name ?? ''),
        description: String(row.description ?? ''),
        version: num(row.version),
        language: typeof row.lang_code === 'string' ? row.lang_code
            : (typeof row.language === 'string' ? row.language : null),
        totalResources: first(row.total_resources, row.total_resource_count),
        publishedSize: first(row.total_file_size, row.published_size),
    };
}

/** Bytes per second from two samples of `transferred_file_size`.
 *  Returns null when there was no progress, so a 0 does not overwrite the previous
 *  reading. */
export function sampleSpeed(
    prevBytes: number, nextBytes: number, prevAtMs: number, nextAtMs: number,
): number | null {
    if (nextBytes <= prevBytes) return null;
    const dtSec = Math.max(0.001, (nextAtMs - prevAtMs) / 1000);
    return Math.max(0, Math.round((nextBytes - prevBytes) / dtSec));
}

/**
 * K2GO-476: bytes of the DISTINCT files under each node.
 *
 * A file used by several nodes (the same video in two topics) counts once per folder,
 * as Kolibri downloads it once; summing per node inflated folders 1.2x-1.7x. Each use
 * walks up its ancestors and stops at one this file already reached. The catalog
 * generator applies the same rule (controller/app/tools/build_kolibri_catalog.py).
 *
 * @param uses     file id -> its size and the node ids that use it
 * @param parentOf node id -> parent node id
 */
export function distinctSubtreeBytes(
    uses: Map<string, { size: number; nodes: string[] }>,
    parentOf: Map<string, string | null>,
): Map<string, number> {
    const out = new Map<string, number>();
    for (const { size, nodes } of uses.values()) {
        const seen = new Set<string>();
        for (const node of nodes) {
            let cur: string | null | undefined = node;
            while (cur && !seen.has(cur)) {
                seen.add(cur);
                out.set(cur, (out.get(cur) ?? 0) + size);
                cur = parentOf.get(cur);
            }
        }
    }
    return out;
}
