// sockets/kolibri.query.ts — consultas directas (no-job) de Kolibri
//
// Mismo papel que books.query.ts: lo que la UI necesita para decidir QUÉ descargar,
// separado del job que lo descarga.
//
//   listInstalledChannels()  qué hay ya en el dispositivo, con bytes disponibles
//   browseRemoteChannels()   catálogo remoto para el selector del wizard
//   resolveIdentifier()      token o UUID → canal (los tokens los pide el usuario,
//                            la CLI y las tareas exigen UUID)
//   browseChannelTree()      árbol granular para elegir subárboles
//   estimateSelection()      bytes exactos de una selección, antes de bajarla
//   deleteChannel()          quitar un canal
//
// Los totales locales se leen de SQLite en readonly (patrón de books.query.ts) en
// lugar de por HTTP: es más barato y no necesita sesión. OJO con una trampa: la
// columna 'available' vive en db.sqlite3, NO en el .sqlite3 del canal, y se marca
// de golpe al FINAL del import — así que sirve para "¿está completo?" pero no como
// fuente de progreso en vivo.
import Database from 'better-sqlite3';
import fs from 'fs';
import path from 'path';
import {
    loginForContent, login, apiJson, apiFetch, checkReadiness,
    KolibriSession, STUDIO_URL,
} from './kolibri.session';

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
 * Canales presentes en el dispositivo y cuánto de cada uno está disponible.
 *
 * El DISTINCT no es cosmético: un LocalFile puede colgar de varios ContentNode, y
 * un JOIN plano contaría los mismos bytes varias veces.
 */
export function listInstalledChannels(): InstalledChannel[] {
    if (!fs.existsSync(MAIN_DB)) return [];
    const db = new Database(MAIN_DB, { readonly: true });
    try {
        const rows = db.prepare(`
            SELECT cm.id      AS id,
                   cm.name    AS name,
                   cm.version AS version,
                   (SELECT COUNT(*) FROM content_localfile lf WHERE lf.id IN (
                        SELECT DISTINCT f.local_file_id FROM content_file f
                        JOIN content_contentnode cn ON cn.id = f.contentnode_id
                        WHERE cn.channel_id = cm.id))                       AS filesTotal,
                   (SELECT COUNT(*) FROM content_localfile lf
                     WHERE lf.available = 1 AND lf.id IN (
                        SELECT DISTINCT f.local_file_id FROM content_file f
                        JOIN content_contentnode cn ON cn.id = f.contentnode_id
                        WHERE cn.channel_id = cm.id))                       AS filesAvailable,
                   (SELECT COALESCE(SUM(lf.file_size),0) FROM content_localfile lf
                     WHERE lf.id IN (
                        SELECT DISTINCT f.local_file_id FROM content_file f
                        JOIN content_contentnode cn ON cn.id = f.contentnode_id
                        WHERE cn.channel_id = cm.id))                       AS bytesTotal,
                   (SELECT COALESCE(SUM(lf.file_size),0) FROM content_localfile lf
                     WHERE lf.available = 1 AND lf.id IN (
                        SELECT DISTINCT f.local_file_id FROM content_file f
                        JOIN content_contentnode cn ON cn.id = f.contentnode_id
                        WHERE cn.channel_id = cm.id))                       AS bytesAvailable
            FROM content_channelmetadata cm
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

/** Bytes ya materializados en disco, excluyendo transferencias en curso.
 *  Es la única fuente fiable de progreso si algún día se necesita sin la API. */
export function contentBytesOnDisk(): number {
    const storage = path.join(CONTENT_DIR, 'storage');
    let total = 0;
    const walk = (dir: string): void => {
        let entries: fs.Dirent[];
        try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
        for (const e of entries) {
            const p = path.join(dir, e.name);
            if (e.isDirectory()) { walk(p); continue; }
            // .transfer y .chunks son descargas a medias; no cuentan como presentes.
            if (e.name.endsWith('.transfer') || e.name.endsWith('.chunks')) continue;
            try { total += fs.statSync(p).size; } catch { /* desapareció */ }
        }
    };
    walk(storage);
    return total;
}

export interface RemoteChannel {
    id: string;
    name: string;
    description: string;
    version: number | null;
    language: string | null;
    totalResources: number | null;
    publishedSize: number | null;
}

function toRemoteChannel(row: Record<string, unknown>): RemoteChannel {
    const num = (v: unknown): number | null =>
        typeof v === 'number' ? v : (typeof v === 'string' && v.trim() !== '' ? Number(v) : null);
    return {
        id: String(row.id ?? ''),
        name: String(row.name ?? ''),
        description: String(row.description ?? ''),
        version: num(row.version),
        language: typeof row.lang_code === 'string' ? row.lang_code
            : (typeof row.language === 'string' ? row.language : null),
        totalResources: num(row.total_resource_count),
        publishedSize: num(row.published_size),
    };
}

/**
 * Catálogo remoto para el selector del wizard, vía el proxy del propio dispositivo.
 *
 * Se usa /api/content/remotechannel/ en lugar de ir directo a Studio para que el
 * origen y la caché sean los de Kolibri (cachea 5 min) y para no duplicar la
 * política de red. Devuelve 503 {"status":"offline"} si no hay conectividad, que
 * aquí se traduce en una excepción legible.
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
 * Resuelve un token (xxxxx-xxxxx o xxxxxxxxxx) o un UUID a su canal.
 *
 * Necesario porque el usuario copia tokens de Studio, pero las tareas y la CLI
 * exigen el hex de 32. Los guiones son puramente presentacionales: Studio guarda
 * el proquint sin guion.
 */
export async function resolveIdentifier(identifier: string): Promise<RemoteChannel> {
    const session = await loginForContent();
    const normalized = identifier.trim().replace(/-/g, '').toLowerCase();
    const data = await apiJson<unknown>(
        session, `/api/content/remotechannel/${encodeURIComponent(normalized)}/`, {}, 30000);
    const row = Array.isArray(data) ? data[0] : data;
    if (!row || typeof row !== 'object') throw new Error(`no se encontró el canal '${identifier}'`);
    const channel = toRemoteChannel(row as Record<string, unknown>);
    if (!channel.id) throw new Error(`no se encontró el canal '${identifier}'`);
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
 * Un nivel del árbol del canal, para que el wizard permita elegir subárboles.
 *
 * PRECONDICIÓN: los metadatos del canal ya tienen que estar en la base local, o
 * sea que hay que haber corrido un import de canal antes. Es el mismo flujo que la
 * UI de Kolibri: primero baja los metadatos (MB), luego deja elegir el contenido (GB).
 *
 * Si no se pasa nodeId, se usa la raíz del canal.
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
            throw new Error(`el canal ${channelId} no está en la base local: `
                + 'importa primero sus metadatos');
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
 * Tamaño exacto de una selección antes de comprometer bytes, más el espacio libre.
 *
 * OJO con el prefijo: este endpoint NO está bajo /api/ sino bajo /device/api/,
 * porque lo publica el plugin 'device'.
 */
export async function estimateSelection(
    channelId: string, nodeIds?: string[], excludeNodeIds?: string[],
): Promise<SelectionSize> {
    const session = await loginForContent();
    const body: Record<string, unknown> = { channel_id: channelId };
    if (nodeIds && nodeIds.length) body.node_ids = nodeIds;
    if (excludeNodeIds && excludeNodeIds.length) body.exclude_node_ids = excludeNodeIds;

    const size = await apiJson<{ resource_count?: number; file_size?: number }>(
        session, '/device/api/importexportsizeview',
        { method: 'POST', body: JSON.stringify(body) }, 60000);

    let freeSpace: number | null = null;
    try {
        const fs2 = await apiJson<{ freespace?: number }>(session, '/api/device/freespace/');
        freeSpace = typeof fs2.freespace === 'number' ? fs2.freespace : null;
    } catch { /* no bloqueante */ }

    const fileSize = size.file_size ?? 0;
    return {
        resourceCount: size.resource_count ?? 0,
        fileSize,
        freeSpace,
        // Kolibri ya resta su colchón (MINIMUM_DISK_SPACE, 250 MB) al calcular
        // freespace, así que comparar directo es correcto.
        fitsOnDevice: freeSpace === null ? null : freeSpace > fileSize,
    };
}

/** Borra un canal completo (metadatos + archivos) vía la tarea de Kolibri. */
export async function deleteChannel(channelId: string, channelName?: string): Promise<string> {
    const session = await loginForContent();
    const job = await apiJson<{ id?: string }>(session, '/api/tasks/tasks/', {
        method: 'POST',
        body: JSON.stringify({
            type: 'kolibri.core.content.tasks.deletechannel',
            channel_id: channelId,
            channel_name: channelName || channelId,
        }),
    }, 30000);
    if (!job?.id) throw new Error('Kolibri no devolvió un id de job');
    return job.id;
}

/** Diagnóstico ampliado: readiness + estado local. Lo consume /kolibri/preflight.
 *  checkReadiness() no lanza: siempre devuelve un diagnóstico, así que un Kolibri
 *  caído se refleja en los campos en lugar de romper la respuesta. */
export async function preflight(): Promise<Record<string, unknown>> {
    const readiness = await checkReadiness();
    let installed: InstalledChannel[] = [];
    try { installed = listInstalledChannels(); } catch { /* base ausente */ }
    return {
        ...readiness,
        studioUrl: STUDIO_URL,
        kolibriHome: KOLIBRI_HOME,
        contentDir: CONTENT_DIR,
        installedChannels: installed.length,
        channels: installed,
        bytesOnDisk: (() => { try { return contentBytesOnDisk(); } catch { return null; } })(),
    };
}

/** Comprueba unas credenciales sin persistirlas. Lo usa POST /credentials/kolibri. */
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
