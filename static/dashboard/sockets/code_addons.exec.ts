// sockets/code_addons.exec.ts - K2GO-443
//
// Code on the Go add-ons gallery runner for the durable job engine. Replaces the fire-and-forget
// wrapper (tools/code-addons-refresh.sh + POST /addons/refresh + status-file poll) with an aria2
// download that gives percent / speed / pause / resume / retry and survives a network change, like
// build-assets / kiwix / maps. The gallery is a mix: many small files (shell, catalog, icons, pages)
// that need a transform (Cloudflare clean + catalog base rewrite), and the heavy add-on binaries
// (.cgp + source tarballs). Only the heavy binaries go through aria2; the mirror stages the rest.
//
// The mirror splits into a plan step and a verify step (mirror_addons.py):
//   --print-aria2-input: stage the small files, print an aria2 input-file for the heavy binaries. Its
//     first stdout line is a status marker. '#status=uptodate' means the same build is already served
//     (keep the live tree, do not swap). '#status=stage' means STAGE is populated and the lines after
//     are the aria2 input, which may be EMPTY when only small files changed (still finalize and swap).
//   --finalize-only: verify the aria2-downloaded binaries are present in STAGE (aria2 already verified
//     each by its sha-256).
// The runner then swaps the staged tree in atomically, restoring the previous tree if the swap fails.
import { jobs, RunnerContext, CanceledError, PausedError } from './jobs';
import { downloadWithAria2, stopError } from './aria2-download';
import { execFileSync } from 'child_process';
import fs from 'fs';

const SERVE = '/library/www/code-addons';
const STAGE = `${SERVE}.new`;
const OLD = `${SERVE}.old`;
const SERVE_BASE = '/code-addons';   // the catalog base the mirror rewrites to
// K2GO-440: prefer the mirror from the self-updating clone so a mirror fix ships via the dash-node
// rebuild with no rebake; fall back to the bake-time copy in the ansible roles dir.
const MIRROR_CLONE = '/opt/iiab-android/tools/upstream-patches/overlays/roles/code_addons/files/mirror_addons.py';
const MIRROR_BAKED = '/opt/iiab/iiab/roles/code_addons/files/mirror_addons.py';

function mirrorPath(): string {
    return fs.existsSync(MIRROR_CLONE) ? MIRROR_CLONE : MIRROR_BAKED;
}

function rmrf(p: string): void {
    try { fs.rmSync(p, { recursive: true, force: true }); } catch { /* best effort */ }
}

/** Remove aria2 control/metadata so the served tree is only the gallery files. */
function cleanAria2Files(dir: string): void {
    try { execFileSync('find', [dir, '-name', '*.aria2', '-delete']); } catch { /* best effort */ }
}

/** Run the mirror and resolve with its stdout; rejects (paused/canceled/error) like a download step. */
function runMirror(ctx: RunnerContext, mirror: string, args: string[], capture: boolean): Promise<string> {
    return new Promise<string>((resolve, reject) => {
        let out = '';
        const p = ctx.spawn('python3', [mirror, ...args]);
        p.stdout?.on('data', (d: Buffer) => { if (capture) out += d.toString(); else ctx.log(d.toString().trim()); });
        p.stderr?.on('data', (d: Buffer) => ctx.log(d.toString().trim()));
        p.on('error', reject);
        p.on('exit', (code, signal) => {
            if (signal === 'SIGKILL' || ctx.isCanceled()) return reject(new CanceledError());
            if (code === 0) return resolve(out);
            reject(new Error(`${args[0]} failed (exit ${code})`));
        });
    });
}

const codeAddonsRunner: (ctx: RunnerContext) => Promise<void> = async (ctx) => {
    const MIRROR = mirrorPath();
    if (!fs.existsSync(MIRROR)) throw new Error(`mirror script not found: ${MIRROR}`);

    // --- Plan (incremental) -------------------------------------------------
    // The mirror stages the small files into STAGE from the published site + the live tree and prints a
    // status marker plus an aria2 input-file for the heavy binaries. STAGE is NOT pre-cleared, so a
    // resume keeps its partials; the plan is deterministic and safe to re-run.
    ctx.update({ phase: 'downloading', percent: -1, speed: 0, detail: 'add-ons' });
    let planned: string;
    try {
        planned = await runMirror(ctx, MIRROR,
            ['--print-aria2-input', '--reuse-from', SERVE, '--out', STAGE, '--serve-base', SERVE_BASE], true);
    } catch (e) {
        const se = stopError(ctx);
        if (se instanceof PausedError) throw se;
        if (se instanceof CanceledError) { rmrf(STAGE); throw se; }
        throw e;
    }
    ctx.throwIfCanceled();

    const nl = planned.indexOf('\n');
    const marker = (nl === -1 ? planned : planned.slice(0, nl)).trim();
    const body = (nl === -1 ? '' : planned.slice(nl + 1)).trim();

    if (marker === '#status=uptodate') {
        // Same published build: keep the live tree, nothing to download or swap.
        rmrf(STAGE);
        ctx.update({ phase: 'done', percent: 100, speed: 0, detail: 'up to date' });
        return;
    }

    // --- Download the changed heavy binaries (aria2, resilient) -------------
    // body may be empty when only small files changed: skip the download, but still finalize and swap
    // so the staged small-file changes go live.
    if (body !== '') {
        const inputFile = `${STAGE}/.aria2-input`;
        fs.mkdirSync(STAGE, { recursive: true });
        fs.writeFileSync(inputFile, body + '\n');
        try {
            await downloadWithAria2(ctx, { destDir: STAGE, inputFile, phase: 'downloading' });
        } catch (e) {
            const se = stopError(ctx);
            if (se instanceof PausedError) throw se;                     // keep STAGE: resume continues
            if (se instanceof CanceledError) { rmrf(STAGE); throw se; }  // cancel discards the partial
            throw e;                                                     // real error: keep STAGE for a retry/resume
        }
        try { fs.rmSync(inputFile, { force: true }); } catch { /* leave nothing non-served behind */ }
    }
    ctx.throwIfCanceled();

    // --- Verify (aria2 checked each binary by sha-256; no re-hash) ----------
    ctx.update({ phase: 'processing', percent: -1, speed: 0, detail: 'finishing' });
    try {
        await runMirror(ctx, MIRROR, ['--finalize-only', '--out', STAGE, '--serve-base', SERVE_BASE], false);
    } catch (e) {
        const se = stopError(ctx);
        if (se) throw se;
        throw e;
    }
    ctx.throwIfCanceled();

    // --- Swap (atomic rename; restore the previous tree if the move fails) --
    ctx.update({ phase: 'processing', percent: 100, speed: 0, detail: 'installing' });
    cleanAria2Files(STAGE);
    rmrf(OLD);
    if (fs.existsSync(SERVE)) fs.renameSync(SERVE, OLD);
    try {
        fs.renameSync(STAGE, SERVE);
    } catch (e) {
        // A failed swap must never leave the box with no served gallery: put the previous tree back.
        if (!fs.existsSync(SERVE) && fs.existsSync(OLD)) {
            try { fs.renameSync(OLD, SERVE); } catch { /* best effort */ }
        }
        throw e;
    }
    rmrf(OLD);

    ctx.update({ phase: 'done', percent: 100, speed: 0 });
};

jobs.registerRunner('code-addons', codeAddonsRunner);

export { codeAddonsRunner };
