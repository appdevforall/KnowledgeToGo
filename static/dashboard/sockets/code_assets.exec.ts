// sockets/code_assets.exec.ts - K2GO-443
//
// Code on the Go build-assets runner for the durable job engine. Replaces the minimalist
// wrapper (tools/code-assets-refresh.sh) with an aria2 download that gives percent / speed /
// pause / resume / retry and survives a network change, like kiwix / maps. The Python mirror
// keeps the content-specific work: --print-aria2-input stages the UNCHANGED files from the live
// tree and prints an aria2 input-file (url + out + checksum=md5) for only the files that changed
// (so a routine update re-downloads nothing when nothing changed); --finalize-only builds the
// browse page (aria2 already verified each file by its checksum). The runner swaps the staged
// tree in atomically, restoring the previous tree if the swap fails.
//
// Option A (ADR-updater-progress-resilience): aria2 download in the runner, plan + verify + page
// in the mirror, swap in the runner. The mirror is read from the self-updating clone when present
// (K2GO-440), else the bake-time copy.
import { jobs, RunnerContext, CanceledError, PausedError } from './jobs';
import { downloadWithAria2, stopError } from './aria2-download';
import { execFileSync } from 'child_process';
import fs from 'fs';

const SERVE = '/library/www/code-assets';
const STAGE = `${SERVE}.new`;
const OLD = `${SERVE}.old`;
// K2GO-440: prefer the mirror from the self-updating clone so a mirror fix ships via the dash-node
// rebuild with no rebake; fall back to the bake-time copy in the ansible roles dir.
const MIRROR_CLONE = '/opt/iiab-android/tools/upstream-patches/overlays/roles/code_assets/files/mirror_code_assets.py';
const MIRROR_BAKED = '/opt/iiab/iiab/roles/code_assets/files/mirror_code_assets.py';

function mirrorPath(): string {
    return fs.existsSync(MIRROR_CLONE) ? MIRROR_CLONE : MIRROR_BAKED;
}

function rmrf(p: string): void {
    try { fs.rmSync(p, { recursive: true, force: true }); } catch { /* best effort */ }
}

/** Remove aria2 control/metadata so the served tree is only the assets + .md5 + index.html. */
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

const codeAssetsRunner: (ctx: RunnerContext) => Promise<void> = async (ctx) => {
    const MIRROR = mirrorPath();
    if (!fs.existsSync(MIRROR)) throw new Error(`mirror script not found: ${MIRROR}`);

    // --- Plan (incremental) -------------------------------------------------
    // The mirror stages the UNCHANGED files into STAGE from the live tree and prints an aria2
    // input-file for only the changed files (empty = nothing changed). STAGE is NOT pre-cleared, so
    // a resume keeps its partials; the plan is deterministic and safe to re-run.
    ctx.update({ phase: 'downloading', percent: -1, speed: 0, detail: 'build assets' });
    let input: string;
    try {
        input = await runMirror(ctx, MIRROR, ['--print-aria2-input', '--reuse-from', SERVE, '--out', STAGE], true);
    } catch (e) {
        const se = stopError(ctx);
        if (se instanceof PausedError) throw se;
        if (se instanceof CanceledError) { rmrf(STAGE); throw se; }
        throw e;
    }
    ctx.throwIfCanceled();

    if (input.trim() === '') {
        // Every file unchanged: keep the live tree, nothing to download or swap.
        rmrf(STAGE);
        ctx.update({ phase: 'done', percent: 100, speed: 0, detail: 'up to date' });
        return;
    }

    // --- Download the changed files (aria2, resilient) ---------------------
    const inputFile = `${STAGE}/.aria2-input`;
    fs.mkdirSync(STAGE, { recursive: true });
    fs.writeFileSync(inputFile, input);
    try {
        await downloadWithAria2(ctx, { destDir: STAGE, inputFile, phase: 'downloading' });
    } catch (e) {
        const se = stopError(ctx);
        if (se instanceof PausedError) throw se;                     // keep STAGE: resume continues
        if (se instanceof CanceledError) { rmrf(STAGE); throw se; }  // cancel discards the partial
        throw e;                                                     // real error: keep STAGE for a retry/resume
    }
    ctx.throwIfCanceled();

    // --- Build the page (aria2 verified each file by checksum; no re-hash) --
    ctx.update({ phase: 'processing', percent: -1, speed: 0, detail: 'finishing' });
    try {
        await runMirror(ctx, MIRROR, ['--finalize-only', '--out', STAGE], false);
    } catch (e) {
        const se = stopError(ctx);
        if (se) throw se;
        throw e;
    }
    ctx.throwIfCanceled();

    // --- Swap (atomic rename; restore the previous tree if the move fails) --
    ctx.update({ phase: 'processing', percent: 100, speed: 0, detail: 'installing' });
    try { fs.rmSync(inputFile, { force: true }); } catch { /* leave nothing non-served behind */ }
    cleanAria2Files(STAGE);
    rmrf(OLD);
    if (fs.existsSync(SERVE)) fs.renameSync(SERVE, OLD);
    try {
        fs.renameSync(STAGE, SERVE);
    } catch (e) {
        // A failed swap must never leave the box with no served assets: put the previous tree back.
        if (!fs.existsSync(SERVE) && fs.existsSync(OLD)) {
            try { fs.renameSync(OLD, SERVE); } catch { /* best effort */ }
        }
        throw e;
    }
    rmrf(OLD);

    ctx.update({ phase: 'done', percent: 100, speed: 0 });
};

jobs.registerRunner('code-assets', codeAssetsRunner);

export { codeAssetsRunner };
