/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveRebuildState } from './rebuild-status';

const GRACE = 15_000;

// --- resolveRebuildState: the pure heal decision for the rebuild status (K2GO-451) -------------

test('resolveRebuildState: non-running states pass through untouched, no heal', () => {
    for (const s of ['idle', 'done', 'error']) {
        assert.deepEqual(resolveRebuildState(s, 'none', 0, GRACE), { state: s, heal: false });
        // the pid liveness and age are irrelevant once the file is not 'running'
        assert.deepEqual(resolveRebuildState(s, 'dead', 1_000_000, GRACE), { state: s, heal: false });
    }
});

test('resolveRebuildState: running with a dead recorded pid heals to error', () => {
    assert.deepEqual(resolveRebuildState('running', 'dead', 0, GRACE), { state: 'error', heal: true });
});

test('resolveRebuildState: running with an alive pid stays running, no heal', () => {
    // a real rebuild, or a recycled unrelated pid: never healed on the read path
    assert.deepEqual(resolveRebuildState('running', 'alive', 1_000_000, GRACE), { state: 'running', heal: false });
});

test('resolveRebuildState: running with no pid yet, within the grace, stays running', () => {
    // a just-started run that has not written its pid: must not be false-healed
    assert.deepEqual(resolveRebuildState('running', 'none', 0, GRACE), { state: 'running', heal: false });
    assert.deepEqual(resolveRebuildState('running', 'none', GRACE, GRACE), { state: 'running', heal: false }); // boundary: not > grace
});

test('resolveRebuildState: running with no pid past the grace heals to error', () => {
    // the script died before (or without) recording a pid, or never started
    assert.deepEqual(resolveRebuildState('running', 'none', GRACE + 1, GRACE), { state: 'error', heal: true });
});
