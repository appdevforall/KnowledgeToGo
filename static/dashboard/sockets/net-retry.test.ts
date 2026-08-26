/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import { withRetry, Aborted } from './net-retry';

// A sleep that spends no real time, so the backoff logic is exercised instantly.
const noSleep = async () => { /* immediate */ };

test('withRetry: returns on the first success, no retries', async () => {
    let calls = 0;
    const out = await withRetry(async () => { calls++; return 'ok'; }, { sleep: noSleep });
    assert.equal(out, 'ok');
    assert.equal(calls, 1);
});

test('withRetry: ADFA-4893 delaysMs drives the exact schedule (3/6/9/18/36), tries defaults to N+1', async () => {
    const waited: number[] = [];
    const onRetried: number[] = [];
    let calls = 0;
    await assert.rejects(
        withRetry(async () => { calls++; throw new Error('down'); }, {
            delaysMs: [3000, 6000, 9000, 18000, 36000],
            sleep: async (ms: number) => { waited.push(ms); },
            onRetry: ({ attempt }) => { onRetried.push(attempt); },
        }),
        /down/,
    );
    assert.equal(calls, 6);                                        // N+1 attempts = 5 visible reconnects
    assert.deepEqual(waited, [3000, 6000, 9000, 18000, 36000]);    // exact schedule, no jitter
    assert.deepEqual(onRetried, [1, 2, 3, 4, 5]);                  // "Reconnecting n/5" ordinals
});

test('withRetry: retries a transient failure then succeeds', async () => {
    let calls = 0;
    const out = await withRetry(async (attempt) => {
        calls++;
        if (attempt < 3) throw new Error('ECONNRESET');
        return attempt;
    }, { tries: 4, sleep: noSleep });
    assert.equal(out, 3);
    assert.equal(calls, 3);
});

test('withRetry: exhausts attempts and throws the last error', async () => {
    let calls = 0;
    await assert.rejects(
        withRetry(async () => { calls++; throw new Error('still down'); }, { tries: 3, sleep: noSleep }),
        /still down/,
    );
    assert.equal(calls, 3);
});

test('withRetry: a non-transient error is not retried', async () => {
    let calls = 0;
    await assert.rejects(
        withRetry(async () => { calls++; throw new Error('HTTP 404'); }, {
            tries: 5, sleep: noSleep,
            isTransient: (e) => !(e instanceof Error && /HTTP 4\d\d/.test(e.message)),
        }),
        /HTTP 404/,
    );
    assert.equal(calls, 1);   // 404 is fatal — tried once, never retried
});

test('withRetry: cancellation via isCanceled throws Aborted and stops trying', async () => {
    let calls = 0;
    let canceled = false;
    await assert.rejects(
        withRetry(async () => { calls++; canceled = true; throw new Error('boom'); }, {
            tries: 5, sleep: noSleep, isCanceled: () => canceled,
        }),
        (e) => e instanceof Aborted,
    );
    assert.equal(calls, 1);   // canceled after the first failure -> no second attempt
});

test('withRetry: an aborted signal during backoff throws Aborted', async () => {
    const ac = new AbortController();
    let calls = 0;
    // sleep aborts the signal mid-backoff, so the loop must not run a second attempt.
    const abortingSleep = async () => { ac.abort(); throw new Aborted(); };
    await assert.rejects(
        withRetry(async () => { calls++; throw new Error('down'); }, {
            tries: 5, signal: ac.signal, sleep: abortingSleep,
        }),
        (e) => e instanceof Aborted,
    );
    assert.equal(calls, 1);
});
