// sockets/kolibri.exec.ts — Kolibri runner for the durable jobs engine
//
// Seeds content into Kolibri by queueing tasks on ITS REST API and polling.
// Unlike kiwix/maps, no binary is spawned here: the work is done by Kolibri
// itself, which is already running inside the proot.
//
// Concrete advantage of this route over invoking the CLI: Kolibri reports
// structured progress (percentage, transferred_file_size, total_resources). Its
// CLI reports nothing parseable — it uses click.progressbar with no label, and in
// non-TTY mode the output of a full import is literally one empty line.
//
// A job item is:
//   { channelId, channelName?, nodeIds?, excludeNodeIds?, allThumbnails? }
// They are processed in SEQUENCE: two simultaneous imports only create contention
// on the same db.sqlite3.
//
// PRECONDITION: the startup layer guarantees Kolibri is alive and its workers
// active. The runner checks it anyway and fails with an actionable message
// instead of hanging indefinitely.
import { jobs, RunnerContext, CanceledError, JobUpdate } from './jobs';
import {
    loginForContent, apiJson, apiFetch, ensureContentOrigin,
    KolibriSession, KolibriAuthError, KolibriApiError, STUDIO_URL,
} from './kolibri.session';
import {
    buildTaskPayload, failureMessage, mapPercent, mapPhase, normalizeUuid,
    overallPercent, sampleSpeed, PRE_RUN_STATES, TERMINAL_STATES,
} from './kolibri.map';

/** Positive integers from the environment, with a default. Adjustable in
 *  operation (very slow networks) and in tests. */
function envMs(name: string, def: number): number {
    const raw = process.env[name];
    if (!raw) return def;
    const n = Number(raw);
    return Number.isFinite(n) && n > 0 ? n : def;
}

const POLL_MS = envMs('K2GO_KOLIBRI_POLL_MS', 2000);

/** If a job is still not picked up after this, the workers are almost certainly
 *  not alive: queueing only writes a row in job_storage.sqlite3; without a
 *  WorkerSupervisor nobody runs it and the job stays QUEUED forever. */
const QUEUED_GRACE_MS = envMs('K2GO_KOLIBRI_QUEUED_GRACE_MS', 90_000);

/** With no byte progress for this long, Kolibri's transport is in its retry
 *  loop —which has NO limit: it waits 30 s and retries on ConnectionError,
 *  Timeout and HTTP 502/503/504/521-524—. The state would stay in RUNNING
 *  forever, so we cut it off. Retrying is safe and cheap: downloads resume
 *  over HTTP Range. */
const STALL_TIMEOUT_MS = envMs('K2GO_KOLIBRI_STALL_MS', 15 * 60_000);

/** Login retries per job. Bounded so a credential revoked halfway through the
 *  import does not produce a loop of failed logins. */
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

/** Readable duration: avoids "0 min" when the threshold is lowered for tests. */
function humanMs(ms: number): string {
    return ms < 60_000
        ? `${Math.round(ms / 1000)} s`
        : `${Math.round(ms / 60_000)} min`;
}

/** Validates and normalises the job items. Fails loudly: better to reject at
 *  queue time than to download zero bytes with apparent success. */
export function parseItems(rawItems: unknown[]): ParsedItem[] {
    const parsed: ParsedItem[] = [];
    for (const raw of rawItems) {
        // For convenience a bare channelId is also accepted, as kiwix does.
        const item: KolibriItem = typeof raw === 'string'
            ? { channelId: raw }
            : ((raw ?? {}) as KolibriItem);

        const channelId = normalizeUuid(item.channelId);
        if (!channelId) {
            throw new Error(`invalid channelId: ${String(item.channelId)} — a 32-char `
                + 'hex UUID is expected; resolve the tokens before queueing');
        }

        const requestedNodes = item.nodeIds ?? [];
        const nodeIds = requestedNodes
            .map(normalizeUuid)
            .filter((x): x is string => x !== null);
        // If a selection was requested and no id is valid, the task would finish
        // successfully without downloading anything. We prefer to fail here.
        if (requestedNodes.length > 0 && nodeIds.length === 0) {
            throw new Error(`no valid nodeId for channel ${channelId}`);
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

/** Channel name according to the device itself (proxy to Studio, cached 5 min).
 *  Returns null if it cannot be resolved; the caller will use the id as name. */
async function resolveChannelName(
    session: KolibriSession, channelId: string,
): Promise<string | null> {
    try {
        // With no network it returns 503 {"status":"offline"}; apiJson throws, so null.
        const data = await apiJson<Record<string, unknown> | Array<Record<string, unknown>>>(
            session, `/api/content/remotechannel/${channelId}/`, {}, 20000);
        const row = Array.isArray(data) ? data[0] : data;
        const name = row?.name;
        return typeof name === 'string' && name ? name : null;
    } catch {
        return null;
    }
}

/** Turns an authentication failure into a message that says what to do. */
function authErrorMessage(e: unknown): string {
    if (!(e instanceof KolibriAuthError)) {
        return `Could not authenticate against Kolibri: ${e instanceof Error ? e.message : String(e)}`;
    }
    switch (e.reason) {
        case 'unreachable':
            return 'Kolibri is not available; retry when the service is ready';
        case 'credentials':
            return 'Wrong Kolibri credentials: update them at /credentials/kolibri';
        case 'permission':
            return e.message;
        default:
            return `Could not authenticate against Kolibri: ${e.message}`;
    }
}

/** Mutable session container: an import can run for hours and outlive the Django
 *  session expiry, so the poll needs to be able to replace it. */
interface SessionHolder { current: KolibriSession }

/** Re-authenticates in place. Returns false if it cannot be done now either. */
async function reauthenticate(ctx: RunnerContext, holder: SessionHolder): Promise<boolean> {
    try {
        holder.current = await loginForContent();
        ctx.log('session expired: re-authenticated');
        return true;
    } catch (e) {
        ctx.log(`could not re-authenticate: ${authErrorMessage(e)}`);
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

    // proot prerequisite: 'importcontent' ALWAYS calls
    // lookup_channel_listing_status() → NetworkClient.discover_from_address(). With
    // no NetworkLocation whose base_url matches the origin, that path falls to the
    // fallback that calls ifaddr.get_adapters(), and netlink is blocked under proot.
    //
    // We do not abort on failure: IIAB may already have seeded the 'reserved' row
    // and only our check failed.
    const origin = await ensureContentOrigin(holder.current, STUDIO_URL);
    ctx.log(`content origin (${STUDIO_URL}): ${origin}`);
    if (origin === 'failed') {
        ctx.log('WARNING: could not ensure the NetworkLocation for the origin. If the '
            + 'import fails to resolve the origin, this is the likely cause.');
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
        ctx.log(`queueing import of ${item.channelId} — ${channelName}`);

        const payload = buildTaskPayload({
            channelId: item.channelId,
            channelName,
            nodeIds: item.nodeIds,
            excludeNodeIds: item.excludeNodeIds,
            // Only on a partial selection: there the thumbnails of the unselected
            // topics would not come and the browse would have gaps. On a full
            // channel they already come, so asking for them only adds download.
            allThumbnails: item.allThumbnails ?? item.nodeIds.length > 0,
        });

        const created = await apiJson<KolibriJob | KolibriJob[]>(
            holder.current, '/api/tasks/tasks/',
            { method: 'POST', body: JSON.stringify(payload) }, 30000);
        const kolibriJob = Array.isArray(created) ? created[0] : created;
        if (!kolibriJob?.id) throw new Error('Kolibri did not return a job id');
        ctx.log(`Kolibri job: ${kolibriJob.id}`);

        await pollKolibriJob(ctx, holder, kolibriJob.id, label, index, parsed.length);
    }

    ctx.update({ phase: 'done', percent: 100, detail: null });
};

/** Follows a Kolibri job to its terminal state, mirroring the progress. */
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
        } catch { /* best effort */ }
    };

    const clearInKolibri = async (): Promise<void> => {
        // CAREFUL: DELETE /api/tasks/tasks/<id>/ returns 405 — the viewset defines
        // delete() but not destroy(), so the router does not route it.
        try {
            await apiFetch(holder.current, `/api/tasks/tasks/${kolibriJobId}/clear/`,
                { method: 'POST', body: '{}' });
        } catch { /* best effort */ }
    };

    for (;;) {
        if (ctx.isCanceled()) {
            // Cancel in Kolibri too: otherwise it would keep downloading in the
            // background even though our job already shows as canceled.
            await cancelInKolibri();
            ctx.log(`job ${kolibriJobId} canceled in Kolibri`);
            throw new CanceledError();
        }

        let job: KolibriJob;
        try {
            job = await apiJson<KolibriJob>(holder.current, `/api/tasks/tasks/${kolibriJobId}/`);
        } catch (e) {
            // Expired session: an import can run for hours and outlive the Django
            // session. Re-authenticate and carry on, instead of losing a job that
            // Kolibri is probably completing.
            if (e instanceof KolibriApiError && e.isAuthExpired && reauths < MAX_REAUTH) {
                reauths++;
                if (await reauthenticate(ctx, holder)) { continue; }
            }
            // A one-off polling failure must not kill the job: Kolibri keeps
            // working. We only abort if it persists beyond the stall timeout.
            if (Date.now() - lastProgressAt > STALL_TIMEOUT_MS) {
                throw new Error(`polling of ${kolibriJobId} failed persistently: `
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

        // The queued grace only applies BEFORE a worker takes the job. If it has
        // already run and went back to QUEUED, that is Kolibri's own retry
        // (enqueue_args.max_retries) and it is legitimate: we do not confuse it
        // with dead workers.
        if (PRE_RUN_STATES.has(job.status) && !taken) {
            if (now - started > QUEUED_GRACE_MS) {
                throw new Error(
                    `Job ${kolibriJobId} is still in ${job.status} after `
                    + `${Math.round((now - started) / 1000)} s: the Kolibri workers do `
                    + 'not seem to be active (kolibri start includes them; kolibri services '
                    + 'starts them without HTTP).');
            }
        } else if (!taken) {
            // It has just moved to RUNNING: we reset the stall clock so as not to
            // count the time it spent queued.
            taken = true;
            lastProgressAt = now;
        }

        if (job.status === 'RUNNING' && now - lastProgressAt > STALL_TIMEOUT_MS) {
            await cancelInKolibri();
            throw new Error(
                `No progress for ${humanMs(STALL_TIMEOUT_MS)}: Kolibri is `
                + 'retrying the download in a loop (unstable network). Job canceled; '
                + 'on retry it resumes where it left off.');
        }

        if (TERMINAL_STATES.has(job.status)) {
            // Cleared on ALL terminal paths, not only on success: otherwise the
            // failed jobs pile up in Kolibri's queue.
            if (job.status === 'FAILED') {
                // job.exception is only the class name — a bad channel id reports
                // a bare "HTTPError". failureMessage digs the real line out of the
                // traceback so the operator is told what actually went wrong.
                const detail = failureMessage(job.exception, job.traceback);
                ctx.log(`FAILURE in Kolibri: ${detail}`);
                if (job.traceback) ctx.log(job.traceback.split('\n').slice(-6).join('\n'));
                await clearInKolibri();
                throw new Error(`Kolibri failed importing ${label}: ${detail}`);
            }
            if (job.status === 'CANCELED') {
                await clearInKolibri();
                throw new CanceledError();
            }

            // COMPLETED does NOT guarantee anything was downloaded: with
            // non-existent node_ids the task ends fine without transferring a byte.
            const expected = meta.total_resources ?? 0;
            const got = meta.transferred_resources ?? 0;
            const mb = Math.round((meta.transferred_file_size ?? 0) / 1048576);
            ctx.log(`completed: ${got}/${expected} resources, ${mb} MB`);
            await clearInKolibri();
            if (expected > 0 && got === 0) {
                throw new Error(
                    `Kolibri finished without transferring anything of ${label}: check the nodeIds. `
                    + 'An empty selection finishes successfully and with no content.');
            }
            return;
        }

        await sleep(POLL_MS);
    }
}

jobs.registerRunner('kolibri', kolibriRunner);

export { kolibriRunner };
