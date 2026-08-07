// sockets/kolibri.query.ts — direct (non-job) Kolibri queries
//
// Same role as books.query.ts: what the UI needs in order to decide WHAT to
// download, kept separate from the job that downloads it.
//
//   listInstalledChannels()  what is already on the device, with available bytes
//   browseRemoteChannels()   remote catalogue for the wizard's picker
//   resolveIdentifier()      token or UUID → channel (users supply the tokens,
//                            the CLI and the tasks demand a UUID)
//   browseChannelTree()      granular tree for choosing subtrees
//   estimateSelection()      exact bytes of a selection, before downloading it
//   deleteChannel()          remove a channel
//
// Local totals are read from SQLite in readonly (the books.query.ts pattern) rather
// than over HTTP: it is cheaper and needs no session. Watch out for one trap: the
// 'available' column lives in db.sqlite3, NOT in the channel's .sqlite3, and is set
// in one go at the END of the import — so it answers "is it complete?" but is no
// source of live progress.
import Database from 'better-sqlite3';
import fs from 'fs';
import path from 'path';
import {
    loginForContent, login, apiJson, apiFetch, checkReadiness,
    KolibriSession, STUDIO_URL,
} from './kolibri.session';
import {
    TASK_DELETE_CHANNEL, TERMINAL_STATES, mapPercent, toRemoteChannel, RemoteChannel,
} from './kolibri.map';

const KOLIBRI_HOME = process.env.KOLIBRI_HOME || '/library/kolibri';
const MAIN_DB = path.join(KOLIBRI_HOME, 'db.sqlite3');
const CONTENT_DIR = process.env.KOLIBRI_CONTENT_DIR
    || path.join(KOLIBRI_HOME, 'content');

export interface InstalledChannel {
    id: string;
    name: string;
    version: number;
    filesTotal: number;
    filesAvailable: number;
    bytesTotal: number;
    bytesAvailable: number;
    complete: boolean;
}

/**
 * Channels present on the device and how much of each one is available.
 *
 * A single pass: the CTE reduces content_file × content_contentnode to DISTINCT
 * (channel_id, local_file_id) pairs and then aggregates once. The DISTINCT is not
 * cosmetic — a LocalFile can hang off several ContentNode rows of the same channel,
 * and a flat JOIN would count its bytes once per node that references it.
 *
 * The LEFT JOIN is deliberate: a channel whose metadata is imported but which has
 * no content must show up with zeros, not vanish from the listing.
 */
export function listInstalledChannels(): InstalledChannel[] {
    if (!fs.existsSync(MAIN_DB)) return [];
    const db = new Database(MAIN_DB, { readonly: true });
    try {
        const rows = db.prepare(`
            WITH channel_files AS (
                SELECT DISTINCT cn.channel_id AS channel_id,
                                f.local_file_id AS local_file_id
                FROM content_file f
                JOIN content_contentnode cn ON cn.id = f.contentnode_id
            )
            SELECT cm.id      AS id,
                   cm.name    AS name,
                   cm.version AS version,
                   COUNT(lf.id)                                                   AS filesTotal,
                   COALESCE(SUM(CASE WHEN lf.available = 1 THEN 1 ELSE 0 END), 0)  AS filesAvailable,
                   COALESCE(SUM(lf.file_size), 0)                                 AS bytesTotal,
                   COALESCE(SUM(CASE WHEN lf.available = 1
                                     THEN lf.file_size ELSE 0 END), 0)            AS bytesAvailable
            FROM content_channelmetadata cm
            LEFT JOIN channel_files p      ON p.channel_id = cm.id
            LEFT JOIN content_localfile lf ON lf.id = p.local_file_id
            GROUP BY cm.id, cm.name, cm.version
            ORDER BY cm.name
        `).all() as Array<Omit<InstalledChannel, 'complete'>>;
        return rows.map((r) => ({
            ...r,
            complete: r.filesTotal > 0 && r.filesAvailable >= r.filesTotal,
        }));
    } finally {
        db.close();
    }
}

/**
 * Bytes already materialised on disk, excluding transfers in progress.
 *
 * DO NOT CALL FROM A REST HANDLER. This is a synchronous walk of the content
 * tree: on a populated device that is tens of thousands of files, and Node is
 * single-threaded, so it would block every other endpoint —including the
 * kiwix/maps/books jobs— for the whole walk.
 *
 * It is kept because it is the only source of progress that does not depend on the
 * Kolibri API (whose `available` column is set in one go at the end of the import).
 * If it is ever needed live, it has to move to fs.promises and be cached.
 */
export function contentBytesOnDisk(): number {
    const storage = path.join(CONTENT_DIR, 'storage');
    let total = 0;
    const walk = (dir: string): void => {
        let entries: fs.Dirent[];
        try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
        for (const e of entries) {
            const p = path.join(dir, e.name);
            if (e.isDirectory()) { walk(p); continue; }
            // .transfer and .chunks are partial downloads; they do not count as present.
            if (e.name.endsWith('.transfer') || e.name.endsWith('.chunks')) continue;
            try { total += fs.statSync(p).size; } catch { /* it vanished */ }
        }
    };
    walk(storage);
    return total;
}

// RemoteChannel and toRemoteChannel now live in kolibri.map.ts: the mapper is
// pure, and the two competing sets of field names it reconciles deserve a unit
// test rather than a comment. Re-exported so the routes keep their import path.
export { RemoteChannel };

/**
 * Remote catalogue for the wizard's picker, through the device's own proxy.
 *
 * /api/content/remotechannel/ is used instead of going straight to Studio so that
 * the origin and the cache are Kolibri's (it caches for 5 min) and so the network
 * policy is not duplicated. It returns 503 {"status":"offline"} when there is no
 * connectivity, which is turned into a readable exception here.
 */
export async function browseRemoteChannels(
    opts: { keyword?: string; language?: string } = {},
): Promise<RemoteChannel[]> {
    const session = await loginForContent();
    const qs = new URLSearchParams();
    if (opts.keyword) qs.set('keyword', opts.keyword);
    if (opts.language) qs.set('language', opts.language);
    const suffix = qs.toString() ? `?${qs.toString()}` : '';
    const data = await apiJson<unknown>(
        session, `/api/content/remotechannel/${suffix}`, {}, 30000);
    const list = Array.isArray(data)
        ? data
        : ((data as { results?: unknown[] })?.results ?? []);
    return (list as Array<Record<string, unknown>>).map(toRemoteChannel);
}

/**
 * Resolves a token (xxxxx-xxxxx or xxxxxxxxxx) or a UUID to its channel.
 *
 * Needed because users copy tokens from Studio, but the tasks and the CLI demand
 * the 32-char hex. The hyphens are purely presentational: Studio stores the
 * proquint without a hyphen.
 */
export async function resolveIdentifier(identifier: string): Promise<RemoteChannel> {
    const session = await loginForContent();
    const normalized = identifier.trim().replace(/-/g, '').toLowerCase();
    const data = await apiJson<unknown>(
        session, `/api/content/remotechannel/${encodeURIComponent(normalized)}/`, {}, 30000);
    const row = Array.isArray(data) ? data[0] : data;
    if (!row || typeof row !== 'object') throw new Error(`channel '${identifier}' not found`);
    const channel = toRemoteChannel(row as Record<string, unknown>);
    if (!channel.id) throw new Error(`channel '${identifier}' not found`);
    return channel;
}

export interface TreeNode {
    id: string;
    title: string;
    kind: string;
    isLeaf: boolean;
    totalResources: number;
    onDeviceResources: number;
    importable: boolean;
    children?: TreeNode[];
}

function toTreeNode(row: Record<string, unknown>): TreeNode {
    const kids = Array.isArray(row.children)
        ? (row.children as Array<Record<string, unknown>>).map(toTreeNode)
        : undefined;
    return {
        id: String(row.id ?? ''),
        title: String(row.title ?? ''),
        kind: String(row.kind ?? ''),
        isLeaf: row.is_leaf === true,
        totalResources: typeof row.total_resources === 'number' ? row.total_resources : 0,
        onDeviceResources: typeof row.on_device_resources === 'number' ? row.on_device_resources : 0,
        importable: row.importable !== false,
        ...(kids ? { children: kids } : {}),
    };
}

/**
 * One level of the channel tree, so the wizard can offer a choice of subtrees.
 *
 * PRECONDITION: the channel metadata has to be in the local database already, which
 * means a channel import must have run first. This is the same flow as Kolibri's
 * own UI: it downloads the metadata first (MB), then it lets you pick content (GB).
 *
 * When no nodeId is passed, the channel root is used.
 */
export async function browseChannelTree(
    channelId: string, nodeId?: string,
): Promise<TreeNode> {
    const session = await loginForContent();
    let target = nodeId;
    if (!target) {
        const channel = await apiJson<Record<string, unknown>>(
            session, `/api/content/channel/${channelId}/`);
        const root = channel?.root;
        if (typeof root !== 'string') {
            throw new Error(`channel ${channelId} is not in the local database: `
                + 'import its metadata first');
        }
        target = root;
    }
    const node = await apiJson<Record<string, unknown>>(
        session, `/api/content/contentnode_granular/${target}/`, {}, 30000);
    return toTreeNode(node);
}

export interface SelectionSize {
    resourceCount: number;
    fileSize: number;
    freeSpace: number | null;
    fitsOnDevice: boolean | null;
}

/**
 * What a selection still has to transfer, plus the free space.
 *
 * Two things about this endpoint that a device test made plain, and that its
 * name does not suggest:
 *
 *   1. **It only answers for a channel already on the device.** Internally it
 *      reaches `_calculate_batch_params`, which reads `max_rght` from local
 *      `ContentNode` rows and multiplies it without a null check; for a channel
 *      that was never imported that is `250 * None` and Kolibri returns a bare
 *      HTTP 500. So the caller is turned away here with a usable message rather
 *      than being handed an opaque server error.
 *   2. **`fileSize` is what is OUTSTANDING, not the channel's size.** The view
 *      filters to unavailable files, so a fully downloaded channel correctly
 *      reports 0. It answers "how much more do I need?", never "how big is it?"
 *      — the size of something not yet installed comes from the catalog.
 *
 * Watch the prefix too: this is NOT under /api/ but under /device/api/, because
 * the 'device' plugin publishes it.
 */
export async function estimateSelection(
    channelId: string, nodeIds?: string[], excludeNodeIds?: string[],
): Promise<SelectionSize> {
    if (!isChannelInstalled(channelId)) {
        throw new Error(
            `channel ${channelId} is not on the device: its remaining size can only be `
            + 'measured once its metadata has been imported');
    }

    const session = await loginForContent();
    const body: Record<string, unknown> = { channel_id: channelId };
    if (nodeIds && nodeIds.length) body.node_ids = nodeIds;
    if (excludeNodeIds && excludeNodeIds.length) body.exclude_node_ids = excludeNodeIds;

    const size = await apiJson<{ resource_count?: number; file_size?: number }>(
        session, '/device/api/importexportsizeview',
        { method: 'POST', body: JSON.stringify(body) }, 60000);

    let freeSpace: number | null = null;
    try {
        // ?path=Content is MANDATORY: FreeSpaceView.list answers 400 "Invalid path"
        // for anything else, including no parameter at all. Without it this call
        // threw on every request, the catch below swallowed it, and freeSpace was
        // permanently null — so fitsOnDevice could never be anything but null.
        const fs2 = await apiJson<{ freespace?: number }>(
            session, '/api/device/freespace/?path=Content');
        freeSpace = typeof fs2.freespace === 'number' ? fs2.freespace : null;
    } catch { /* non-blocking */ }

    const fileSize = size.file_size ?? 0;
    return {
        resourceCount: size.resource_count ?? 0,
        fileSize,
        freeSpace,
        // Kolibri already subtracts its buffer (MINIMUM_DISK_SPACE, 250 MB) when
        // computing freespace, so comparing directly is correct.
        fitsOnDevice: freeSpace === null ? null : freeSpace > fileSize,
    };
}

/**
 * Whether the channel's metadata is in the local content database.
 *
 * A single-row lookup, not the full inventory query: this runs before every
 * estimate and only needs a yes or no.
 */
function isChannelInstalled(channelId: string): boolean {
    if (!fs.existsSync(MAIN_DB)) return false;
    try {
        const db = new Database(MAIN_DB, { readonly: true });
        try {
            const row = db.prepare(
                'SELECT 1 FROM content_channelmetadata WHERE id = ? LIMIT 1',
            ).get(channelId);
            return row !== undefined;
        } finally {
            db.close();
        }
    } catch {
        // Unreadable database: treat as not installed, which produces the same
        // actionable message rather than an opaque 500 from Kolibri.
        return false;
    }
}

/**
 * Deletes a whole channel (metadata + files) through the Kolibri task.
 *
 * It returns the KOLIBRI job id, not the local engine's: this is a short operation
 * and needs no durable job. Query it with getKolibriTask() / GET /kolibri/task/:id.
 */
export async function deleteChannel(channelId: string, channelName?: string): Promise<string> {
    const session = await loginForContent();
    const job = await apiJson<{ id?: string }>(session, '/api/tasks/tasks/', {
        method: 'POST',
        body: JSON.stringify({
            type: TASK_DELETE_CHANNEL,
            channel_id: channelId,
            channel_name: channelName || channelId,
        }),
    }, 30000);
    if (!job?.id) throw new Error('Kolibri did not return a job id');
    return job.id;
}

export interface KolibriTaskStatus {
    id: string;
    status: string;
    /** 0-100 integer, or -1 if Kolibri reports no progress for this task. */
    percent: number;
    exception: string | null;
    done: boolean;
}

/**
 * Status of a Kolibri task started outside the job engine (today, the deletion).
 *
 * Without this, deleteChannel() returned an id that none of our endpoints knew
 * how to query: the client got a useless identifier.
 */
export async function getKolibriTask(taskId: string): Promise<KolibriTaskStatus> {
    const session = await loginForContent();
    const job = await apiJson<{
        id: string; status: string; percentage: number | null; exception: string | null;
    }>(session, `/api/tasks/tasks/${encodeURIComponent(taskId)}/`);
    return {
        id: job.id,
        status: job.status,
        percent: mapPercent(job.percentage),
        exception: job.exception ?? null,
        done: TERMINAL_STATES.has(job.status),
    };
}

/** Extended diagnostics: readiness + local state. Consumed by /kolibri/preflight.
 *  checkReadiness() does not throw: it always returns a diagnostic, so a Kolibri
 *  that is down shows up in the fields instead of breaking the response.
 *
 *  The bytes come from SQLite, not from disk: summing bytesAvailable gives the same
 *  figure as walking content/storage and does not block the event loop. */
export async function preflight(): Promise<Record<string, unknown>> {
    const readiness = await checkReadiness();
    let installed: InstalledChannel[] = [];
    try { installed = listInstalledChannels(); } catch { /* database absent */ }
    return {
        ...readiness,
        studioUrl: STUDIO_URL,
        kolibriHome: KOLIBRI_HOME,
        contentDir: CONTENT_DIR,
        installedChannels: installed.length,
        channels: installed,
        bytesAvailable: installed.reduce((sum, c) => sum + c.bytesAvailable, 0),
        bytesTotal: installed.reduce((sum, c) => sum + c.bytesTotal, 0),
    };
}

/** Checks credentials without persisting them. Used by POST /credentials/kolibri. */
export async function verifyCredentials(
    username: string, password: string,
): Promise<{ ok: boolean; canManageContent: boolean; username: string }> {
    const session: KolibriSession = await login({ username, password });
    return {
        ok: true,
        canManageContent: session.canManageContent,
        username: session.username,
    };
}

export { apiFetch };
