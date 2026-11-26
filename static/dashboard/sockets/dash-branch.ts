// The branch that this box's dash-node build came from: one owner for the fact (ENG-1).
// The record is a one-line file next to the live build. Only the installer (first install) and the
// rebuild finalize step (after the live check passed) write it, so it always names the branch of
// the build that runs. No file = 'main', so a main box behaves as before. Each call reads the file:
// no cache. Design: k2go-school notes, self-update-branch.md.
import fs from 'fs';

export const BRANCH_RECORD = '/library/dashboard/.k2go-branch';
export const DEFAULT_BRANCH = 'main';

// The same charset as the bake's --k2go-ref, plus: no leading '-' (git option injection) and no '..'.
export function isValidBranch(name: string): boolean {
    return /^[A-Za-z0-9._/-]+$/.test(name) && !name.startsWith('-') && !name.includes('..')
        && name.length <= 200;
}

export type TrackedBranch =
    | { ok: true; branch: string }
    | { ok: false; error: string };

/** The tracked branch. A bad record is an error, never a silent fallback to main. */
export function readTrackedBranch(file: string = BRANCH_RECORD): TrackedBranch {
    let text: string;
    try {
        text = fs.readFileSync(file, 'utf8');
    } catch (e: any) {
        if (e?.code === 'ENOENT') return { ok: true, branch: DEFAULT_BRANCH };
        return { ok: false, error: 'branch record unreadable' };
    }
    const branch = text.trim();
    if (!branch) return { ok: true, branch: DEFAULT_BRANCH };
    if (!isValidBranch(branch)) return { ok: false, error: 'branch record invalid' };
    return { ok: true, branch };
}

/** True when git says that the branch is not on the remote (deleted or never pushed). */
export function isBranchMissing(gitError: string): boolean {
    return /couldn't find remote ref/i.test(gitError);
}
