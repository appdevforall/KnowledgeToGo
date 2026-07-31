// sockets/kolibri.map.ts — helpers puros de traducción Kolibri ↔ motor de jobs
//
// Separado de kolibri.exec.ts por la misma razón que maps.socket.ts está separado
// de maps.exec.ts: aquí no hay red, ni SQLite, ni dependencias nativas, así que se
// puede testear con `node --test` sin compilar better-sqlite3 — que es justo lo que
// hace el CI con `npm ci --ignore-scripts`.
//
// Todo lo que vive aquí encapsula una trampa concreta de la API de Kolibri.

/** Tarea que importa metadatos + archivos en un solo job.
 *  Los nombres antiguos (startremotechannelimport, startchannelupdate…) son de una
 *  API anterior a la 0.15 y ya no existen en el backend: solo sobreviven en tests
 *  del frontend de Kolibri. */
export const TASK_REMOTE_IMPORT = 'kolibri.core.content.tasks.remoteimport';
export const TASK_DELETE_CHANNEL = 'kolibri.core.content.tasks.deletechannel';

/** Estados terminales de Kolibri (kolibri/core/tasks/job.py, class State). */
export const TERMINAL_STATES = new Set(['COMPLETED', 'FAILED', 'CANCELED']);

/** Estados en los que el job todavía no ha sido tomado por un worker. */
export const PRE_RUN_STATES = new Set(['PENDING', 'SCHEDULED', 'QUEUED']);

/** Fases del motor de jobs de K2Go. Se replica el tipo en lugar de importarlo de
 *  jobs.ts para no arrastrar better-sqlite3 a este módulo puro. */
export type Phase =
    | 'queued' | 'downloading' | 'indexing' | 'processing'
    | 'done' | 'error' | 'canceled';

const HEX32 = /^[0-9a-f]{32}$/;

/**
 * Normaliza un identificador de canal o nodo a hex de 32 minúsculas.
 *
 * Kolibri acepta UUID con o sin guiones y los normaliza (HexOnlyUUIDField), pero
 * validar aquí evita encolar basura y da el error en el sitio correcto. Devuelve
 * null para tokens proquint: no son channel_id, y pasarlos tal cual acabaría en un
 * 404 del descargador sin mensaje claro.
 */
export function normalizeUuid(raw: unknown): string | null {
    if (typeof raw !== 'string') return null;
    const v = raw.trim().replace(/-/g, '').toLowerCase();
    return HEX32.test(v) ? v : null;
}

/** Traduce el estado de Kolibri a la fase del motor de jobs. */
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
            // Estado nuevo en una versión futura: no romper, seguir mostrando avance.
            return 'processing';
    }
}

/**
 * Kolibri devuelve `percentage` como float 0–1; el motor de jobs usa entero 0–100.
 * Confundirlos es el error más fácil de cometer con esta API.
 * Devuelve -1 (indeterminado, la convención de jobs.ts) si no hay dato.
 */
export function mapPercent(percentage: number | null | undefined): number {
    if (typeof percentage !== 'number' || Number.isNaN(percentage)) return -1;
    return Math.max(0, Math.min(100, Math.round(percentage * 100)));
}

/** Progreso global cuando un job de K2Go abarca varios canales: cada canal ocupa
 *  su franja, de modo que la barra sube 0→100 una sola vez en lugar de reiniciarse.
 *  Se acota a 99 para que el 100 lo ponga el final del runner. */
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
 * Construye el cuerpo del POST a /api/tasks/tasks/.
 *
 * Encapsula tres trampas verificadas en el código de Kolibri:
 *
 *   1. `channel_name` es OBLIGATORIO (serializers.CharField sin required=False).
 *      Omitirlo da 400 aunque el campo solo se use para metadatos de presentación.
 *   2. `peer` NO admite null (PrimaryKeyRelatedField sin allow_null=True): mandar
 *      `"peer": null` da 400 "This field may not be null". Hay que OMITIR la clave.
 *   3. `node_ids: []` significa CERO NODOS, no "todo el canal". Ausente significa
 *      todo. Una lista vacía descargaría nada terminando con éxito.
 */
export function buildTaskPayload(item: TaskPayloadInput): Record<string, unknown> {
    const payload: Record<string, unknown> = {
        type: TASK_REMOTE_IMPORT,
        channel_id: item.channelId,
        channel_name: item.channelName,
        // Sin esto los errores 404 / ENOENT / nombre inválido se cuentan como
        // "skipped" y la tarea termina como COMPLETED.
        fail_on_error: true,
        renderable_only: true,
    };
    if (item.nodeIds && item.nodeIds.length > 0) payload.node_ids = item.nodeIds;
    if (item.excludeNodeIds && item.excludeNodeIds.length > 0) {
        payload.exclude_node_ids = item.excludeNodeIds;
    }
    // Con selección parcial, sin esto la navegación por temas queda con huecos:
    // las miniaturas de los topics no seleccionados no se descargan.
    if (item.allThumbnails) payload.all_thumbnails = true;
    return payload;
}

/** Bytes por segundo a partir de dos muestras de `transferred_file_size`.
 *  Devuelve null si no hubo avance, para no pisar la velocidad anterior con un 0. */
export function sampleSpeed(
    prevBytes: number, nextBytes: number, prevAtMs: number, nextAtMs: number,
): number | null {
    if (nextBytes <= prevBytes) return null;
    const dtSec = Math.max(0.001, (nextAtMs - prevAtMs) / 1000);
    return Math.max(0, Math.round((nextBytes - prevBytes) / dtSec));
}
