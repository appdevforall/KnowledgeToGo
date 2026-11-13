/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'fs';
import os from 'os';
import path from 'path';
import {
    readIniOption, setIniOption, parseIniBool, outboundFromText, valueFor, readState, applyOutbound,
    findSwitch, PRIVACY_SWITCHES, PrivacySwitch, applyPrivacyDefaultsOnce, isPending, startEpochMs, startTicks,
} from './privacy';
import { isRestartableService, createRestartQueue } from './services';

const KOLIBRI = findSwitch('kolibri')!;
const FORGEJO = findSwitch('forgejo')!;

// --- INI read / set --------------------------------------------------------------------------

test('readIniOption: reads the key of the named section only', () => {
    const t = '[Server]\nDISABLE_PING = False\n[Deployment]\nHTTP_PORT = 8009\nDISABLE_PING = True\n';
    assert.equal(readIniOption(t, 'Deployment', 'DISABLE_PING'), 'True');
    assert.equal(readIniOption(t, 'Server', 'DISABLE_PING'), 'False');
    assert.equal(readIniOption(t, 'Deployment', 'MISSING'), undefined);
    assert.equal(readIniOption(t, 'Nope', 'DISABLE_PING'), undefined);
});

test('readIniOption: ignores comments, strips quotes, last repeated key wins', () => {
    const t = '[cron.update_checker]\n; ENABLED = true\n# ENABLED = true\nENABLED = "true"\nENABLED = false\n';
    assert.equal(readIniOption(t, 'cron.update_checker', 'ENABLED'), 'false');
    assert.equal(readIniOption('[a]\nk = "v"\n', 'a', 'k'), 'v');
});

test('readIniOption: drops an inline comment after whitespace', () => {
    assert.equal(readIniOption('[cron.update_checker]\nENABLED = false ; offline box\n', 'cron.update_checker', 'ENABLED'), 'false');
    assert.equal(readIniOption('[Deployment]\nDISABLE_PING = True  # note\n', 'Deployment', 'DISABLE_PING'), 'True');
    assert.equal(outboundFromText(FORGEJO, '[cron.update_checker]\nENABLED = false ; offline box\n'), false);
});

test('setIniOption: replaces an existing key in place, keeps every other line', () => {
    const t = '; head\n[Deployment]\nHTTP_PORT = 8009\n  DISABLE_PING=False\n\n[Paths]\nX = 1\n';
    assert.equal(setIniOption(t, 'Deployment', 'DISABLE_PING', 'True'),
        '; head\n[Deployment]\nHTTP_PORT = 8009\n  DISABLE_PING = True\n\n[Paths]\nX = 1\n');
});

test('setIniOption: adds a missing key after the last line of its section', () => {
    const t = '[Deployment]\nHTTP_PORT = 8009\n\n[Paths]\nX = 1\n';
    assert.equal(setIniOption(t, 'Deployment', 'DISABLE_PING', 'True'),
        '[Deployment]\nHTTP_PORT = 8009\nDISABLE_PING = True\n\n[Paths]\nX = 1\n');
});

test('setIniOption: adds a missing section at the end (with or without a final newline)', () => {
    assert.equal(setIniOption('[mailer]\nENABLED = false\n', 'cron.update_checker', 'ENABLED', 'false'),
        '[mailer]\nENABLED = false\n\n[cron.update_checker]\nENABLED = false\n');
    assert.equal(setIniOption('[mailer]\nENABLED = false', 'cron.update_checker', 'ENABLED', 'false'),
        '[mailer]\nENABLED = false\n\n[cron.update_checker]\nENABLED = false\n');
    assert.equal(setIniOption('', 'Deployment', 'DISABLE_PING', 'True'), '[Deployment]\nDISABLE_PING = True\n');
});

test('setIniOption: an empty section gets the key right after its header', () => {
    assert.equal(setIniOption('[Deployment]\n[Paths]\n', 'Deployment', 'DISABLE_PING', 'True'),
        '[Deployment]\nDISABLE_PING = True\n[Paths]\n');
});

test('setIniOption: keeps CRLF line ends', () => {
    assert.equal(setIniOption('[Deployment]\r\nHTTP_PORT = 8009\r\n', 'Deployment', 'DISABLE_PING', 'True'),
        '[Deployment]\r\nHTTP_PORT = 8009\r\nDISABLE_PING = True\r\n');
});

test('parseIniBool: the spellings configobj and go-ini accept; undefined when unclear', () => {
    for (const v of ['True', 'true', 'YES', 'on', '1', ' t ']) assert.equal(parseIniBool(v), true, v);
    for (const v of ['False', 'false', 'no', 'OFF', '0', 'f']) assert.equal(parseIniBool(v), false, v);
    assert.equal(parseIniBool('maybe'), undefined);
    assert.equal(parseIniBool(undefined), undefined);
});

// --- Switch semantics ------------------------------------------------------------------------

test('outboundFromText: Kolibri DISABLE_PING is inverted; missing means the service default (on)', () => {
    assert.equal(outboundFromText(KOLIBRI, '[Deployment]\nDISABLE_PING = True\n'), false);
    assert.equal(outboundFromText(KOLIBRI, '[Deployment]\nDISABLE_PING = False\n'), true);
    assert.equal(outboundFromText(KOLIBRI, '[Deployment]\nHTTP_PORT = 8009\n'), true);
});

test('outboundFromText: Forgejo [cron.update_checker] ENABLED is direct; missing means on', () => {
    assert.equal(outboundFromText(FORGEJO, '[cron.update_checker]\nENABLED = false\n'), false);
    assert.equal(outboundFromText(FORGEJO, '[cron.update_checker]\nENABLED = true\n'), true);
    assert.equal(outboundFromText(FORGEJO, '[server]\nHTTP_PORT = 3300\n'), true);
});

test('valueFor: each file keeps the spelling of its own tool', () => {
    assert.equal(valueFor(KOLIBRI, false), 'True');
    assert.equal(valueFor(KOLIBRI, true), 'False');
    assert.equal(valueFor(FORGEJO, false), 'false');
    assert.equal(valueFor(FORGEJO, true), 'true');
});

test('every switch is a known restartable service (the restart allowlist covers it)', () => {
    for (const sw of PRIVACY_SWITCHES) assert.equal(isRestartableService(sw.service), true, sw.service);
    assert.equal(findSwitch('nginx'), undefined);
    assert.equal(findSwitch('kolibri; rm -rf /'), undefined);
});

// --- File read / write -----------------------------------------------------------------------

function tmpSwitch(base: PrivacySwitch, content: string | null): PrivacySwitch {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'k2go-privacy-'));
    const file = path.join(dir, 'conf.ini');
    if (content !== null) fs.writeFileSync(file, content, { mode: 0o640 });
    return { ...base, file };
}

test('readState: a missing file means not installed', () => {
    const sw = tmpSwitch(KOLIBRI, null);
    assert.deepEqual(readState(sw), { service: 'kolibri', installed: false, outbound: false, pending: false });
});

test('applyOutbound: writes the switch, keeps the rest of the file, reports the change', () => {
    const sw = tmpSwitch(KOLIBRI, '[Deployment]\nHTTP_PORT = 8009\n');
    assert.equal(readState(sw).outbound, true);
    const r = applyOutbound(sw, false);
    assert.deepEqual(r, { state: { service: 'kolibri', installed: true, outbound: false, pending: false }, changed: true });
    assert.equal(fs.readFileSync(sw.file, 'utf8'), '[Deployment]\nHTTP_PORT = 8009\nDISABLE_PING = True\n');
    assert.equal(fs.existsSync(sw.file + '.k2go-tmp'), false);
});

test('applyOutbound: the same value is a no-op (no write, no restart)', () => {
    const sw = tmpSwitch(FORGEJO, '[cron.update_checker]\nENABLED = false\n');
    const before = fs.statSync(sw.file).mtimeMs;
    const r = applyOutbound(sw, false);
    assert.equal(r.changed, false);
    assert.equal(r.state.outbound, false);
    assert.equal(fs.statSync(sw.file).mtimeMs, before);
});

test('applyOutbound: turning Forgejo back on writes ENABLED = true', () => {
    const sw = tmpSwitch(FORGEJO, '[cron.update_checker]\nENABLED = false\n');
    assert.equal(applyOutbound(sw, true).state.outbound, true);
    assert.equal(fs.readFileSync(sw.file, 'utf8'), '[cron.update_checker]\nENABLED = true\n');
});

test('readState: an unreadable file is an error, not "not installed"', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'k2go-privacy-'));
    assert.throws(() => readState({ ...KOLIBRI, file: dir }));   // EISDIR
});

test('applyOutbound: a symlinked config keeps its link; the target gets the change', (t) => {
    const sw = tmpSwitch(FORGEJO, '[cron.update_checker]\nENABLED = true\n');
    const link = sw.file + '.link';
    try { fs.symlinkSync(sw.file, link); } catch { t.skip('no symlink permission'); return; }
    applyOutbound({ ...sw, file: link }, false);
    assert.equal(fs.lstatSync(link).isSymbolicLink(), true);
    assert.equal(fs.readFileSync(sw.file, 'utf8'), '[cron.update_checker]\nENABLED = false\n');
});

test('applyOutbound: throws when the service is not installed (no file is made)', () => {
    const sw = tmpSwitch(FORGEJO, null);
    assert.throws(() => applyOutbound(sw, false));
    assert.equal(fs.existsSync(sw.file), false);
});

// --- Restart queue: one restart per service at a time ----------------------------------------

function fakeQueue() {
    const runs: string[] = [];
    const pending = new Map<string, () => void>();
    const request = createRestartQueue((svc, done) => { runs.push(svc); pending.set(svc, done); });
    const finish = (svc: string) => { const d = pending.get(svc)!; pending.delete(svc); d(); };
    return { runs, request, finish };
}

test('restart queue: a request while one runs waits, then runs once', () => {
    const q = fakeQueue();
    q.request('kolibri');
    q.request('kolibri');
    q.request('kolibri');                  // folded into the same one follow-up run
    assert.deepEqual(q.runs, ['kolibri']);
    q.finish('kolibri');
    assert.deepEqual(q.runs, ['kolibri', 'kolibri']);
    q.finish('kolibri');
    assert.deepEqual(q.runs, ['kolibri', 'kolibri']);
});

test('restart queue: different services do not wait for each other', () => {
    const q = fakeQueue();
    q.request('kolibri');
    q.request('forgejo');
    assert.deepEqual(q.runs, ['kolibri', 'forgejo']);
});

test('restart queue: a request after the run ended starts a new run at once', () => {
    const q = fakeQueue();
    q.request('forgejo');
    q.finish('forgejo');
    q.request('forgejo');
    assert.deepEqual(q.runs, ['forgejo', 'forgejo']);
});

// --- One-time privacy defaults on update -------------------------------------------------------

function defaultsBox(kolibri: string | null, forgejo: string | null) {
    const k = tmpSwitch(KOLIBRI, kolibri);
    const f = tmpSwitch(FORGEJO, forgejo);
    const marker = path.join(path.dirname(k.file), 'privacy-defaults.done');
    return { k, f, marker, run: () => applyPrivacyDefaultsOnce([k, f], marker) };
}

test('defaults once: first run turns both off and writes the marker (no restart: next start applies)', () => {
    const b = defaultsBox('[Deployment]\nHTTP_PORT = 8009\n', '[server]\nHTTP_PORT = 3300\n');
    assert.deepEqual(b.run(), { ran: true, changed: ['kolibri', 'forgejo'] });
    assert.equal(readState(b.k).outbound, false);
    assert.equal(readState(b.f).outbound, false);
    assert.equal(fs.existsSync(b.marker), true);
});

test('defaults once: with the marker it does nothing, so a switch the user turned on stays on', () => {
    const b = defaultsBox('[Deployment]\nDISABLE_PING = True\n', '[cron.update_checker]\nENABLED = false\n');
    b.run();
    applyOutbound(b.k, true);                       // the user turns Kolibri on after the update
    assert.deepEqual(b.run(), { ran: false, changed: [] });
    assert.equal(readState(b.k).outbound, true);
});

test('defaults once: a service that is not installed is skipped; an OFF switch is not reported', () => {
    const b = defaultsBox('[Deployment]\nDISABLE_PING = True\n', null);
    assert.deepEqual(b.run(), { ran: true, changed: [] });
    assert.equal(fs.existsSync(b.f.file), false);   // no config is made for a missing service
    assert.equal(fs.existsSync(b.marker), true);
});

test('defaults once: a failed config write leaves no marker, so the next start tries again', () => {
    const b = defaultsBox('[Deployment]\nHTTP_PORT = 8009\n', null);
    const unreadable = { ...b.f, file: path.dirname(b.k.file) };   // a directory: read throws EISDIR
    assert.throws(() => applyPrivacyDefaultsOnce([b.k, unreadable], b.marker));
    assert.equal(fs.existsSync(b.marker), false);
});

// --- Pending restart: derived from config mtime vs the running process start --------------------

test('isPending: a config newer than the running process is pending; no process is never pending', () => {
    assert.equal(isPending(2_000, 1_000), true);
    assert.equal(isPending(1_000, 2_000), false);   // the service started after the change: applied
    assert.equal(isPending(2_000, null), false);    // not running: its next start reads the config
});

test('startTicks: field 22 of /proc/<pid>/stat, also when the name has spaces and parens', () => {
    const fields = Array.from({ length: 30 }, (_, i) => String(i + 3));
    fields[19] = '5000';
    assert.equal(startTicks('1234 (python3 (k) x) ' + fields.join(' ')), 5000);
    assert.equal(startTicks('garbage'), null);
});

test('startEpochMs: measured against this process (no /proc/uptime in the box)', () => {
    // This process started at t=1_000_000 ms with 3000 ticks; the service has 5000 ticks: 20 s later.
    assert.equal(startEpochMs(5000, 3000, 1_000_000), 1_020_000);
    assert.equal(startEpochMs(1000, 3000, 1_000_000), 980_000);
});
