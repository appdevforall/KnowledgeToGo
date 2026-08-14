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
    TASK_DELETE_CHANNEL, TERMINAL_STATES, mapPercent, toRemoteChannel,
} from './kolibri.map';
import type { RemoteChannel } from './kolibri.map';

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
// test rather than a comment. Re-exported so the routes keep their import path —
// as `export type`, because RemoteChannel is an interface with no runtime value
// and a plain re-export stops compiling the day isolatedModules or
// verbatimModuleSyntax is switched on.
export type { RemoteChannel };

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

// ---- Offline subtree, Studio-shaped, with byte sizes (ADFA-5094) ------------------
// The app's LocalTreeSource reads this and parses it with the SAME mapper it uses for
// Studio, so the wire shape here is Studio's public contentnode_tree — not the granular
// TreeNode above (which the web wizard uses and which carries no bytes).
//
// Why raw SQL and not a Kolibri API: the granular endpoint has no byte sizes, and
// importexportsizeview reports only a selection's OUTSTANDING transfer, never per-node
// structural size. The per-node bytes live in content_localfile.file_size, so they are
// summed here over each node's MPTT (lft/rght) range. That is what lets the picker show
// sizes at every level with no network.
//
// NOTE (verification): the SQL runs against a live Kolibri database, which cannot be
// exercised in CI — it is validated on a box with a metadata-imported channel. If a column
// or the schema differs, this throws, the route returns 500, and LocalTreeSource falls back
// to Studio: a wrong query degrades to today's behaviour, it does not break the picker.

interface StudioFile { file_size: number; preset: string; }
interface StudioNode {
    id: string;
    title: string;
    kind: string;
    is_leaf: boolean;
    lft: number;
    rght: number;
    files: StudioFile[];
    children: { more: unknown | null; results: StudioNode[] };
}

interface CnRow {
    id: string;
    parent_id: string | null;
    title: string;
    kind: string;
    lft: number;
    rght: number;
}
interface FileRow { node_id: string; preset: string; file_size: number; }

// A generous ceiling that keeps the JSON well under LocalTreeSource's 8 MB cap while
// covering ordinary channels whole. A subtree larger than this is truncated and its root
// is marked incomplete, so the mapper reports the size as unknown rather than under-counting.
const SUBTREE_NODE_CAP = 20000;

/**
 * One channel subtree rooted at {@code nodeId}, in Studio's contentnode_tree shape, built
 * from the local content database. Returns {@code null} when the node is not present — i.e.
 * the channel's metadata has not been imported — which the app reads as "fall back to Studio".
 *
 * The channel is scoped by the root node's own channel_id + MPTT range, so a node id alone
 * is enough (as with Studio), and no channelId has to be threaded from the caller.
 */
export function buildLocalSubtree(nodeId: string): StudioNode | null {
    if (!fs.existsSync(MAIN_DB)) return null;
    const db = new Database(MAIN_DB, { readonly: true });
    try {
        const root = db.prepare(
            `SELECT id, parent_id, title, kind, lft, rght, channel_id
               FROM content_contentnode WHERE id = ?`,
        ).get(nodeId) as (CnRow & { channel_id: string }) | undefined;
        if (!root) return null;

        const rows = db.prepare(
            `SELECT id, parent_id, title, kind, lft, rght
               FROM content_contentnode
              WHERE channel_id = ? AND lft >= ? AND rght <= ?
              ORDER BY lft
              LIMIT ?`,
        ).all(root.channel_id, root.lft, root.rght, SUBTREE_NODE_CAP) as CnRow[];

        // Real content files only: thumbnails and supplementary files are excluded so the
        // sizes match a normal import, the same exclusion StudioCatalogMapper makes.
        const files = db.prepare(
            `SELECT f.contentnode_id AS node_id, f.preset AS preset, lf.file_size AS file_size
               FROM content_file f
               JOIN content_localfile lf ON lf.id = f.local_file_id
              WHERE f.thumbnail = 0 AND f.supplementary = 0
                AND f.contentnode_id IN (
                    SELECT id FROM content_contentnode
                     WHERE channel_id = ? AND lft >= ? AND rght <= ? LIMIT ?)`,
        ).all(root.channel_id, root.lft, root.rght, SUBTREE_NODE_CAP) as FileRow[];

        const filesByNode = new Map<string, StudioFile[]>();
        for (const f of files) {
            const arr = filesByNode.get(f.node_id) ?? [];
            arr.push({ file_size: f.file_size ?? 0, preset: f.preset ?? '' });
            filesByNode.set(f.node_id, arr);
        }

        const byId = new Map<string, StudioNode>();
        for (const r of rows) {
            byId.set(r.id, {
                id: r.id,
                title: r.title ?? '',
                kind: r.kind ?? '',
                is_leaf: r.kind !== 'topic',
                lft: r.lft,
                rght: r.rght,
                files: filesByNode.get(r.id) ?? [],
                children: { more: null, results: [] },
            });
        }
        for (const r of rows) {
            if (r.id === root.id || !r.parent_id) continue;
            byId.get(r.parent_id)?.children.results.push(byId.get(r.id)!);
        }

        const rootNode = byId.get(root.id) ?? null;
        // Hitting the cap means the deepest nodes were dropped; mark the root incomplete so
        // the mapper reports its subtree size as unknown rather than silently under-counting.
        if (rootNode && rows.length >= SUBTREE_NODE_CAP) {
            rootNode.children.more = { cursor: 'truncated' };
        }
        return rootNode;
    } finally {
        db.close();
    }
}

export interface SelectionSize {
    resourceCount: number;
    fileSize: number;
    freeSpace: number | null;
    fitsOnDevice: boolean | null;
}

/**
 * The request was well formed but the channel is not on the device yet.
 *
 * A distinct type so the route can answer 409 rather than 500: the caller asked
 * for something that needs a precondition it has not met, which is not the
 * server breaking. Without this the readable message still arrived wrapped in an
 * "internal error", which is as misleading as the bare 500 it replaced.
 */
export class ChannelNotInstalledError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'ChannelNotInstalledError';
    }
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
    // ABSENT is a fact about the request; UNKNOWN is a fact about us. Only the
    // first one is the caller's problem, so only the first one refuses.
    if (installedState(channelId) === 'absent') {
        throw new ChannelNotInstalledError(
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
function installedState(channelId: string): 'present' | 'absent' | 'unknown' {
    // No database at all means nothing has ever been imported, which is a real
    // answer rather than a failure to look.
    if (!fs.existsSync(MAIN_DB)) return 'absent';
    try {
        const db = new Database(MAIN_DB, { readonly: true });
        try {
            const row = db.prepare(
                'SELECT 1 FROM content_channelmetadata WHERE id = ? LIMIT 1',
            ).get(channelId);
            return row === undefined ? 'absent' : 'present';
        } finally {
            db.close();
        }
    } catch {
        // The database exists but could not be read — SQLITE_BUSY while Kolibri
        // writes during an import is the realistic case. Reporting "not installed"
        // here would tell the user to import a channel they may already have. Say
        // we do not know, and let the request through: Kolibri is the authority,
        // and if the channel really is missing it answers its own 500, which is
        // where we started but only in the case we cannot rule out.
        return 'unknown';
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
