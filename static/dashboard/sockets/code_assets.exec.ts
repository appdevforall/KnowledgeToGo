// sockets/code_assets.exec.ts - K2GO-443
//
// Code on the Go build-assets runner for the durable job engine. Replaces the minimalist
// wrapper (tools/code-assets-refresh.sh) with an aria2 download that gives percent / speed /
// pause / resume / retry and survives a network change, like kiwix / maps. The Python mirror
// keeps the content-specific work: it prints the aria2 input-file (the file set + source base,
// one source of truth) and, after the download, verifies each file against its published .md5
// and writes the browse page (--finalize-only). The runner swaps the staged tree in atomically.
//
// Option A (ADR-updater-progress-resilience): aria2 download in the runner, verify + page in the
// mirror, swap in the runner. The mirror is read from the self-updating clone when present
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

const codeAssetsRunner: (ctx: RunnerContext) => Promise<void> = async (ctx) => {
    const MIRROR = mirrorPath();
    if (!fs.existsSync(MIRROR)) throw new Error(`mirror script not found: ${MIRROR}`);

    // --- Download phase (aria2, resilient) ---------------------------------
    // STAGE is intentionally NOT cleared here: a resume continues the partial via aria2 --continue.
    // The mirror prints the aria2 input-file (url + out=path), so the manifest + base stay single-source.
    ctx.update({ phase: 'downloading', percent: -1, speed: 0, detail: 'build assets' });
    fs.mkdirSync(STAGE, { recursive: true });
    const inputFile = `${STAGE}/.aria2-input`;
    fs.writeFileSync(inputFile, execFileSync('python3', [MIRROR, '--print-aria2-input']));

    try {
        await downloadWithAria2(ctx, { destDir: STAGE, inputFile, phase: 'downloading' });
    } catch (e) {
        const se = stopError(ctx);
        if (se instanceof PausedError) throw se;                     // keep STAGE: resume continues
        if (se instanceof CanceledError) { rmrf(STAGE); throw se; }  // cancel discards the partial
        throw e;                                                     // real error: keep STAGE for a retry/resume
    }
    ctx.throwIfCanceled();

    // --- Verify + page (no download) ---------------------------------------
    ctx.update({ phase: 'processing', percent: -1, speed: 0, detail: 'verifying' });
    try {
        await new Promise<void>((resolve, reject) => {
            const fin = ctx.spawn('python3', [MIRROR, '--finalize-only', '--out', STAGE]);
            fin.stdout?.on('data', (d: Buffer) => ctx.log(d.toString().trim()));
            fin.stderr?.on('data', (d: Buffer) => ctx.log(d.toString().trim()));
            fin.on('error', reject);
            fin.on('exit', (code, signal) => {
                if (signal === 'SIGKILL' || ctx.isCanceled()) return reject(new CanceledError());
                if (code === 0) return resolve();
                reject(new Error(`finalize failed (exit ${code}): a file did not verify`));
            });
        });
    } catch (e) {
        const se = stopError(ctx);
        if (se) throw se;
        throw e;
    }
    ctx.throwIfCanceled();

    // --- Swap (atomic rename on the same filesystem) -----------------------
    ctx.update({ phase: 'processing', percent: 100, speed: 0, detail: 'installing' });
    try { fs.rmSync(inputFile, { force: true }); } catch { /* leave nothing non-served behind */ }
    cleanAria2Files(STAGE);
    rmrf(OLD);
    if (fs.existsSync(SERVE)) fs.renameSync(SERVE, OLD);
    fs.renameSync(STAGE, SERVE);
    rmrf(OLD);

    ctx.update({ phase: 'done', percent: 100, speed: 0 });
};

jobs.registerRunner('code-assets', codeAssetsRunner);

export { codeAssetsRunner };
