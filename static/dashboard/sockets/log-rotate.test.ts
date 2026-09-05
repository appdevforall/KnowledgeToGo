/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import { updateStreaks } from './log-rotate';

test('updateStreaks: a first-time firehose starts at occurrence 1', () => {
    const next = updateStreaks(new Set(['/var/log/php8.4-fpm.log']), new Map());
    assert.equal(next.get('/var/log/php8.4-fpm.log'), 1);
});

test('updateStreaks: a recurring firehose increments from its previous count', () => {
    const prev = new Map([['/var/log/php8.4-fpm.log', 2]]);
    const next = updateStreaks(new Set(['/var/log/php8.4-fpm.log']), prev);
    assert.equal(next.get('/var/log/php8.4-fpm.log'), 3);
});

test('updateStreaks: a path not firehosing this pass is dropped (no stale/leaked entry)', () => {
    // gone.log firehosed before but is not in this pass (deleted, renamed, or now sane) → must not linger.
    const prev = new Map([
        ['/var/log/gone.log', 5],
        ['/var/log/dash-node.log', 1],
    ]);
    const next = updateStreaks(new Set(['/var/log/dash-node.log']), prev);
    assert.equal(next.has('/var/log/gone.log'), false);
    assert.equal(next.get('/var/log/dash-node.log'), 2);
    assert.equal(next.size, 1);
});

test('updateStreaks: an empty firehose set clears everything (a calm tick leaks nothing)', () => {
    const prev = new Map([['/var/log/a.log', 3]]);
    const next = updateStreaks(new Set<string>(), prev);
    assert.equal(next.size, 0);
});
