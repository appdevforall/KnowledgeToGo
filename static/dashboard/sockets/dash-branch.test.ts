/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { isValidBranch, readTrackedBranch, isBranchMissing, DEFAULT_BRANCH } from './dash-branch';

function tmpRecord(content: string | null): string {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dash-branch-'));
    const file = path.join(dir, '.k2go-branch');
    if (content !== null) fs.writeFileSync(file, content);
    return file;
}

test('isValidBranch: accepts normal branch names', () => {
    for (const b of ['main', 'feature/k2go-school', 'fix/K2GO-123-x_y.z']) assert.equal(isValidBranch(b), true, b);
});

test('isValidBranch: refuses option injection, shell text and parent refs', () => {
    for (const b of ['', '-x', '--upload-pack=sh', 'a b', 'a;b', "a'b", 'a$(id)', 'a..b', 'a\nb']) {
        assert.equal(isValidBranch(b), false, JSON.stringify(b));
    }
});

test('readTrackedBranch: no record = main (a main box does not change)', () => {
    assert.deepEqual(readTrackedBranch(tmpRecord(null)), { ok: true, branch: DEFAULT_BRANCH });
});

test('readTrackedBranch: an empty record = main', () => {
    assert.deepEqual(readTrackedBranch(tmpRecord('\n')), { ok: true, branch: 'main' });
});

test('readTrackedBranch: reads the recorded branch, trims the newline', () => {
    assert.deepEqual(readTrackedBranch(tmpRecord('feature/k2go-school\n')), { ok: true, branch: 'feature/k2go-school' });
});

test('readTrackedBranch: a bad record is an error, not a fallback to main', () => {
    const r = readTrackedBranch(tmpRecord('main; rm -rf /'));
    assert.equal(r.ok, false);
});

test('readTrackedBranch: a record that is not a file is an error', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dash-branch-'));
    assert.equal(readTrackedBranch(dir).ok, false);
});

test('isBranchMissing: matches the git message for a deleted branch only', () => {
    assert.equal(isBranchMissing("fatal: couldn't find remote ref feature/gone"), true);
    assert.equal(isBranchMissing('fatal: unable to access ...: Could not resolve host: github.com'), false);
    assert.equal(isBranchMissing('git timed out'), false);
});
