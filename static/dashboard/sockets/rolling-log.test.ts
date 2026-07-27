/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import { RollingLog } from './rolling-log';

test('RollingLog: incremental cursor returns only new lines', () => {
    const log = new RollingLog();
    log.append('j', 'a\nb\nc');
    let s = log.getSince('j', 0);
    assert.deepEqual(s.lines, ['a', 'b', 'c']);
    assert.equal(s.from, 0);
    assert.equal(s.next, 3);
    assert.equal(s.truncated, false);
    log.append('j', 'd\ne');
    s = log.getSince('j', 3);
    assert.deepEqual(s.lines, ['d', 'e']);
    assert.equal(s.next, 5);
});

test('RollingLog: splits on bare CR (pmtiles progress) and drops empties', () => {
    const log = new RollingLog();
    log.append('p', 'fetching 0%\rfetching 14%\rfetching 100%\n');
    assert.deepEqual(log.getSince('p', 0).lines, ['fetching 0%', 'fetching 14%', 'fetching 100%']);
});

test('RollingLog: caps lines and reports truncated when since fell off', () => {
    const log = new RollingLog(3, 50);   // keep only the last 3 lines
    log.append('j', '1\n2\n3\n4\n5');
    const s = log.getSince('j', 0);
    assert.deepEqual(s.lines, ['3', '4', '5']);
    assert.equal(s.from, 2);
    assert.equal(s.next, 5);
    assert.equal(s.truncated, true);
});

test('RollingLog: since ahead of total (post-restart) resets via truncated', () => {
    const log = new RollingLog();
    log.append('j', 'x');
    const s = log.getSince('j', 999);
    assert.equal(s.next, 1);
    assert.equal(s.truncated, true);
    assert.deepEqual(s.lines, []);
});

test('RollingLog: evicts the oldest job beyond maxJobs', () => {
    const log = new RollingLog(500, 2);
    log.append('a', 'x');
    log.append('b', 'y');
    log.append('c', 'z');   // evicts 'a'
    assert.deepEqual(log.getSince('a', 0).lines, []);
    assert.deepEqual(log.getSince('c', 0).lines, ['z']);
});

test('RollingLog: unknown id is empty, not an error', () => {
    const log = new RollingLog();
    assert.deepEqual(log.getSince('nope', 0), { from: 0, next: 0, lines: [], truncated: false });
});
