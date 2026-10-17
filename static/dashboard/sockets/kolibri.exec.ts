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
import { jobs, RunnerContext, CanceledError, JobUpdate, throwIfStopped } from './jobs';
import {
    loginForContent, apiJson, apiFetch, ensureContentOrigin,
    KolibriSession, KolibriAuthError, KolibriApiError, STUDIO_URL,
} from './kolibri.session';
import {
    buildTaskPayload, failureMessage, importOutcome, isSameImport, mapPercent, mapPhase,
    normalizeUuid, overallPercent, sampleSpeed, PRE_RUN_STATES, TERMINAL_STATES,
} from './kolibri.map';
import type { ImportResult, KolibriTaskRow } from './kolibri.map';
import { selectionStatus } from './kolibri.query';

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

/** K2GO-475: how long a job waits for Kolibri to come up. After a box restart the
 *  engine relaunches the job (and the app re-drains) while Kolibri is still starting;
 *  failing at once would lose the order. */
const READY_WAIT_MS = envMs('K2GO_KOLIBRI_READY_WAIT_MS', 10 * 60_000);
const READY_POLL_MS = envMs('K2GO_KOLIBRI_READY_POLL_MS', 5000);

/** Kolibri tasks a live runner of this process is polling. Not adopted by another job:
 *  two jobs sharing one task would cancel or clear it under each other. Empty after a
 *  restart, which is when adoption is needed. */
const polled = new Set<string>();

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

interface KolibriJob extends KolibriTaskRow {
    id: string;
    status: string;
    percentage: number | null;
    exception: string | null;
    traceback: string | null;
    extra_metadata?: {
        channel_id?: string;
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

/** Logs in, waiting while Kolibri is unreachable (still starting). Other auth
 *  failures, or a wait past READY_WAIT_MS, fail with the actionable message. */
async function loginWhenReady(ctx: RunnerContext): Promise<KolibriSession> {
    const deadline = Date.now() + READY_WAIT_MS;
    let logged = false;
    for (;;) {
        throwIfStopped(ctx);
        try {
            return await loginForContent();
        } catch (e) {
            const starting = e instanceof KolibriAuthError && e.reason === 'unreachable';
            if (!starting || Date.now() >= deadline) throw new Error(authErrorMessage(e));
            if (!logged) {
                ctx.log('Kolibri is not up yet: waiting for it');
                logged = true;
            }
            await stoppableSleep(ctx, READY_POLL_MS);
        }
    }
}

/** sleep() that ends early when the job is paused or canceled. */
function stoppableSleep(ctx: RunnerContext, ms: number): Promise<void> {
    return new Promise((resolve) => {
        const onAbort = () => { clearTimeout(t); resolve(); };
        // Removed on timeout too: one listener per wait would pile up on the job's signal.
        const t = setTimeout(() => { ctx.signal.removeEventListener('abort', onAbort); resolve(); }, ms);
        ctx.signal.addEventListener('abort', onAbort, { once: true });
    });
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

    const holder: SessionHolder = { current: await loginWhenReady(ctx) };

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

    const total: ImportResult = { requested: 0, available: 0, notFound: 0 };
    let index = 0;
    let failure = '';
    for (const item of parsed) {
        throwIfStopped(ctx);
        index++;

        const channelName = item.channelName
            || await resolveChannelName(holder.current, item.channelId)
            || item.channelId;

        const label = parsed.length > 1
            ? `${channelName} (${index}/${parsed.length})`
            : channelName;

        ctx.update({ phase: 'queued', detail: label });

        const kolibriJobId = await findRunningImport(holder.current, item)
            ?? await queueImport(ctx, holder.current, item, channelName);
        ctx.log(`Kolibri job: ${kolibriJobId}`);

        await pollKolibriJob(ctx, holder, kolibriJobId, label, index, parsed.length);

        let r: ImportResult;
        try {
            r = selectionStatus(item.channelId, item.nodeIds, item.excludeNodeIds);
        } catch (e) {
            // Retrying is cheap: Kolibri skips the files it already has.
            throw new Error(`Could not verify the import of ${label}: `
                + (e instanceof Error ? e.message : String(e)));
        }
        ctx.log(`verified: ${r.available}/${r.requested} resources available, `
            + `${r.notFound} requested node(s) not in the channel`);
        if (importOutcome(r) === 'failed') {
            failure = r.requested === 0
                ? `The selected content of ${label} is no longer in the channel`
                : `Kolibri finished ${label} but none of its ${r.requested} resources is available`;
            ctx.log(failure);
        }
        total.requested += r.requested;
        total.available += r.available;
        total.notFound += r.notFound;
    }
    // One empty channel in a multi-channel job is a partial result, not a failed job.
    if (importOutcome(total) === 'failed') throw new Error(failure);

    // The engine writes 'done'; this records what 'done' delivered.
    ctx.update({
        detail: null,
        result: JSON.stringify({ outcome: importOutcome(total), ...total }),
    });
};

/**
 * The id of an import for this item that Kolibri is already running, or null.
 * After a dashboard restart the engine relaunches this runner; adopting the running
 * task avoids a second import of the same selection.
 */
async function findRunningImport(session: KolibriSession, item: ParsedItem): Promise<string | null> {
    try {
        const tasks = await apiJson<KolibriJob[]>(session, '/api/tasks/tasks/', {}, 30000);
        const hit = (Array.isArray(tasks) ? tasks : []).find((t) => !polled.has(t.id)
            && isSameImport(t, item.channelId, item.nodeIds, item.excludeNodeIds));
        return hit?.id ?? null;
    } catch {
        return null;   // Listing is an optimisation; queueing a new task is always correct.
    }
}

async function queueImport(ctx: RunnerContext, session: KolibriSession,
                           item: ParsedItem, channelName: string): Promise<string> {
    ctx.log(`queueing import of ${item.channelId}: ${channelName}`);
    const payload = buildTaskPayload({
        channelId: item.channelId,
        channelName,
        nodeIds: item.nodeIds,
        excludeNodeIds: item.excludeNodeIds,
        allThumbnails: item.allThumbnails === true,
    });
    const created = await apiJson<KolibriJob | KolibriJob[]>(
        session, '/api/tasks/tasks/',
        { method: 'POST', body: JSON.stringify(payload) }, 30000);
    const kolibriJob = Array.isArray(created) ? created[0] : created;
    if (!kolibriJob?.id) throw new Error('Kolibri did not return a job id');
    return kolibriJob.id;
}

/**
 * Cancels a Kolibri task. apiFetch does not throw on an HTTP error, so the status is
 * checked: after a long import the session has often expired, and a silent 403 would
 * leave Kolibri downloading behind a stopped job.
 */
async function cancelInKolibri(ctx: RunnerContext, holder: SessionHolder,
                               kolibriJobId: string): Promise<void> {
    const post = () => apiFetch(holder.current, `/api/tasks/tasks/${kolibriJobId}/cancel/`,
        { method: 'POST', body: '{}' });
    try {
        let res = await post();
        if ((res.status === 401 || res.status === 403) && await reauthenticate(ctx, holder)) {
            res = await post();
        }
        ctx.log(res.ok
            ? `job ${kolibriJobId} canceled in Kolibri`
            : `could not cancel job ${kolibriJobId} in Kolibri: HTTP ${res.status}`);
    } catch (e) {
        ctx.log(`could not cancel job ${kolibriJobId} in Kolibri: `
            + (e instanceof Error ? e.message : String(e)));
    }
}

/**
 * Follows a Kolibri job to COMPLETED, mirroring the progress. Throws on any other end.
 *
 * Whatever way this returns or throws, the Kolibri task is left finished and cleared:
 * a stop, a stall or a lost poll cancels it, so it never keeps downloading behind a
 * job that is no longer running. A pause is a cancel too: resume() queues the import
 * again, and Kolibri skips the files it already has.
 */
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
    let finished = false;   // Kolibri reported a terminal state

    polled.add(kolibriJobId);
    try {
        for (;;) {
            throwIfStopped(ctx);

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
                throw new Error(
                    `No progress for ${humanMs(STALL_TIMEOUT_MS)}: Kolibri is `
                    + 'retrying the download in a loop (unstable network). Job canceled; '
                    + 'on retry it resumes where it left off.');
            }

            if (TERMINAL_STATES.has(job.status)) {
                finished = true;
                if (job.status === 'FAILED') {
                    // job.exception is only the class name: a bad channel id reports
                    // a bare "HTTPError". failureMessage digs the real line out of the
                    // traceback so the operator is told what actually went wrong.
                    const detail = failureMessage(job.exception, job.traceback);
                    ctx.log(`FAILURE in Kolibri: ${detail}`);
                    if (job.traceback) ctx.log(job.traceback.split('\n').slice(-6).join('\n'));
                    throw new Error(`Kolibri failed importing ${label}: ${detail}`);
                }
                if (job.status === 'CANCELED') throw new CanceledError();
                const mb = Math.round((meta.transferred_file_size ?? 0) / 1048576);
                ctx.log(`completed: ${mb} MB transferred`);
                return;
            }

            await sleep(POLL_MS);
        }
    } finally {
        polled.delete(kolibriJobId);
        if (!finished) await cancelInKolibri(ctx, holder, kolibriJobId);
        // CAREFUL: DELETE /api/tasks/tasks/<id>/ returns 405: the viewset defines
        // delete() but not destroy(), so the router does not route it. Clearing a task
        // that is still CANCELING fails; that row is left for Kolibri's own cleanup.
        try {
            await apiFetch(holder.current, `/api/tasks/tasks/${kolibriJobId}/clear/`,
                { method: 'POST', body: '{}' });
        } catch { /* best effort */ }
    }
}

jobs.registerRunner('kolibri', kolibriRunner);

export { kolibriRunner };
