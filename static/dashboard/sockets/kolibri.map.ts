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

/** Translates a Kolibri state into a jobs-engine phase. */
export function mapPhase(kolibriStatus: string): Phase {
    switch (kolibriStatus) {
        case 'PENDING':
        case 'SCHEDULED':
        case 'QUEUED':
        case 'SELECTED':
            return 'queued';
        case 'RUNNING':
            return 'downloading';
        case 'COMPLETED':
            return 'done';
        case 'FAILED':
            return 'error';
        case 'CANCELING':
        case 'CANCELED':
            return 'canceled';
        default:
            // A state added in some future version: do not break, keep reporting progress.
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
        // Without this, 404 / ENOENT / invalid-name errors are counted as
        // "skipped" and the task finishes as COMPLETED.
        fail_on_error: true,
        renderable_only: true,
    };
    if (item.nodeIds && item.nodeIds.length > 0) payload.node_ids = item.nodeIds;
    if (item.excludeNodeIds && item.excludeNodeIds.length > 0) {
        payload.exclude_node_ids = item.excludeNodeIds;
    }
    // On a partial selection, without this the topic browse has gaps: thumbnails
    // for unselected topics are never downloaded.
    if (item.allThumbnails) payload.all_thumbnails = true;
    return payload;
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
