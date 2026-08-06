// routes.ts — ADFA-4838
//
// REST surface over the durable job engine. Short, stateless calls the app (and the
// web UI) use instead of a long-lived socket: start a job, poll its structured status,
// cancel it. The job itself lives in the dashboard process (see sockets/jobs.ts), so
// none of these calls hold state — a client can drop and re-attach by polling the id.
import express, { Router, Request, Response } from 'express';
import { spawn } from 'child_process';
import fs from 'fs';
import path from 'path';
import { jobs, Job, JobType } from './sockets/jobs';
import { searchCatalog, listLibrary, removeBook, listLanguages, getCalibreSession } from './sockets/books.query';
import { parseBox, parseEstimate } from './sockets/maps.socket';
import {
    preflight, listInstalledChannels, browseRemoteChannels, resolveIdentifier,
    browseChannelTree, estimateSelection, deleteChannel, getKolibriTask, verifyCredentials,
} from './sockets/kolibri.query';
import { checkReadiness, KolibriAuthError, KolibriApiError, login as kolibriLogin } from './sockets/kolibri.session';
import {
    describeCredential, setCredential, clearCredential, isServiceName,
} from './sockets/credentials';

// ADFA-4879: FQR helpers reached from the app (in-app region download/delete instead of the
// copy-paste-into-a-terminal flow). tile-extract.py is installed on the box by the upstream maps
// role and is NOT modified here — we only talk to its existing CLI (same trusted binary
// maps.exec.ts already spawns): `extract` (interactive, for the estimate) and `delete`.
const MAPS_SCRIPT = '/opt/iiab/maps/tile-extract/tile-extract.py';
const MAPS_NAME_RE = /^[A-Za-z0-9_-]{1,34}$/;

// ADFA-5004: Kiwix ZIM management (list + delete), reached from the app so users can free space
// without the retired web dashboard. ZIMS_DIR/KIWIX_INDEXER mirror sockets/kiwix.exec.ts — keep in
// sync. ZIM_NAME_RE only ever matches a plain "<file>.zim" (no path separators), so a delete can
// never escape ZIMS_DIR.
const ZIMS_DIR = '/library/zims/content/';
const KIWIX_INDEXER = '/usr/bin/iiab-make-kiwix-lib';
const ZIM_NAME_RE = /^[A-Za-z0-9._-]{1,150}\.zim$/;

const VALID_TYPES: JobType[] = ['kiwix', 'maps', 'books', 'kolibri'];
function isType(t: string): t is JobType {
    return (VALID_TYPES as string[]).includes(t);
}

/** Public shape returned to clients: ids as an array, no raw JSON column. */
function toApi(job: Job) {
    let ids: string[] = [];
    try { ids = JSON.parse(job.target) as string[]; } catch { ids = []; }
    return {
        id: job.id,
        type: job.type,
        ids,
        phase: job.phase,
        percent: job.percent,
        speed: job.speed,
        detail: job.detail,
        error: job.error,
        updated: job.updated,
    };
}

export const apiRouter: Router = express.Router();

// --- Books: direct (non-job) queries over the offline catalog + Calibre-Web library ---------
// ADFA-4850. Ported from the socket.io handlers; the download itself stays a durable job
// (POST /books/download). These paths don't collide with the generic /:type/* routes below.

// Search the offline Gutenberg catalog. ?q= (FTS) | ?filter=educational | (default) top-by-downloads.
apiRouter.get('/books/search', (req: Request, res: Response): void => {
    try {
        const q = String(req.query.q ?? '');
        const filter = String(req.query.filter ?? '');
        const lang = String(req.query.lang ?? '');
        const limit = parseInt(String(req.query.limit ?? '40'), 10);
        res.json(searchCatalog(q, filter, lang, limit));
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'search failed' });
    }
});

// The distinct languages present in the catalog (for the language picker).
apiRouter.get('/books/languages', (_req: Request, res: Response): void => {
    try {
        res.json(listLanguages());
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'languages read failed' });
    }
});

// The local Calibre-Web library (EPUB books) — for "Your books" / Read a Book.
apiRouter.get('/books/library', (_req: Request, res: Response): void => {
    try {
        res.json(listLibrary());
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'library read failed' });
    }
});

// Remove a book from the Calibre-Web library.
apiRouter.post('/books/library/:id/remove', async (req: Request, res: Response): Promise<void> => {
    const id = parseInt(String(req.params.id), 10);
    if (!Number.isFinite(id)) { res.status(400).json({ error: 'bad id' }); return; }
    try {
        await removeBook(id);
        res.json({ ok: true });
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'remove failed' });
    }
});

// --- Maps FQR: estimate + delete (ADFA-4879) --------------------------------------------------
// These are direct (non-job) ops. The download itself stays a durable job (POST /maps/download).
// Declared before the generic /:type/* routes so the literal paths win.

// Size estimate for a region, for the in-app consent step: { transfer, archive, free, free_after }
// (bytes). We do NOT modify tile-extract.py — we drive its existing INTERACTIVE `extract`, read the
// size prompt it prints (parseEstimate), then abort WITHOUT downloading. Hardened default-safe: we
// close stdin immediately, so the script's confirm `input()` raises EOFError and aborts on its own
// even if the prompt wording changes — the estimate is printed before that input(), so we still
// capture it. Needs connectivity + a moment (pmtiles --dry-run).
const MAPS_ESTIMATE_TIMEOUT_MS = 60000;
apiRouter.post('/maps/estimate', (req: Request, res: Response): void => {
    const parsed = parseBox(String((req.body as { box?: unknown })?.box ?? ''));
    if (!parsed.ok) { res.status(400).json({ error: parsed.error }); return; }
    // A unique throwaway name so we never hit the "overwrite existing?" branch.
    const probeName = 'est_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    const p = spawn('sudo', [MAPS_SCRIPT, 'extract', probeName, parsed.box],
        { env: { ...process.env, PYTHONUNBUFFERED: '1' } });
    p.stdin?.end();   // never feed input => any confirm() aborts via EOFError; no download can start

    let out = '', err = '', done = false;
    const finish = (fn: () => void) => {
        if (done) return; done = true; clearTimeout(timer);
        try { p.kill('SIGKILL'); } catch { /* already gone */ }
        fn();
    };
    const timer = setTimeout(() =>
        finish(() => { if (!res.headersSent) res.status(504).json({ error: 'estimate timed out' }); }),
        MAPS_ESTIMATE_TIMEOUT_MS);
    const check = () => {
        if (done) return;
        if (/overlap/i.test(out) || /overlap/i.test(err)) {
            finish(() => { if (!res.headersSent) res.status(409).json({ error: 'overlaps an existing region' }); });
            return;
        }
        const est = parseEstimate(out);
        if (est) finish(() => { if (!res.headersSent) res.json({ ok: true, ...est }); });
    };
    p.stdout.on('data', (d: Buffer) => { out += d.toString(); check(); });
    p.stderr.on('data', (d: Buffer) => { err += d.toString(); check(); });
    p.on('error', (e) => finish(() => { if (!res.headersSent) res.status(500).json({ error: String(e) }); }));
    p.on('exit', () => finish(() => {
        if (res.headersSent) return;
        const est = parseEstimate(out);
        if (est) res.json({ ok: true, ...est });
        else res.status(500).json({ error: err.trim() || 'no size estimate in output', raw: out.slice(-200) });
    }));
});

// Delete a downloaded region. tile-extract.py's `delete` runs update-json itself.
apiRouter.post('/maps/delete', (req: Request, res: Response): void => {
    const name = String((req.body as { name?: unknown })?.name ?? '').trim();
    if (!MAPS_NAME_RE.test(name)) { res.status(400).json({ error: 'invalid region name' }); return; }
    const p = spawn('sudo', [MAPS_SCRIPT, 'delete', name],
        { env: { ...process.env, PYTHONUNBUFFERED: '1' } });
    let err = '';
    p.stderr.on('data', (d: Buffer) => { err += d.toString(); });
    p.on('error', (e) => { if (!res.headersSent) res.status(500).json({ error: String(e) }); });
    p.on('exit', (code) => {
        if (res.headersSent) return;
        if (code === 0) res.json({ ok: true });
        else res.status(500).json({ error: err.trim() || `delete exited ${code}` });
    });
});

// --- Kiwix: list installed ZIMs + delete (ADFA-5004) ------------------------------------------
// Direct (non-job) ops mirroring /maps/delete; the download itself stays a durable job
// (POST /kiwix/download). Ported from the retired dashboard socket handler (delete_zim): remove the
// .zim from disk, then rebuild the Kiwix library index so the deleted book stops being served.
// Declared before the generic /:type/* routes so these literal paths win.

// The ZIMs currently on disk (name + bytes), newest first. Drives the in-app manage list.
apiRouter.get('/kiwix/library', (_req: Request, res: Response): void => {
    try {
        if (!fs.existsSync(ZIMS_DIR)) { res.json([]); return; }
        const rows = fs.readdirSync(ZIMS_DIR)
            .filter((f) => f.endsWith('.zim'))
            .map((name) => {
                let bytes = 0, mtime = 0;
                try { const st = fs.statSync(path.join(ZIMS_DIR, name)); bytes = st.size; mtime = st.mtimeMs; }
                catch { /* file vanished between readdir and stat — skip */ }
                return { name, bytes, mtime };
            })
            .sort((a, b) => b.mtime - a.mtime);
        res.json(rows);
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'library read failed' });
    }
});

// Delete one installed ZIM, then rebuild the Kiwix index so kiwix-serve stops serving it.
apiRouter.post('/kiwix/delete', (req: Request, res: Response): void => {
    const name = String((req.body as { name?: unknown })?.name ?? '').trim();
    // Strict name + basename guard: only a plain "<file>.zim" inside ZIMS_DIR, never a path.
    if (!ZIM_NAME_RE.test(name) || name !== path.basename(name)) {
        res.status(400).json({ error: 'invalid zim name' }); return;
    }
    const filePath = path.join(ZIMS_DIR, name);
    if (!fs.existsSync(filePath)) { res.status(404).json({ error: 'not found' }); return; }
    try {
        fs.unlinkSync(filePath);
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'delete failed' }); return;
    }
    // No indexer on the box: the file is gone, report success without a reindex.
    if (!fs.existsSync(KIWIX_INDEXER)) { res.json({ ok: true, reindexed: false }); return; }
    // ADFA-5004: don't run a second indexer while a kiwix download/index job is in flight. The jobs
    // engine does NOT serialize (each runner is its own promise) and this delete runs outside it, so
    // spawning iiab-make-kiwix-lib now could race that job's own end-of-run reindex on library.xml
    // (and pick up an incomplete, still-downloading .zim). The file is already unlinked, so the
    // active job's reindex — which reads the current disk state — will reflect the deletion when it
    // finishes. See ADR-4832 (proot/index collisions).
    const kiwixBusy = jobs.list('kiwix').some((j) =>
        j.phase === 'queued' || j.phase === 'downloading' || j.phase === 'indexing' || j.phase === 'processing');
    if (kiwixBusy) { res.json({ ok: true, reindexed: false, deferred: true }); return; }
    // Rebuild the library index — the SAME step the download runner (kiwix.exec.ts) uses to make
    // content show up, so it also makes a deleted ZIM disappear (no kiwix-serve restart needed).
    // We MUST drain stdout/stderr: with the default piped stdio, a chatty indexer fills the ~64KB
    // pipe buffer and blocks forever (never exits, library never rebuilt) — exactly what left a
    // deleted ZIM still served. Its exit code is advisory (kiwix.exec.ts treats it the same), so the
    // unlink is the real signal; we respond when it finishes.
    const idx = spawn(KIWIX_INDEXER, [], { env: { ...process.env } });
    idx.stdout?.on('data', () => { /* drain so the indexer never blocks on a full pipe */ });
    idx.stderr?.on('data', () => { /* drain */ });
    idx.on('error', (e) => { if (!res.headersSent) res.status(500).json({ error: String(e) }); });
    idx.on('exit', () => { if (!res.headersSent) res.json({ ok: true, reindexed: true }); });
});

// --- System: dash-node version + self-rebuild (ADFA-5011) -------------------------------------
// The dashboard REST core can rebuild ITSELF from the on-device clone without a rootfs rebuild:
// git fetch+reset -> build in a staging dir -> smoke-test the staged build -> atomically swap it
// live only if it passes (tools/rebuild-dashboard.sh). The rebuild runs DETACHED (setsid) so
// restarting dash-node mid-run never kills it. Declared before the generic /:type/* routes.
const REBUILD_SCRIPT = '/opt/iiab-android/tools/rebuild-dashboard.sh';
const REBUILD_STATUS_FILE = '/var/run/dash-rebuild.status';

// Installed dash-node version (from package.json), so the module card can show it + compare.
apiRouter.get('/system/version', (_req: Request, res: Response): void => {
    try {
        const pkg = JSON.parse(fs.readFileSync(path.join(process.cwd(), 'package.json'), 'utf8'));
        res.json({ version: String(pkg.version || 'unknown') });
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'version read failed' });
    }
});

// Current rebuild state: idle | running | done | error (read from the status file the script writes).
apiRouter.get('/system/dashboard/rebuild/status', (_req: Request, res: Response): void => {
    let state = 'idle';
    try { state = (fs.readFileSync(REBUILD_STATUS_FILE, 'utf8').trim() || 'idle'); } catch { /* no file yet */ }
    res.json({ state });
});

// Trigger a rebuild. Fire-and-forget: launches the orchestrator DETACHED and returns 202 at once;
// the app then polls /system/version + RestReadiness until the API is back on the new version.
apiRouter.post('/system/dashboard/rebuild', (_req: Request, res: Response): void => {
    let running = false;
    try { running = fs.readFileSync(REBUILD_STATUS_FILE, 'utf8').trim() === 'running'; } catch { /* none */ }
    if (running) { res.status(409).json({ error: 'a rebuild is already running' }); return; }
    if (!fs.existsSync(REBUILD_SCRIPT)) { res.status(500).json({ error: 'rebuild script not found' }); return; }
    try {
        // setsid => own session, so `pdsm restart dash-node` inside the script can't kill this run.
        const child = spawn('setsid', ['sh', REBUILD_SCRIPT], { detached: true, stdio: 'ignore' });
        child.unref();
        res.status(202).json({ ok: true, state: 'running' });
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'could not start rebuild' });
    }
});

// ADFA-5026: live "is a newer dash-node available?" check. Unlike /system/version (which reads the
// installed package.json off disk, offline), this compares the installed version against the mainline
// remote: git fetch + read origin/main's static/dashboard/package.json. Everything stays in the rootfs
// clone and reuses its existing git auth (like the rebuild). Network-bound, so it runs with a timeout
// and returns 503 (not 500) when the remote can't be reached, letting the app keep its last-known state.
const UPDATE_CLONE_DIR = '/opt/iiab-android';
const UPDATE_BRANCH = 'main';
const UPDATE_PKG_IN_CLONE = 'static/dashboard/package.json';

/** Run git inside the on-device clone with a hard timeout; resolves stdout, rejects on non-zero/timeout. */
function gitInClone(args: string[], timeoutMs: number): Promise<string> {
    return new Promise((resolve, reject) => {
        const p = spawn('git', ['-C', UPDATE_CLONE_DIR, ...args], { stdio: ['ignore', 'pipe', 'pipe'] });
        let out = '', err = '';
        const timer = setTimeout(() => { p.kill('SIGKILL'); reject(new Error('git timed out')); }, timeoutMs);
        p.stdout.on('data', (d) => { out += d; });
        p.stderr.on('data', (d) => { err += d; });
        p.on('error', (e) => { clearTimeout(timer); reject(e); });
        p.on('close', (code) => {
            clearTimeout(timer);
            if (code === 0) resolve(out); else reject(new Error(err.trim() || ('git exited ' + code)));
        });
    });
}

/** True when {@code a} is a strictly newer semantic version than {@code b} (major.minor.patch).
 *  Pre-release/build suffixes are ignored (e.g. "1.1.0-beta" compares as "1.1.0") — fine for the
 *  plain x.y.z versions dash-node uses today; revisit if a suffixed tag is ever shipped. */
function versionGt(a: string, b: string): boolean {
    const pa = a.split('.').map((n) => parseInt(n, 10) || 0);
    const pb = b.split('.').map((n) => parseInt(n, 10) || 0);
    for (let i = 0; i < 3; i++) {
        const x = pa[i] || 0, y = pb[i] || 0;
        if (x !== y) return x > y;
    }
    return false;
}

apiRouter.get('/system/dashboard/update-check', async (_req: Request, res: Response): Promise<void> => {
    let installed = 'unknown';
    try {
        const pkg = JSON.parse(fs.readFileSync(path.join(process.cwd(), 'package.json'), 'utf8'));
        installed = String(pkg.version || 'unknown');
    } catch { /* leave 'unknown'; the remote check below still runs */ }

    // Don't fetch while a rebuild owns the clone (it runs `git reset --hard`): a concurrent fetch
    // could collide on git's locks. Report installed-only and let the app keep its last-known state.
    let rebuilding = false;
    try { rebuilding = fs.readFileSync(REBUILD_STATUS_FILE, 'utf8').trim() === 'running'; } catch { /* no file yet */ }
    if (rebuilding) {
        res.status(503).json({ installed, available: 'unknown', updateAvailable: false, error: 'rebuild in progress' });
        return;
    }

    try {
        await gitInClone(['fetch', '--quiet', 'origin', UPDATE_BRANCH], 20000);
        const remotePkg = await gitInClone(['show', `origin/${UPDATE_BRANCH}:${UPDATE_PKG_IN_CLONE}`], 10000);
        const available = String(JSON.parse(remotePkg).version || 'unknown');
        const updateAvailable = installed !== 'unknown' && available !== 'unknown' && versionGt(available, installed);
        res.json({ installed, available, updateAvailable });
    } catch (e: any) {
        // Offline / no remote / clone missing: not a server fault, and git stderr can echo the remote
        // URL (which may carry the clone's auth token). Keep the detail server-side; return a generic
        // reason. 503 so the app falls back to its last-known state rather than a hard error.
        console.error('[update-check] ' + (e?.message || e));
        res.status(503).json({ installed, available: 'unknown', updateAvailable: false,
            error: 'update check unavailable' });
    }
});

// --- Kolibri: readiness, catálogo y selección (ADFA-4949) -------------------------
// Consultas directas (no-job). La descarga en sí es un job durable
// (POST /kolibri/download), que sale gratis al añadir 'kolibri' a VALID_TYPES.
//
// Nota de puertos y prefijos: nginx expone Kolibri en /kolibri/ y además le enruta
// /api/, /content/, /device/ y /learn/ en el :8085 compartido. Nosotros hablamos
// con Kolibri directamente en 127.0.0.1:8009 desde dentro, así que no hay colisión.

/** Traduce un KolibriAuthError al status HTTP correcto. */
function authStatus(e: unknown): number {
    if (e instanceof KolibriAuthError) {
        switch (e.reason) {
            case 'unreachable': return 503;   // el servicio no está listo
            case 'credentials': return 401;
            case 'permission': return 403;
            default: return 502;
        }
    }
    // Kolibri respondió, pero con error: propagamos su semántica en lugar de un 500.
    if (e instanceof KolibriApiError) {
        if (e.status === 401 || e.status === 403) return e.status;
        return e.status >= 500 ? 502 : 502;   // upstream en mal estado
    }
    // fetch aborta por timeout (AbortError) o no conecta (TypeError). Eso es
    // "Kolibri no está listo", no "nuestro servidor se rompió": 503, no 500.
    if (e instanceof Error && (e.name === 'AbortError' || e.name === 'TimeoutError'
        || e instanceof TypeError)) {
        return 503;
    }
    return 500;
}

// El "gate" de arranque: mientras ready sea false, no tiene sentido lanzar jobs.
// Nunca lanza: siempre devuelve un diagnóstico con los bloqueadores en claro.
apiRouter.get('/kolibri/ready', async (_req: Request, res: Response): Promise<void> => {
    try {
        const readiness = await checkReadiness();
        // 200 siempre: es un endpoint de estado, no una operación. El cliente mira
        // el campo `ready`, no el status.
        res.json(readiness);
    } catch (e: any) {
        res.status(500).json({ ready: false, blockers: [e?.message || 'readiness failed'] });
    }
});

// Diagnóstico ampliado: readiness + estado local (canales, bytes en disco, rutas).
apiRouter.get('/kolibri/preflight', async (_req: Request, res: Response): Promise<void> => {
    try {
        res.json(await preflight());
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'preflight failed' });
    }
});

// Qué hay ya en el dispositivo. Lectura local en readonly: no necesita ni sesión
// ni que Kolibri esté arriba.
apiRouter.get('/kolibri/channels', (_req: Request, res: Response): void => {
    try {
        res.json(listInstalledChannels());
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'channel list failed' });
    }
});

// Catálogo remoto para el selector del wizard. ?keyword= y ?language= opcionales.
apiRouter.get('/kolibri/catalog', async (req: Request, res: Response): Promise<void> => {
    try {
        res.json(await browseRemoteChannels({
            keyword: req.query.keyword ? String(req.query.keyword) : undefined,
            language: req.query.language ? String(req.query.language) : undefined,
        }));
    } catch (e: any) {
        res.status(authStatus(e)).json({ error: e?.message || 'catalog fetch failed' });
    }
});

// Resuelve un token (xxxxx-xxxxx) o un UUID a su canal. El usuario copia tokens de
// Studio, pero las tareas exigen el hex de 32.
apiRouter.get('/kolibri/resolve/:identifier', async (req: Request, res: Response): Promise<void> => {
    try {
        res.json(await resolveIdentifier(String(req.params.identifier)));
    } catch (e: any) {
        const status = e instanceof KolibriAuthError ? authStatus(e) : 404;
        res.status(status).json({ error: e?.message || 'not found' });
    }
});

// Un nivel del árbol del canal, para elegir subárboles.
// PRECONDICIÓN: los metadatos del canal deben estar ya en la base local, o sea que
// hay que haber importado el canal antes. Es el mismo flujo que la UI de Kolibri:
// primero los metadatos (MB), luego la selección del contenido (GB).
apiRouter.get('/kolibri/tree/:channelId', async (req: Request, res: Response): Promise<void> => {
    try {
        const nodeId = req.query.nodeId ? String(req.query.nodeId) : undefined;
        res.json(await browseChannelTree(String(req.params.channelId), nodeId));
    } catch (e: any) {
        res.status(authStatus(e)).json({ error: e?.message || 'tree fetch failed' });
    }
});

// Tamaño exacto de una selección + espacio libre, para el paso de consentimiento.
// Body: { channelId, nodeIds?, excludeNodeIds? }
apiRouter.post('/kolibri/estimate', async (req: Request, res: Response): Promise<void> => {
    const body = req.body as { channelId?: unknown; nodeIds?: unknown; excludeNodeIds?: unknown };
    const channelId = String(body?.channelId ?? '').trim();
    if (!channelId) { res.status(400).json({ error: 'channelId required' }); return; }
    try {
        res.json(await estimateSelection(
            channelId,
            Array.isArray(body.nodeIds) ? body.nodeIds.map(String) : undefined,
            Array.isArray(body.excludeNodeIds) ? body.excludeNodeIds.map(String) : undefined,
        ));
    } catch (e: any) {
        res.status(authStatus(e)).json({ error: e?.message || 'estimate failed' });
    }
});

// Borrar un canal. Devuelve el id del job de Kolibri, no del motor local: es una
// operación corta y no merece un job durable.
apiRouter.post('/kolibri/delete', async (req: Request, res: Response): Promise<void> => {
    const body = req.body as { channelId?: unknown; channelName?: unknown };
    const channelId = String(body?.channelId ?? '').trim();
    if (!channelId) { res.status(400).json({ error: 'channelId required' }); return; }
    try {
        const jobId = await deleteChannel(channelId,
            body.channelName ? String(body.channelName) : undefined);
        // El id es de Kolibri, no del motor local; se consulta en /kolibri/task/:id.
        res.json({ ok: true, kolibriJobId: jobId, statusUrl: `/kolibri/task/${jobId}` });
    } catch (e: any) {
        res.status(authStatus(e)).json({ error: e?.message || 'delete failed' });
    }
});

// Estado de una tarea lanzada directamente en Kolibri (hoy solo el borrado). Sin
// esto, /kolibri/delete devolvía un id que ningún endpoint sabía consultar.
apiRouter.get('/kolibri/task/:id', async (req: Request, res: Response): Promise<void> => {
    try {
        res.json(await getKolibriTask(String(req.params.id)));
    } catch (e: any) {
        const status = e instanceof KolibriApiError && e.status === 404 ? 404 : authStatus(e);
        res.status(status).json({ error: e?.message || 'task lookup failed' });
    }
});

// --- Credenciales de servicios (ADFA-4949) ---------------------------------------
// Permite al webview actualizar usuario/contraseña sin recompilar. La contraseña
// NUNCA se devuelve: el GET solo dice qué usuario hay y de dónde sale.

apiRouter.get('/credentials/:service', (req: Request, res: Response): void => {
    const service = String(req.params.service);
    if (!isServiceName(service)) { res.status(404).json({ error: 'unknown service' }); return; }
    try {
        res.json(describeCredential(service));
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'read failed' });
    }
});

// Valida ANTES de persistir: así el error aparece en el formulario donde se cometió,
// no media hora después en una descarga fallida.
//   401 → credenciales rechazadas (no se guarda nada)
//   403 → autentica pero no puede gestionar contenido (no se guarda nada)
//   503 → Kolibri no responde; la capa de arranque debe resolverlo primero
apiRouter.post('/credentials/:service', async (req: Request, res: Response): Promise<void> => {
    const service = String(req.params.service);
    if (!isServiceName(service)) { res.status(404).json({ error: 'unknown service' }); return; }

    const body = req.body as { username?: unknown; password?: unknown };
    const username = String(body?.username ?? '').trim();
    const password = String(body?.password ?? '');
    if (!username || !password) {
        res.status(400).json({ error: 'username and password required' });
        return;
    }

    // Por ahora solo Kolibri se valida en vivo. Para otros servicios se guarda tal
    // cual (calibre sigue con sus constantes hasta que se migre en su propio PR).
    if (service !== 'kolibri') {
        try {
            setCredential(service, { username, password });
            res.json({ ok: true, verified: false, service });
        } catch (e: any) {
            res.status(500).json({ error: e?.message || 'save failed' });
        }
        return;
    }

    try {
        const check = await verifyCredentials(username, password);
        if (!check.canManageContent) {
            // Distinguir esto de "contraseña mala" ahorra horas de diagnóstico: el
            // usuario existe y la clave es correcta, pero no puede importar contenido.
            res.status(403).json({
                error: `'${username}' autentica pero no tiene permiso para gestionar contenido`,
                saved: false,
            });
            return;
        }
        setCredential('kolibri', { username, password });
        res.json({ ok: true, verified: true, service, username: check.username });
    } catch (e: any) {
        const status = authStatus(e);
        res.status(status).json({
            error: status === 401
                ? 'Kolibri rechazó estas credenciales'
                : (e?.message || 'verification failed'),
            saved: false,
        });
    }
});

// Volver al valor de fábrica (o a la variable de entorno, si está puesta).
apiRouter.delete('/credentials/:service', (req: Request, res: Response): void => {
    const service = String(req.params.service);
    if (!isServiceName(service)) { res.status(404).json({ error: 'unknown service' }); return; }
    try {
        clearCredential(service);
        res.json({ ok: true, ...describeCredential(service) });
    } catch (e: any) {
        res.status(500).json({ error: e?.message || 'reset failed' });
    }
});

// ADFA-5043: hand the app a service session cookie so it can inject it into the WebView and land the
// user already authenticated as the box admin (Calibre-Web / Kolibri). The login runs server-side with
// the stored credentials — the password never leaves the box. no-store so the session cookie is never
// cached by the proxy or the client.
apiRouter.get('/auth/:service/session', async (req: Request, res: Response): Promise<void> => {
    res.set('Cache-Control', 'no-store');
    const service = String(req.params.service);
    try {
        if (service === 'kolibri') {
            const s = await kolibriLogin();
            res.json({ service: 'kolibri', cookie: s.cookie });
            return;
        }
        if (service === 'calibre' || service === 'books') {
            const s = await getCalibreSession();
            res.json({ service: 'calibre', cookie: s.cookie });
            return;
        }
        res.status(404).json({ error: 'unknown service' });
    } catch (e: any) {
        // Keep the detail server-side; the app only needs the status + a generic reason.
        console.error('[auth] ' + (e?.message || e));
        if (e instanceof KolibriAuthError) {
            const status = e.reason === 'credentials' ? 401 : e.reason === 'permission' ? 403 : 503;
            res.status(status).json({ error: 'sign-in failed' });
            return;
        }
        // Calibre login throws generic errors; treat a clear bad-credential signal as 401, else 503.
        const status = /invalid.*cred/i.test(e?.message || '') ? 401 : 503;
        res.status(status).json({ error: 'sign-in failed' });
    }
});

// Start a content job → 202 { ...job }
apiRouter.post('/:type/download', (req: Request, res: Response): void => {
    const type = String(req.params.type);
    if (!isType(type)) { res.status(404).json({ error: 'unknown type' }); return; }
    // kiwix sends { ids: ["file.zim"] }; maps/books send { items: [ {...} ] }.
    const body = req.body as { ids?: unknown; items?: unknown };
    const items: unknown[] = Array.isArray(body?.items)
        ? body.items
        : Array.isArray(body?.ids) ? body.ids : [];
    if (items.length === 0) { res.status(400).json({ error: 'items (or ids) required' }); return; }
    res.status(202).json(toApi(jobs.create(type, items)));
});

// Poll one job's structured status.
apiRouter.get('/:type/jobs/:id', (req: Request, res: Response): void => {
    const job = jobs.get(String(req.params.id));
    if (!job || job.type !== String(req.params.type)) { res.status(404).json({ error: 'not found' }); return; }
    res.json(toApi(job));
});

// Live log tail for a job (ADFA-4879). Opt-in: a client polls ?since=<cursor> and appends the
// returned lines, feeding `next` back as the next `since`. Enables a simple live log over REST.
apiRouter.get('/:type/jobs/:id/log', (req: Request, res: Response): void => {
    const job = jobs.get(String(req.params.id));
    if (!job || job.type !== String(req.params.type)) { res.status(404).json({ error: 'not found' }); return; }
    const since = parseInt(String(req.query.since ?? '0'), 10);
    res.json(jobs.getLog(job.id, Number.isFinite(since) ? since : 0));
});

// List a type's jobs (most recent first).
apiRouter.get('/:type/jobs', (req: Request, res: Response): void => {
    const type = String(req.params.type);
    if (!isType(type)) { res.status(404).json({ error: 'unknown type' }); return; }
    res.json(jobs.list(type).map(toApi));
});

// Cancel a running job.
apiRouter.post('/:type/jobs/:id/cancel', (req: Request, res: Response): void => {
    const job = jobs.get(String(req.params.id));
    if (!job || job.type !== String(req.params.type)) { res.status(404).json({ error: 'not found' }); return; }
    jobs.cancel(job.id);
    res.json({ ok: true });
});
