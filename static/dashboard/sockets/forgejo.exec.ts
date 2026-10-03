// sockets/forgejo.exec.ts - K2GO-443
//
// Forgejo repo-refresh runner for the durable job engine. Forgejo is a GIT operation (per seeded example
// repo: fetch to a side ref, classify by ancestry, fast-forward or clean 3-way merge, authenticated push),
// NOT a file download. So there is no aria2, no staging swap, and NO mid-transfer pause/resume: repo-count
// IS the progress (repo N of M), and a retry just re-runs the refresh (idempotent: every push is a
// fast-forward or a clean merge; a conflict is left as-is and reported). The runner wraps the existing box
// orchestration (static/forgejo/orchestration -> refresh_forgejo) and reads its K2GO_PROGRESS marker for
// percent + the current repo. The legacy POST /forgejo/refresh wrapper stays for now.
// See controller/docs/ADR-updater-progress-resilience.md.
import { jobs, RunnerContext, CanceledError } from './jobs';
import fs from 'fs';

// The orchestration lives in the self-updating clone (like the legacy wrapper), so an orchestration fix
// ships via the dash-node rebuild with no rebake (K2GO-440).
const ORCH_CLONE = '/opt/iiab-android/static/forgejo/orchestration';

const PROGRESS = /^K2GO_PROGRESS\s+(\d+)\/(\d+)\s+(.+)$/;

const forgejoRunner: (ctx: RunnerContext) => Promise<void> = async (ctx) => {
    if (!fs.existsSync(ORCH_CLONE)) throw new Error(`forgejo orchestration not found: ${ORCH_CLONE}`);

    ctx.update({ phase: 'processing', percent: -1, speed: 0, detail: 'repositories' });

    // Per-repo outcome tally (same classification the legacy /forgejo/refresh/status route uses), carried
    // to the app in the final detail so it can keep the "some blocked / already up to date" messages:
    // refresh_forgejo returns 0 even when a repo conflicts, so the job phase alone cannot say that.
    let changed = 0, problems = 0, okNoChange = 0;

    await new Promise<void>((resolve, reject) => {
        // Source the orchestration and run the full-set refresh; _fj_refresh_one skips any repo not seeded.
        const script = `. "${ORCH_CLONE}"; export FORGEJO_REPOS="$FORGEJO_REPOS_FULL"; refresh_forgejo`;
        const p = ctx.spawn('bash', ['-c', script]);
        let buf = '';
        const onData = (d: Buffer): void => {
            buf += d.toString();
            let nl: number;
            while ((nl = buf.indexOf('\n')) >= 0) {
                const line = buf.slice(0, nl).trim();
                buf = buf.slice(nl + 1);
                const m = PROGRESS.exec(line);
                if (m) {
                    // The marker is emitted BEFORE repo i is processed, so (i-1) repos are done.
                    const done = parseInt(m[1], 10) - 1;
                    const total = parseInt(m[2], 10);
                    const pct = total > 0 ? Math.max(0, Math.min(100, Math.round(done * 100 / total))) : -1;
                    ctx.update({ phase: 'processing', percent: pct, speed: 0, detail: m[3] });
                } else if (line) {
                    if (line.includes('refresh fast-forward') || line.includes('refresh merged upstream')) changed++;
                    else if (line.includes('refresh conflict') || line.includes('refresh fetch failed')
                        || line.includes('refresh ff push failed') || line.includes('refresh merge push failed')) problems++;
                    else if (line.includes('refresh up-to-date') || line.includes('refresh already ahead')) okNoChange++;
                    ctx.log(line);
                }
            }
        };
        p.stdout?.on('data', onData);
        p.stderr?.on('data', onData);
        p.on('error', reject);
        p.on('exit', (code, signal) => {
            if (signal === 'SIGKILL' || ctx.isCanceled()) return reject(new CanceledError());
            if (code === 0) return resolve();
            reject(new Error(`forgejo refresh failed (exit ${code})`));
        });
    });

    ctx.throwIfCanceled();
    // Final detail carries the outcome tally so the app can pick the right message (K2GO_SUMMARY is an
    // app<->runner token, parsed in ForgejoRepoRefresh; it is never shown as a repo name).
    const total = changed + problems + okNoChange;
    ctx.update({ phase: 'done', percent: 100, speed: 0, detail: `K2GO_SUMMARY ${changed} ${problems} ${total}` });
};

jobs.registerRunner('forgejo', forgejoRunner);

export { forgejoRunner };
