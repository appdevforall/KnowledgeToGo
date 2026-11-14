// sockets/log-less.ts -- K2GO-559
//
// Log less at the source: the box logs must not identify who used the server. New bakes ship this
// (tools/upstream-patches 0010-0012); this step brings boxes installed before that change to the
// same state, once. Each step edits the service's own config and keeps a value an admin changed.
// The change applies when the service restarts: Forgejo then reads as `pending` (privacy.ts) and the
// app restarts it at its next open; nginx and Calibre-Web apply it at the next box start. After a
// service runs on the new config, its older logs (written with client IDs) are removed once.
import fs from 'fs';
import path from 'path';
import Database from 'better-sqlite3';
import { processStartMs, readIniOption, setIniOption, writeAtomic } from './privacy';

export type LogLessStep = 'nginx' | 'forgejo' | 'calibre-web';

export interface LogLessTargets {
    nginxConf: string;
    forgejoIni: string;
    calibreAppDb: string;
}

export const LOG_LESS_TARGETS: LogLessTargets = {
    nginxConf: process.env.K2GO_NGINX_CONF || '/etc/nginx/nginx.conf',
    forgejoIni: process.env.K2GO_FORGEJO_APP_INI || '/etc/forgejo/app.ini',
    calibreAppDb: process.env.K2GO_CALIBRE_APP_DB || '/library/calibre-web/config/app.db',
};

// Owner of the marker: dash-node, at startup. It is written only after every step succeeds; a
// crash before it re-runs the steps at the next start, which is safe because each step is a no-op
// on a config that already has the change. It records which services changed (for the log purge).
export const LOG_LESS_MARKER = process.env.K2GO_LOG_LESS_MARKER || '/library/dashboard/log-less.done';

/** The IIAB `log_format awstats` with the client IP and the User-Agent replaced by "-" (same fields,
 *  so awstats still parses the log). undefined when the statement is not the stock one. */
export function nginxWithoutClientIds(text: string): string | undefined {
    const m = /log_format\s+awstats\b[^;]*;/.exec(text);
    if (!m || !m[0].includes('$remote_addr') || !m[0].includes('"$http_user_agent"')) return undefined;
    const fixed = m[0].replace('$remote_addr', '-').replace('"$http_user_agent"', '"-"');
    return text.slice(0, m.index) + fixed + text.slice(m.index + m[0].length);
}

// Every key Forgejo reads for the router logger (modules/setting/log.go). Any of them set = the
// admin chose; we leave it.
const FORGEJO_ROUTER_KEYS = ['LOGGER_ROUTER_MODE', 'logger.router.MODE', 'ROUTER', 'DISABLE_ROUTER_LOG'];

/** app.ini with the router log off (`LOGGER_ROUTER_MODE` empty). undefined when a router key is set. */
export function forgejoWithoutRouterLog(text: string): string | undefined {
    if (FORGEJO_ROUTER_KEYS.some((k) => readIniOption(text, 'log', k) !== undefined)) return undefined;
    return setIniOption(text, 'log', 'LOGGER_ROUTER_MODE', '');
}

/** Python logging levels as Calibre-Web stores them (`settings.config_log_level`). */
const CALIBRE_DEBUG_MAX = 19;
const CALIBRE_INFO = 20;

/** Raise a DEBUG Calibre-Web log level to INFO. Returns true when the row changed. */
export function sqliteCalibreInfo(dbFile: string): boolean {
    const db = new Database(dbFile, { timeout: 5000 });
    try {
        const r = db.prepare('UPDATE settings SET config_log_level = ? WHERE config_log_level <= ?')
            .run(CALIBRE_INFO, CALIBRE_DEBUG_MAX);
        return r.changes > 0;
    } finally {
        db.close();
    }
}

function rewrite(file: string, edit: (text: string) => string | undefined): boolean {
    if (!fs.existsSync(file)) return false;   // the service is not on the box
    const next = edit(fs.readFileSync(file, 'utf8'));
    if (next === undefined) return false;
    writeAtomic(file, next);
    return true;
}

function writeMarker(marker: string, data: string): void {
    const tmp = marker + '.k2go-tmp';
    fs.writeFileSync(tmp, data);
    fs.renameSync(tmp, marker);
}

export function applyLogLessOnce(
    t: LogLessTargets, marker: string, calibreInfo: (dbFile: string) => boolean = sqliteCalibreInfo,
): { ran: boolean; changed: LogLessStep[] } {
    if (fs.existsSync(marker)) return { ran: false, changed: [] };
    const changed: LogLessStep[] = [];
    if (rewrite(t.nginxConf, nginxWithoutClientIds)) changed.push('nginx');
    if (rewrite(t.forgejoIni, forgejoWithoutRouterLog)) changed.push('forgejo');
    if (fs.existsSync(t.calibreAppDb) && calibreInfo(t.calibreAppDb)) changed.push('calibre-web');
    writeMarker(marker, JSON.stringify({ at: new Date().toISOString(), changed }) + '\n');
    return { ran: true, changed };
}

// --- Remove the logs written before the change ----------------------------------------------------
// They hold client IPs, User-Agents (nginx, Forgejo router) or DEBUG session data (Calibre-Web).
// Only a service that the step above changed is purged, and only when the new config is in effect:
// its process started after the change, or it does not run. Then the rotated copies from before the
// change are deleted and the live log is emptied in place (the writers append, as with logrotate
// copytruncate). Owner of the marker: dash-node, at startup; while a changed service still runs the
// old config, the next start tries again.
export const LOG_PURGE_MARKER = process.env.K2GO_LOG_PURGE_MARKER || '/library/dashboard/log-less-purge.done';

/** Per service: the log that held client IDs (rotated copies are `<log>.N[.gz]`) and the `pgrep -f`
 *  pattern of its process. Paths: tools/setup-proot-logging.sh and the IIAB pdsm service wrappers. */
export interface ServiceLog { log: string; process: string }

export const SERVICE_LOGS: Record<LogLessStep, ServiceLog> = {
    nginx: { log: process.env.K2GO_NGINX_ACCESS_LOG || '/var/log/nginx/access.log', process: 'nginx: master' },
    forgejo: { log: process.env.K2GO_FORGEJO_LOG || '/library/forgejo/log/forgejo.log', process: 'forgejo web' },
    'calibre-web': { log: process.env.K2GO_CALIBRE_LOG || '/var/log/calibre-web.log', process: 'calibre-web-py3/cps.py' },
};

/** Rotated copies of `log` (`<log>.N`, `<log>.N.gz`) last written before `beforeMs`. */
export function rotatedBefore(log: string, beforeMs: number): string[] {
    const dir = path.dirname(log);
    const base = path.basename(log);
    let names: string[];
    try { names = fs.readdirSync(dir); } catch { return []; }
    return names
        .filter((n) => n.startsWith(base + '.') && /^\d+(\.gz)?$/.test(n.slice(base.length + 1)))
        .map((n) => path.join(dir, n))
        .filter((f) => { try { return fs.statSync(f).mtimeMs < beforeMs; } catch { return false; } });
}

export function purgeOldLogsOnce(
    marker: string, purgeMarker: string, logs: Record<LogLessStep, ServiceLog> = SERVICE_LOGS,
    startMs: (pattern: string) => number | null = processStartMs,
): { ran: boolean; purged: LogLessStep[]; waiting: LogLessStep[] } {
    if (fs.existsSync(purgeMarker) || !fs.existsSync(marker)) return { ran: false, purged: [], waiting: [] };
    const changeMs = fs.statSync(marker).mtimeMs;
    let changed: LogLessStep[] = [];
    try { changed = JSON.parse(fs.readFileSync(marker, 'utf8')).changed || []; } catch { /* nothing to purge */ }
    const purged: LogLessStep[] = [];
    const waiting: LogLessStep[] = [];
    for (const svc of changed) {
        const s = logs[svc];
        if (!s) continue;
        const started = startMs(s.process);
        if (started !== null && started <= changeMs) { waiting.push(svc); continue; }   // old config runs
        // Until the restart the old config wrote the log, so a copy rotated after the change can
        // still hold IDs: the cutoff is the restart (or now, when the service does not run).
        for (const f of rotatedBefore(s.log, started ?? Date.now())) fs.rmSync(f, { force: true });
        if (fs.existsSync(s.log)) fs.truncateSync(s.log, 0);
        purged.push(svc);
    }
    if (waiting.length === 0) writeMarker(purgeMarker, new Date().toISOString() + '\n');
    return { ran: true, purged, waiting };
}
