// sockets/forgejo-bin.exec.ts - K2GO-593
//
// Forgejo binary runner for the durable job engine. The forgejo role downloads a ~100 MB binary with
// Ansible get_url: no percent, no pause, no resume, and it runs after `pdsm stop`, which also stops
// dash-node. The app runs this job BEFORE the stop. It puts the binary at the path the role uses
// (/library/forgejo/bin/forgejo-<ver>), and the app pins forgejo_version=<ver> in local_vars, so the
// role's get_url finds the file and skips the download. The role still downloads the .asc and does
// its GPG check (fail closed) on this file.
//
// The box resolves the version (the same codeberg API as the role) and makes the URL; the app sends
// no URL. aria2 writes to forgejo-<ver>.part and the runner renames it at the end, so the final name
// exists only for a complete file (a partial at the final name would make get_url skip and the GPG
// check fail).
import { jobs, RunnerContext, PausedError, CanceledError } from './jobs';
import { downloadWithAria2, stopError } from './aria2-download';
import { withRetry } from './net-retry';
import fs from 'fs';
import os from 'os';
import path from 'path';

/** forgejo_root_directory/bin in roles/forgejo/defaults/main.yml. */
const BIN_DIR = process.env.K2GO_FORGEJO_BIN_DIR || '/library/forgejo/bin';
/** forgejo_releases_api in the role. */
const RELEASES_API = 'https://codeberg.org/api/v1/repos/forgejo/forgejo/releases/latest';
const DOWNLOAD_BASE = 'https://codeberg.org/forgejo/forgejo/releases/download';
// K2G-P5: a neutral agent, the same one books.exec.ts sends.
const USER_AGENT = 'K2Go Dashboard/1.0 (https://github.com/appdevforall/KnowledgeToGo)';
const API_TIMEOUT_MS = 20_000;
const PART = '.part';
const RETRY_DELAYS = [3_000, 6_000, 9_000, 18_000, 36_000];

/** The role's iset_suffixes map, keyed on `uname -m` (os.machine), the value the role reads. */
const ISET_SUFFIXES: Record<string, string> = {
    x86_64: 'amd64',
    aarch64: 'arm64',
    armv7l: 'arm-6',
    armv6l: 'arm-6',
    armv8l: 'arm-6',
};

export function forgejoSuffix(machine: string): string | null {
    return ISET_SUFFIXES[machine] ?? null;
}

/** The role's assert: forgejo_version is X.Y.Z. Also keeps the value safe for a path and a URL. */
export function isForgejoVersion(v: unknown): v is string {
    return typeof v === 'string' && /^[0-9]+\.[0-9]+\.[0-9]+$/.test(v);
}

/** forgejo_download_url in the role. */
export function forgejoUrl(version: string, suffix: string): string {
    return `${DOWNLOAD_BASE}/v${version}/forgejo-${version}-linux-${suffix}`;
}

export function finalPath(dir: string, version: string): string {
    return path.join(dir, `forgejo-${version}`);
}

/**
 * Remove the partial downloads (forgejo-<ver>.part and its .aria2 control file) in dir, except the
 * ones of keepVersion. Never touches a complete forgejo-<ver> binary or its .asc.
 */
export function removePartials(dir: string, keepVersion: string | null = null): void {
    let names: string[] = [];
    try { names = fs.readdirSync(dir); } catch { return; }
    for (const n of names) {
        const m = /^forgejo-([0-9]+\.[0-9]+\.[0-9]+)\.part(\.aria2)?$/.exec(n);
        if (!m || m[1] === keepVersion) continue;
        try { fs.rmSync(path.join(dir, n), { force: true }); } catch { /* best effort */ }
    }
}

/** The tag of the latest Forgejo release, without the leading "v" (the role's regex_replace). */
async function latestVersion(ctx: RunnerContext): Promise<string> {
    const ac = new AbortController();
    const onStop = () => ac.abort();
    ctx.signal.addEventListener('abort', onStop, { once: true });
    const timer = setTimeout(() => ac.abort(), API_TIMEOUT_MS);
    try {
        const res = await fetch(RELEASES_API, {
            headers: { 'User-Agent': USER_AGENT, Accept: 'application/json' },
            signal: ac.signal,
        });
        if (!res.ok) throw new Error(`release lookup failed: HTTP ${res.status}`);
        const body = await res.json() as { tag_name?: unknown };
        const version = typeof body.tag_name === 'string' ? body.tag_name.replace(/^v/, '') : '';
        if (!isForgejoVersion(version)) throw new Error(`release lookup gave a bad version: ${String(body.tag_name)}`);
        return version;
    } finally {
        clearTimeout(timer);
        ctx.signal.removeEventListener('abort', onStop);
    }
}

const forgejoBinRunner: (ctx: RunnerContext) => Promise<void> = async (ctx) => {
    const suffix = forgejoSuffix(os.machine());
    if (!suffix) throw new Error(`no Forgejo binary for CPU architecture ${os.machine()}`);

    ctx.update({ phase: 'downloading', percent: -1, speed: 0, eta: -1, detail: 'forgejo version' });
    let version: string;
    try {
        version = await withRetry(() => latestVersion(ctx), {
            delaysMs: RETRY_DELAYS,
            signal: ctx.signal,
            isCanceled: ctx.isCanceled,
            isTransient: (e) => !(e instanceof Error && /bad version/.test(e.message)),
            onRetry: ({ attempt }) => ctx.reportRetry(attempt, RETRY_DELAYS.length),
        });
    } catch (e) {
        const se = stopError(ctx);
        if (se) throw se;
        throw e;
    }
    ctx.reportRetry(0, 0);
    const result = JSON.stringify({ version });

    fs.mkdirSync(BIN_DIR, { recursive: true });
    removePartials(BIN_DIR, version);   // a partial of an older release cannot be resumed
    const dest = finalPath(BIN_DIR, version);
    if (fs.existsSync(dest)) {
        removePartials(BIN_DIR);
        ctx.log(`[forgejo-bin] ${path.basename(dest)} is already there`);
        ctx.update({ phase: 'done', percent: 100, speed: 0, eta: -1, detail: version, result });
        return;
    }

    const part = `forgejo-${version}${PART}`;
    const inputFile = path.join(os.tmpdir(), `k2go-forgejo-bin-${process.pid}.aria2-input`);
    fs.writeFileSync(inputFile, `${forgejoUrl(version, suffix)}\n  out=${part}\n`);
    ctx.update({ phase: 'downloading', percent: -1, speed: 0, eta: -1, detail: version });
    try {
        await downloadWithAria2(ctx, { destDir: BIN_DIR, inputFile });
    } catch (e) {
        const se = stopError(ctx);
        if (se instanceof PausedError) throw se;   // keep the partial: Resume continues it
        if (se instanceof CanceledError) { removePartials(BIN_DIR); throw se; }
        throw e;                                    // keep the partial: Retry continues it
    } finally {
        try { fs.rmSync(inputFile, { force: true }); } catch { /* best effort */ }
    }

    const partPath = path.join(BIN_DIR, part);
    if (!fs.existsSync(partPath) || fs.existsSync(`${partPath}.aria2`)) {
        throw new Error('the Forgejo download did not complete');
    }
    fs.renameSync(partPath, dest);
    fs.chmodSync(dest, 0o775);   // the role's get_url mode
    ctx.update({ phase: 'done', percent: 100, speed: 0, eta: -1, detail: version, result });
};

jobs.registerRunner('forgejo-bin', forgejoBinRunner);
// A Cancel of a PAUSED job: its runner already exited and kept the partial for Resume.
jobs.registerDiscard('forgejo-bin', () => removePartials(BIN_DIR));

export { forgejoBinRunner };
