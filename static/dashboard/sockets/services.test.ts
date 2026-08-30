/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import { isRestartableService, RESTARTABLE_SERVICES } from './services';
import { dueForRestart, classifyProbe } from './service-heal';

// --- isRestartableService: the /system/service/:svc/restart allowlist -------------------------

test('isRestartableService: accepts every upstream content service', () => {
    for (const svc of RESTARTABLE_SERVICES) {
        assert.equal(isRestartableService(svc), true, svc);
    }
    // The concrete cases §10 wires first.
    assert.equal(isRestartableService('kiwix'), true);
    assert.equal(isRestartableService('kolibri'), true);
});

test('isRestartableService: rejects dash-node (k2go\'s own service, not a content service)', () => {
    // Bouncing dash-node would kill the process serving the request and the heal loop; its
    // restart lives in the rebuild path, never this endpoint.
    assert.equal(isRestartableService('dash-node'), false);
});

test('isRestartableService: rejects unknown, empty and shell-metacharacter names', () => {
    assert.equal(isRestartableService(''), false);
    assert.equal(isRestartableService('unknown'), false);
    assert.equal(isRestartableService('KIWIX'), false);          // exact match only
    assert.equal(isRestartableService('kiwix '), false);         // no trailing space
    assert.equal(isRestartableService('kiwix; rm -rf /'), false);
    assert.equal(isRestartableService('kiwix && reboot'), false);
    assert.equal(isRestartableService('../kiwix'), false);
});

// --- dueForRestart: the per-service cooldown that bounds restarts ------------------------------

test('dueForRestart: true on the first attempt (no prior restart)', () => {
    assert.equal(dueForRestart(0, 1_000_000, 60_000), true);
});

test('dueForRestart: false while still inside the cooldown window', () => {
    const now = 1_000_000;
    assert.equal(dueForRestart(now - 59_999, now, 60_000), false);
});

test('dueForRestart: true once the cooldown has elapsed (boundary is inclusive)', () => {
    const now = 1_000_000;
    assert.equal(dueForRestart(now - 60_000, now, 60_000), true);
    assert.equal(dueForRestart(now - 120_000, now, 60_000), true);
});

// --- classifyProbe: heal only a present-but-wedged service, never an absent one ----------------

test('classifyProbe: 2xx is ok (serving), never healed', () => {
    assert.equal(classifyProbe(200), 'ok');
    assert.equal(classifyProbe(204), 'ok');
});

test('classifyProbe: 404 is absent (not installed) — the not-installed vs not-running split', () => {
    // A box without this content fronts no such path; healing would restart a service that isn't
    // there, every cooldown, forever. Must be left alone.
    assert.equal(classifyProbe(404), 'absent');
});

test('classifyProbe: a wedged upstream (5xx) and no reply (null) both heal', () => {
    assert.equal(classifyProbe(502), 'down');
    assert.equal(classifyProbe(503), 'down');
    assert.equal(classifyProbe(504), 'down');
    assert.equal(classifyProbe(null), 'down');   // timeout / connection refused
});
