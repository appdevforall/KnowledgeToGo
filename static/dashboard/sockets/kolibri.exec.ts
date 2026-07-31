// sockets/kolibri.exec.ts — runner de Kolibri para el motor de jobs durable
//
// Siembra contenido en Kolibri encolando tareas en SU API REST y haciendo polling.
// A diferencia de kiwix/maps, aquí no se hace spawn de ningún binario: el trabajo
// lo ejecuta el propio Kolibri, que ya está corriendo dentro del proot.
//
// Ventaja concreta de esta vía sobre invocar la CLI: Kolibri reporta progreso
// estructurado (percentage, transferred_file_size, total_resources). Su CLI no
// reporta nada parseable — usa click.progressbar sin etiqueta, y en modo no-TTY la
// salida de un import completo es literalmente una línea vacía.
//
// Un item del job es:
//   { channelId, channelName?, nodeIds?, excludeNodeIds?, allThumbnails? }
// Se procesan en SECUENCIA: dos imports simultáneos solo generan contención sobre
// la misma db.sqlite3.
//
// PRECONDICIÓN: la capa de arranque garantiza que Kolibri está vivo y sus workers
// activos. El runner lo verifica igualmente y falla con un mensaje accionable en
// lugar de colgarse indefinidamente.
import { jobs, RunnerContext, CanceledError, JobUpdate } from './jobs';
import {
    loginForContent, apiJson, apiFetch, ensureContentOrigin,
    KolibriSession, KolibriAuthError, KolibriApiError, STUDIO_URL,
} from './kolibri.session';
import {
    buildTaskPayload, mapPercent, mapPhase, normalizeUuid, overallPercent,
    sampleSpeed, PRE_RUN_STATES, TERMINAL_STATES,
} from './kolibri.map';

/** Enteros positivos desde el entorno, con default. Ajustables en operación
 *  (redes muy lentas) y en tests. */
function envMs(name: string, def: number): number {
    const raw = process.env[name];
    if (!raw) return def;
    const n = Number(raw);
    return Number.isFinite(n) && n > 0 ? n : def;
}

const POLL_MS = envMs('K2GO_KOLIBRI_POLL_MS', 2000);

/** Si un job sigue sin ser tomado tras esto, casi seguro los workers no están
 *  vivos: encolar solo escribe una fila en job_storage.sqlite3; sin un
 *  WorkerSupervisor nadie la ejecuta y el job queda en QUEUED para siempre. */
const QUEUED_GRACE_MS = envMs('K2GO_KOLIBRI_QUEUED_GRACE_MS', 90_000);

/** Sin avance de bytes durante este tiempo, el transporte de Kolibri está en su
 *  bucle de reintentos —que NO tiene límite: espera 30 s y reintenta ante
 *  ConnectionError, Timeout y HTTP 502/503/504/521-524—. El estado seguiría en
 *  RUNNING eternamente, así que cortamos. Reintentar es seguro y barato: las
 *  descargas se reanudan por HTTP Range. */
const STALL_TIMEOUT_MS = envMs('K2GO_KOLIBRI_STALL_MS', 15 * 60_000);

/** Reintentos de login por job. Acotado para que una credencial revocada a mitad
 *  del import no genere un bucle de logins fallidos. */
const MAX_REAUTH = 3;

interface KolibriItem {
    channelId?: string;
    channelName?: string;
    nodeIds?: string[];
    excludeNodeIds?: string[];
    allThumbnails?: boolean;
}

interface ParsedItem {
    channelId: string;
    channelName?: string;
    nodeIds: string[];
    excludeNodeIds: string[];
    allThumbnails?: boolean;
}

interface KolibriJob {
    id: string;
    status: string;
    percentage: number | null;
    exception: string | null;
    traceback: string | null;
    extra_metadata?: {
        channel_name?: string;
        database_ready?: boolean;
        file_size?: number;
        total_resources?: number;
        transferred_file_size?: number;
        transferred_resources?: number;
    };
}

function sleep(ms: number): Promise<void> {
    return new Promise((r) => setTimeout(r, ms));
}

/** Duración legible: evita "0 min" cuando el umbral se baja para pruebas. */
function humanMs(ms: number): string {
    return ms < 60_000
        ? `${Math.round(ms / 1000)} s`
        : `${Math.round(ms / 60_000)} min`;
}

/** Valida y normaliza los items del job. Falla ruidosamente: es mejor rechazar en
 *  el encolado que descargar cero bytes con éxito aparente. */
export function parseItems(rawItems: unknown[]): ParsedItem[] {
    const parsed: ParsedItem[] = [];
    for (const raw of rawItems) {
        // Por comodidad se acepta también un channelId suelto, como hace kiwix.
        const item: KolibriItem = typeof raw === 'string'
            ? { channelId: raw }
            : ((raw ?? {}) as KolibriItem);

        const channelId = normalizeUuid(item.channelId);
        if (!channelId) {
            throw new Error(`channelId inválido: ${String(item.channelId)} — se espera `
                + 'un UUID hex de 32; resuelve los tokens antes de encolar');
        }

        const requestedNodes = item.nodeIds ?? [];
        const nodeIds = requestedNodes
            .map(normalizeUuid)
            .filter((x): x is string => x !== null);
        // Si se pidió una selección y ningún id es válido, la tarea terminaría con
        // éxito sin descargar nada. Preferimos fallar aquí.
        if (requestedNodes.length > 0 && nodeIds.length === 0) {
            throw new Error(`ningún nodeId válido para el canal ${channelId}`);
        }

        const excludeNodeIds = (item.excludeNodeIds ?? [])
            .map(normalizeUuid)
            .filter((x): x is string => x !== null);

        parsed.push({
            channelId,
            channelName: item.channelName,
            nodeIds,
            excludeNodeIds,
            allThumbnails: item.allThumbnails,
        });
    }
    if (parsed.length === 0) throw new Error('no channels requested');
    return parsed;
}

/** Nombre del canal según el propio dispositivo (proxy a Studio, cacheado 5 min).
 *  Devuelve null si no se puede resolver; el llamador usará el id como nombre. */
async function resolveChannelName(
    session: KolibriSession, channelId: string,
): Promise<string | null> {
    try {
        // Devuelve 503 {"status":"offline"} sin red; apiJson lanza y caemos a null.
        const data = await apiJson<Record<string, unknown> | Array<Record<string, unknown>>>(
            session, `/api/content/remotechannel/${channelId}/`, {}, 20000);
        const row = Array.isArray(data) ? data[0] : data;
        const name = row?.name;
        return typeof name === 'string' && name ? name : null;
    } catch {
        return null;
    }
}

/** Traduce un fallo de autenticación en un mensaje que dice qué hacer. */
function authErrorMessage(e: unknown): string {
    if (!(e instanceof KolibriAuthError)) {
        return `No se pudo autenticar contra Kolibri: ${e instanceof Error ? e.message : String(e)}`;
    }
    switch (e.reason) {
        case 'unreachable':
            return 'Kolibri no está disponible; reintenta cuando el servicio esté listo';
        case 'credentials':
            return 'Credenciales de Kolibri incorrectas: actualízalas en /credentials/kolibri';
        case 'permission':
            return e.message;
        default:
            return `No se pudo autenticar contra Kolibri: ${e.message}`;
    }
}

/** Contenedor mutable de la sesión: un import puede durar horas y sobrevivir a la
 *  caducidad de la sesión de Django, así que el poll necesita poder reemplazarla. */
interface SessionHolder { current: KolibriSession }

/** Reautentica in situ. Devuelve false si tampoco se puede ahora. */
async function reauthenticate(ctx: RunnerContext, holder: SessionHolder): Promise<boolean> {
    try {
        holder.current = await loginForContent();
        ctx.log('sesión caducada: reautenticado');
        return true;
    } catch (e) {
        ctx.log(`no se pudo reautenticar: ${authErrorMessage(e)}`);
        return false;
    }
}

const kolibriRunner: (ctx: RunnerContext) => Promise<void> = async (ctx) => {
    const parsed = parseItems(ctx.items);

    ctx.update({ phase: 'queued', percent: 0 });

    let holder: SessionHolder;
    try {
        holder = { current: await loginForContent() };
    } catch (e) {
        throw new Error(authErrorMessage(e));
    }

    // Prerrequisito de proot: 'importcontent' llama SIEMPRE a
    // lookup_channel_listing_status() → NetworkClient.discover_from_address(). Sin
    // una NetworkLocation cuyo base_url coincida con el origen, ese camino cae al
    // fallback que invoca ifaddr.get_adapters(), y netlink está bloqueado bajo proot.
    //
    // No abortamos si falla: puede que IIAB ya sembrara la fila 'reserved' y solo
    // fallara nuestra comprobación.
    const origin = await ensureContentOrigin(holder.current, STUDIO_URL);
    ctx.log(`origen de contenido (${STUDIO_URL}): ${origin}`);
    if (origin === 'failed') {
        ctx.log('AVISO: no se pudo garantizar la NetworkLocation del origen. Si el '
            + 'import falla al resolver el origen, ésta es la causa probable.');
    }

    let index = 0;
    for (const item of parsed) {
        ctx.throwIfCanceled();
        index++;

        const channelName = item.channelName
            || await resolveChannelName(holder.current, item.channelId)
            || item.channelId;

        const label = parsed.length > 1
            ? `${channelName} (${index}/${parsed.length})`
            : channelName;

        ctx.update({ phase: 'queued', detail: label });
        ctx.log(`encolando import de ${item.channelId} — ${channelName}`);

        const payload = buildTaskPayload({
            channelId: item.channelId,
            channelName,
            nodeIds: item.nodeIds,
            excludeNodeIds: item.excludeNodeIds,
            // Solo cuando hay selección parcial: ahí las miniaturas de los topics no
            // seleccionados no vendrían y la navegación quedaría con huecos. En un
            // canal completo ya vienen, así que pedirlas solo añade descarga.
            allThumbnails: item.allThumbnails ?? item.nodeIds.length > 0,
        });

        const created = await apiJson<KolibriJob | KolibriJob[]>(
            holder.current, '/api/tasks/tasks/',
            { method: 'POST', body: JSON.stringify(payload) }, 30000);
        const kolibriJob = Array.isArray(created) ? created[0] : created;
        if (!kolibriJob?.id) throw new Error('Kolibri no devolvió un id de job');
        ctx.log(`job de Kolibri: ${kolibriJob.id}`);

        await pollKolibriJob(ctx, holder, kolibriJob.id, label, index, parsed.length);
    }

    ctx.update({ phase: 'done', percent: 100, detail: null });
};

/** Sigue un job de Kolibri hasta su estado terminal, reflejando el progreso. */
async function pollKolibriJob(
    ctx: RunnerContext,
    holder: SessionHolder,
    kolibriJobId: string,
    label: string,
    index: number,
    total: number,
): Promise<void> {
    const started = Date.now();
    let lastBytes = 0;
    let lastSampleAt = Date.now();
    let lastProgressAt = Date.now();
    let taken = false;
    let reauths = 0;

    const cancelInKolibri = async (): Promise<void> => {
        try {
            await apiFetch(holder.current, `/api/tasks/tasks/${kolibriJobId}/cancel/`,
                { method: 'POST', body: '{}' });
        } catch { /* mejor esfuerzo */ }
    };

    const clearInKolibri = async (): Promise<void> => {
        // OJO: DELETE /api/tasks/tasks/<id>/ devuelve 405 — el viewset define
        // delete() pero no destroy(), así que el router no lo enruta.
        try {
            await apiFetch(holder.current, `/api/tasks/tasks/${kolibriJobId}/clear/`,
                { method: 'POST', body: '{}' });
        } catch { /* mejor esfuerzo */ }
    };

    for (;;) {
        if (ctx.isCanceled()) {
            // Cancelar también en Kolibri: si no, seguiría descargando en segundo
            // plano aunque nuestro job ya figure como cancelado.
            await cancelInKolibri();
            ctx.log(`job ${kolibriJobId} cancelado en Kolibri`);
            throw new CanceledError();
        }

        let job: KolibriJob;
        try {
            job = await apiJson<KolibriJob>(holder.current, `/api/tasks/tasks/${kolibriJobId}/`);
        } catch (e) {
            // Sesión caducada: un import puede durar horas y sobrevivir a la sesión
            // de Django. Reautenticar y seguir, en lugar de perder un job que
            // Kolibri probablemente esté completando.
            if (e instanceof KolibriApiError && e.isAuthExpired && reauths < MAX_REAUTH) {
                reauths++;
                if (await reauthenticate(ctx, holder)) { continue; }
            }
            // Un fallo puntual de polling no debe matar el job: Kolibri sigue
            // trabajando. Solo abortamos si persiste más allá del stall timeout.
            if (Date.now() - lastProgressAt > STALL_TIMEOUT_MS) {
                throw new Error(`el polling de ${kolibriJobId} falló de forma sostenida: `
                    + (e instanceof Error ? e.message : String(e)));
            }
            await sleep(POLL_MS);
            continue;
        }

        const meta = job.extra_metadata ?? {};
        const now = Date.now();

        const patch: JobUpdate = {
            phase: mapPhase(job.status),
            detail: total > 1 ? label : (meta.channel_name || label),
        };
        const local = mapPercent(job.percentage);
        if (local >= 0) patch.percent = overallPercent(index, total, local);

        const bytes = meta.transferred_file_size ?? 0;
        const speed = sampleSpeed(lastBytes, bytes, lastSampleAt, now);
        if (speed !== null) {
            patch.speed = speed;
            lastBytes = bytes;
            lastSampleAt = now;
            lastProgressAt = now;
        }
        ctx.update(patch);

        // La gracia de cola solo aplica ANTES de que un worker tome el job. Si ya
        // corrió y volvió a QUEUED, es el reintento propio de Kolibri
        // (enqueue_args.max_retries) y es legítimo: no lo confundimos con workers
        // caídos.
        if (PRE_RUN_STATES.has(job.status) && !taken) {
            if (now - started > QUEUED_GRACE_MS) {
                throw new Error(
                    `El job ${kolibriJobId} sigue en ${job.status} tras `
                    + `${Math.round((now - started) / 1000)} s: los workers de Kolibri no `
                    + 'parecen estar activos (kolibri start los incluye; kolibri services '
                    + 'los arranca sin HTTP).');
            }
        } else if (!taken) {
            // Acaba de pasar a RUNNING: reiniciamos el reloj de estancamiento para
            // no contar el tiempo que estuvo en cola.
            taken = true;
            lastProgressAt = now;
        }

        if (job.status === 'RUNNING' && now - lastProgressAt > STALL_TIMEOUT_MS) {
            await cancelInKolibri();
            throw new Error(
                `Sin avance durante ${humanMs(STALL_TIMEOUT_MS)}: Kolibri está `
                + 'reintentando la descarga en bucle (red inestable). Job cancelado; '
                + 'al reintentar se reanuda donde quedó.');
        }

        if (TERMINAL_STATES.has(job.status)) {
            // Se limpia en TODOS los caminos terminales, no solo en el éxito: si no,
            // los jobs fallidos se acumulan en la cola de Kolibri.
            if (job.status === 'FAILED') {
                const detail = job.exception || 'sin detalle';
                ctx.log(`FALLO en Kolibri: ${detail}`);
                if (job.traceback) ctx.log(job.traceback.split('\n').slice(-6).join('\n'));
                await clearInKolibri();
                throw new Error(`Kolibri falló importando ${label}: ${detail}`);
            }
            if (job.status === 'CANCELED') {
                await clearInKolibri();
                throw new CanceledError();
            }

            // COMPLETED no garantiza que se haya descargado algo: con node_ids
            // inexistentes la tarea termina bien sin transferir un byte.
            const expected = meta.total_resources ?? 0;
            const got = meta.transferred_resources ?? 0;
            const mb = Math.round((meta.transferred_file_size ?? 0) / 1048576);
            ctx.log(`completado: ${got}/${expected} recursos, ${mb} MB`);
            await clearInKolibri();
            if (expected > 0 && got === 0) {
                throw new Error(
                    `Kolibri terminó sin transferir nada de ${label}: revisa los nodeIds. `
                    + 'Una selección vacía termina con éxito y sin contenido.');
            }
            return;
        }

        await sleep(POLL_MS);
    }
}

jobs.registerRunner('kolibri', kolibriRunner);

export { kolibriRunner };
