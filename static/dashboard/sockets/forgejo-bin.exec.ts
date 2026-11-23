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
// no URL. aria2 downloads into bin/.k2go-download (under the URL's file name: the canonical flags have
// -Z, and aria2 ignores `out` with -Z), and the runner moves the file to forgejo-<ver> at the end, so
// the final name exists only for a complete file (a partial there would make get_url skip and the GPG
// check fail). Before the move, the file is checked against the release's published sha-256
// (forgejo_sha256_url in the role): a wrong file at the final name would fail the GPG check on every
// Retry.
import { jobs, RunnerContext, PausedError, CanceledError } from './jobs';
import { downloadWithAria2, stopError } from './aria2-download';
import { withRetry } from './net-retry';
import crypto from 'crypto';
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
/** Not forgejo-*, so the role's cleanup of superseded binaries never matches it. */
const STAGE_NAME = '.k2go-download';
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

export function stageDir(binDir: string): string {
    return path.join(binDir, STAGE_NAME);
}

/**
 * Remove the partial downloads in binDir's staging folder, except the files of keepVersion
 * (forgejo-<ver>-linux-<arch> and its .aria2 control file). Never touches a binary in binDir itself.
 */
export function removePartials(binDir: string, keepVersion: string | null = null): void {
    const dir = stageDir(binDir);
    let names: string[] = [];
    try { names = fs.readdirSync(dir); } catch { return; }
    for (const n of names) {
        if (keepVersion !== null && n.startsWith(`forgejo-${keepVersion}-linux-`)) continue;
        try { fs.rmSync(path.join(dir, n), { recursive: true, force: true }); } catch { /* best effort */ }
    }
}

/** One GET with the neutral agent, a timeout, and the job's stop signal. */
async function fetchText(ctx: RunnerContext, url: string, accept: string): Promise<{ status: number; text: string }> {
    const ac = new AbortController();
    const onStop = () => ac.abort();
    ctx.signal.addEventListener('abort', onStop, { once: true });
    const timer = setTimeout(() => ac.abort(), API_TIMEOUT_MS);
    try {
        const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT, Accept: accept }, signal: ac.signal });
        return { status: res.status, text: await res.text() };
    } finally {
        clearTimeout(timer);
        ctx.signal.removeEventListener('abort', onStop);
    }
}

/** Not worth a retry: the server answered, and the answer is wrong. */
class BadAnswer extends Error {}

/** The tag of the latest Forgejo release, without the leading "v" (the role's regex_replace). */
async function latestVersion(ctx: RunnerContext): Promise<string> {
    const { status, text } = await fetchText(ctx, RELEASES_API, 'application/json');
    if (status !== 200) throw new Error(`release lookup failed: HTTP ${status}`);
    let tag: unknown;
    try { tag = (JSON.parse(text) as { tag_name?: unknown }).tag_name; } catch { tag = undefined; }
    const version = typeof tag === 'string' ? tag.replace(/^v/, '') : '';
    if (!isForgejoVersion(version)) throw new BadAnswer(`release lookup gave a bad version: ${String(tag)}`);
    return version;
}

/** The first field of a `sha256sum` line ("<64 hex>  <name>"), or null. */
export function parseSha256(text: string): string | null {
    const m = /^([0-9a-fA-F]{64})(\s|$)/.exec(text.trim());
    return m ? m[1].toLowerCase() : null;
}

/** The release's published sha-256 (forgejo_sha256_url in the role), or null when it has none (404). */
async function releaseSha256(ctx: RunnerContext, url: string): Promise<string | null> {
    const { status, text } = await fetchText(ctx, `${url}.sha256`, 'text/plain');
    if (status === 404) return null;
    if (status !== 200) throw new Error(`checksum lookup failed: HTTP ${status}`);
    const sha = parseSha256(text);
    if (!sha) throw new BadAnswer('the checksum file is not a sha-256 line');
    return sha;
}

export function fileSha256(file: string): Promise<string> {
    return new Promise((resolve, reject) => {
        const h = crypto.createHash('sha256');
        fs.createReadStream(file).on('error', reject).on('data', (c) => h.update(c)).on('end', () => resolve(h.digest('hex')));
    });
}

async function lookup<T>(ctx: RunnerContext, fn: () => Promise<T>): Promise<T> {
    try {
        const v = await withRetry(fn, {
            delaysMs: RETRY_DELAYS,
            signal: ctx.signal,
            isCanceled: ctx.isCanceled,
            isTransient: (e) => !(e instanceof BadAnswer),
            onRetry: ({ attempt }) => ctx.reportRetry(attempt, RETRY_DELAYS.length),
        });
        ctx.reportRetry(0, 0);
        return v;
    } catch (e) {
        const se = stopError(ctx);
        if (se) throw se;
        throw e;
    }
}

const forgejoBinRunner: (ctx: RunnerContext) => Promise<void> = async (ctx) => {
    const suffix = forgejoSuffix(os.machine());
    if (!suffix) throw new Error(`no Forgejo binary for CPU architecture ${os.machine()}`);

    ctx.update({ phase: 'downloading', percent: -1, speed: 0, eta: -1, detail: 'forgejo version' });
    const version = await lookup(ctx, () => latestVersion(ctx));
    const url = forgejoUrl(version, suffix);
    const sha = await lookup(ctx, () => releaseSha256(ctx, url));
    if (!sha) ctx.log(`[forgejo-bin] no published sha-256 for ${version}; the role GPG check still runs`);
    const result = JSON.stringify({ version });

    fs.mkdirSync(BIN_DIR, { recursive: true });
    removePartials(BIN_DIR, version);   // a partial of an older release cannot be resumed
    const dest = finalPath(BIN_DIR, version);
    if (fs.existsSync(dest)) {
        // A wrong file here would fail the role's GPG check on every Retry, so it must not be kept.
        if (sha && await fileSha256(dest) !== sha) {
            ctx.log(`[forgejo-bin] ${path.basename(dest)} does not match the release sha-256; downloading it again`);
            fs.rmSync(dest, { force: true });
        } else {
            removePartials(BIN_DIR);
            ctx.log(`[forgejo-bin] ${path.basename(dest)} is already there`);
            ctx.update({ phase: 'done', percent: 100, speed: 0, eta: -1, detail: version, result });
            return;
        }
    }

    const stage = stageDir(BIN_DIR);
    const staged = path.join(stage, path.basename(url));
    fs.mkdirSync(stage, { recursive: true });
    ctx.update({ phase: 'downloading', percent: -1, speed: 0, eta: -1, detail: version });
    try {
        await downloadWithAria2(ctx, { destDir: stage, urls: [url] });
    } catch (e) {
        const se = stopError(ctx);
        if (se instanceof PausedError) throw se;   // keep the partial: Resume continues it
        if (se instanceof CanceledError) { removePartials(BIN_DIR); throw se; }
        throw e;                                    // keep the partial: Retry continues it
    }

    if (!fs.existsSync(staged) || fs.existsSync(`${staged}.aria2`)) {
        throw new Error('the Forgejo download did not complete');
    }
    if (sha && await fileSha256(staged) !== sha) {
        removePartials(BIN_DIR);                    // wrong bytes: a Retry must start from 0
        throw new Error('the Forgejo download does not match the release sha-256');
    }
    fs.renameSync(staged, dest);
    fs.chmodSync(dest, 0o775);   // the role's get_url mode
    removePartials(BIN_DIR);
    ctx.update({ phase: 'done', percent: 100, speed: 0, eta: -1, detail: version, result });
};

jobs.registerRunner('forgejo-bin', forgejoBinRunner);
// A Cancel of a PAUSED job: its runner already exited and kept the partial for Resume.
jobs.registerDiscard('forgejo-bin', () => removePartials(BIN_DIR));

export { forgejoBinRunner };
