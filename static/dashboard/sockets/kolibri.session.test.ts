// sockets/kolibri.session.test.ts
//
// Tests for the PURE helpers on the Kolibri path, in the same line as
// maps.socket.test.ts and rolling-log.test.ts: no network, no SQLite, no Kolibri.
// They cover exactly the traps that make the first attempt fail — non-standard
// cookie names, CSRF token rotation, percentage 0-1 vs 0-100, and the payload
// Kolibri rejects if 'peer': null is sent.
//
// Run:  npm test   (add this file to the "test" script in package.json)
//
// Imports ONLY from kolibri.session.ts and kolibri.map.ts. Neither of them pulls
// in jobs.ts, so the better-sqlite3 native binary is not needed — CI installs
// with `npm ci --ignore-scripts` and does not compile it.
/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';

import { cookieValue, mergeCookies, matchesOrigin, KolibriApiError } from './kolibri.session';
import {
    mapPhase, mapPercent, normalizeUuid, buildTaskPayload, overallPercent, sampleSpeed,
    toRemoteChannel, failureMessage,
} from './kolibri.map';

// ─── cookieValue ─────────────────────────────────────────────────────────────

test('cookieValue finds the Kolibri CSRF cookie, which is not called csrftoken', () => {
    const setCookies = [
        'kolibri_csrftoken=abc123; expires=Fri, 01 Jan 2027 00:00:00 GMT; Path=/; SameSite=Lax',
        'kolibri=sess789; expires=Fri, 01 Jan 2027 00:00:00 GMT; HttpOnly; Path=/',
    ];
    assert.equal(cookieValue(setCookies, 'kolibri_csrftoken'), 'abc123');
    assert.equal(cookieValue(setCookies, 'kolibri'), 'sess789');
    // The standard Django names do NOT exist in Kolibri: anyone looking for them
    // must get null instead of a false positive.
    assert.equal(cookieValue(setCookies, 'csrftoken'), null);
    assert.equal(cookieValue(setCookies, 'sessionid'), null);
});

test('cookieValue tolerates odd headers', () => {
    assert.equal(cookieValue([], 'kolibri'), null);
    assert.equal(cookieValue(['no-equals-sign'], 'kolibri'), null);
    // A value with '=' inside it (base64) must be kept whole.
    assert.equal(cookieValue(['kolibri=a=b=c; Path=/'], 'kolibri'), 'a=b=c');
});

// ─── mergeCookies ────────────────────────────────────────────────────────────

test('mergeCookies applies the CSRF token rotation after login', () => {
    // Django rotates the token on authentication: the new value must win, or the
    // first POST afterwards would fail with 403.
    const before = 'kolibri_csrftoken=viejo';
    const afterLogin = ['kolibri_csrftoken=fresh; Path=/', 'kolibri=session; Path=/'];
    const merged = mergeCookies(before, afterLogin);
    assert.match(merged, /kolibri_csrftoken=fresh/);
    assert.doesNotMatch(merged, /viejo/);
    assert.match(merged, /kolibri=session/);
});

test('mergeCookies keeps previous cookies that were not replaced', () => {
    const merged = mergeCookies('other=kept; kolibri_csrftoken=v1', ['kolibri_csrftoken=v2']);
    assert.match(merged, /other=kept/);
    assert.match(merged, /kolibri_csrftoken=v2/);
});

test('mergeCookies starts from empty without generating junk', () => {
    const merged = mergeCookies('', ['kolibri=s; HttpOnly']);
    assert.equal(merged, 'kolibri=s');
});

// ─── mapPhase ────────────────────────────────────────────────────────────────

test('mapPhase covers the nine Kolibri states', () => {
    assert.equal(mapPhase('PENDING'), 'queued');
    assert.equal(mapPhase('SCHEDULED'), 'queued');
    assert.equal(mapPhase('QUEUED'), 'queued');
    assert.equal(mapPhase('SELECTED'), 'queued');
    assert.equal(mapPhase('RUNNING'), 'downloading');
    assert.equal(mapPhase('COMPLETED'), 'done');
    assert.equal(mapPhase('FAILED'), 'error');
    assert.equal(mapPhase('CANCELING'), 'canceled');
    assert.equal(mapPhase('CANCELED'), 'canceled');
});

test('mapPhase degrades an unknown state without breaking', () => {
    assert.equal(mapPhase('SOMETHING_NEW'), 'processing');
});

// ─── mapPercent ──────────────────────────────────────────────────────────────

test('mapPercent converts the Kolibri 0-1 float to a 0-100 integer', () => {
    // This is the easiest mix-up to make: percentage is NOT 0-100.
    assert.equal(mapPercent(0), 0);
    assert.equal(mapPercent(0.5), 50);
    assert.equal(mapPercent(0.333), 33);
    assert.equal(mapPercent(1), 100);
});

test('mapPercent returns -1 (indeterminate) when there is no data', () => {
    assert.equal(mapPercent(null), -1);
    assert.equal(mapPercent(undefined), -1);
    assert.equal(mapPercent(NaN), -1);
});

test('mapPercent clamps out-of-range values', () => {
    assert.equal(mapPercent(1.5), 100);
    assert.equal(mapPercent(-0.2), 0);
});

// ─── normalizeUuid ───────────────────────────────────────────────────────────

test('normalizeUuid accepts 32-char hex and also the hyphenated form', () => {
    const hex = '95a52b386f2c485cb97dd60901674a98';
    assert.equal(normalizeUuid(hex), hex);
    assert.equal(normalizeUuid('95A52B386F2C485CB97DD60901674A98'), hex);
    assert.equal(normalizeUuid('95a52b38-6f2c-485c-b97d-d60901674a98'), hex);
    assert.equal(normalizeUuid(`  ${hex}  `), hex);
});

test('normalizeUuid rejects tokens: they must be resolved before queueing', () => {
    // A proquint token is not a channel_id. Passing it through would end in a 404
    // from the downloader with no clear message.
    assert.equal(normalizeUuid('bisan-sukod'), null);
    assert.equal(normalizeUuid('bisansukod'), null);
    assert.equal(normalizeUuid(''), null);
    assert.equal(normalizeUuid('zz'.repeat(16)), null);
    assert.equal(normalizeUuid(undefined), null);
    assert.equal(normalizeUuid(42), null);
});

// ─── buildTaskPayload ────────────────────────────────────────────────────────

const CH = '95a52b386f2c485cb97dd60901674a98';

test('buildTaskPayload uses remoteimport and always sends channel_name', () => {
    const p = buildTaskPayload({ channelId: CH, channelName: 'Khan Academy' });
    assert.equal(p.type, 'kolibri.core.content.tasks.remoteimport');
    assert.equal(p.channel_id, CH);
    // Mandatory: without it Kolibri answers 400, even though it is decorative.
    assert.equal(p.channel_name, 'Khan Academy');
    assert.equal(p.fail_on_error, true);
});

test('buildTaskPayload NEVER includes peer', () => {
    // 'peer' is a PrimaryKeyRelatedField without allow_null: sending null gives 400
    // "This field may not be null". The key has to be omitted.
    const p = buildTaskPayload({ channelId: CH, channelName: 'x' });
    assert.equal('peer' in p, false);
});

test('buildTaskPayload omits node_ids when there is no selection', () => {
    // Critical Kolibri distinction: node_ids=[] means ZERO NODES, whereas absent
    // means THE WHOLE CHANNEL. Sending an empty list would download nothing with
    // apparent success.
    const p = buildTaskPayload({ channelId: CH, channelName: 'x', nodeIds: [] });
    assert.equal('node_ids' in p, false);

    const q = buildTaskPayload({ channelId: CH, channelName: 'x', nodeIds: undefined });
    assert.equal('node_ids' in q, false);
});

test('buildTaskPayload includes the selection when there is one', () => {
    const nodes = ['aaaa1111bbbb2222cccc3333dddd4444'];
    const p = buildTaskPayload({ channelId: CH, channelName: 'x', nodeIds: nodes });
    assert.deepEqual(p.node_ids, nodes);
});

test('buildTaskPayload only sends all_thumbnails when requested', () => {
    const omitted = buildTaskPayload({ channelId: CH, channelName: 'x' });
    assert.equal('all_thumbnails' in omitted, false);

    const requested = buildTaskPayload({ channelId: CH, channelName: 'x', allThumbnails: true });
    assert.equal(requested.all_thumbnails, true);
});

test('buildTaskPayload omits an empty exclude_node_ids', () => {
    const p = buildTaskPayload({ channelId: CH, channelName: 'x', excludeNodeIds: [] });
    assert.equal('exclude_node_ids' in p, false);
});

// ─── overallPercent ──────────────────────────────────────────────────────────

test('overallPercent leaves the value alone when there is a single channel', () => {
    assert.equal(overallPercent(1, 1, 42), 42);
    assert.equal(overallPercent(1, 1, -1), -1);
});

test('overallPercent splits the bar into per-channel bands', () => {
    // With 3 channels, the first at 0% is 0 overall and at 100% is 33 overall; so
    // the bar rises once instead of restarting three times.
    assert.equal(overallPercent(1, 3, 0), 0);
    assert.equal(overallPercent(1, 3, 100), 33);
    assert.equal(overallPercent(2, 3, 0), 33);
    assert.equal(overallPercent(3, 3, 50), 83);
});

test('overallPercent is clamped to 99: the end of the runner sets the 100', () => {
    assert.equal(overallPercent(3, 3, 100), 99);
});

test('overallPercent propagates the indeterminate value', () => {
    assert.equal(overallPercent(2, 3, -1), -1);
});

// ─── sampleSpeed ─────────────────────────────────────────────────────────────

test('sampleSpeed computes bytes per second between two samples', () => {
    // 1 MiB in 2 s → ~524 288 B/s
    assert.equal(sampleSpeed(0, 1048576, 1000, 3000), 524288);
});

test('sampleSpeed returns null with no progress, so the previous speed stands', () => {
    // Returning 0 would flicker the UI to "0 B/s" on every poll with no progress.
    assert.equal(sampleSpeed(1000, 1000, 0, 2000), null);
    assert.equal(sampleSpeed(1000, 500, 0, 2000), null);
});

test('sampleSpeed does not divide by zero with two simultaneous samples', () => {
    const v = sampleSpeed(0, 100, 5000, 5000);
    assert.ok(v !== null && Number.isFinite(v) && v > 0);
});

// ─── matchesOrigin ───────────────────────────────────────────────────────────
// Decides whether importcontent can resolve the origin: with no match it drops to
// the fallback that calls ifaddr, blocked by netlink under proot.

const STUDIO = 'https://studio.learningequality.org';

test('matchesOrigin accepts the row with and without a trailing slash', () => {
    assert.equal(matchesOrigin([{ base_url: STUDIO }], STUDIO), true);
    assert.equal(matchesOrigin([{ base_url: `${STUDIO}/` }], STUDIO), true);
    // And also if the searched value carries the slash and the stored one does not.
    assert.equal(matchesOrigin([{ base_url: STUDIO }], `${STUDIO}/`), true);
});

test('matchesOrigin does not depend on location_type: any row will do', () => {
    // known_location_for_address() does not filter by type either, so a 'static'
    // one created by us serves as well as the 'reserved' ones IIAB seeds.
    assert.equal(matchesOrigin([{ base_url: STUDIO, nickname: 'K2Go content origin' }], STUDIO), true);
});

test('matchesOrigin finds the match among several rows', () => {
    const rows = [
        { base_url: 'http://192.168.1.50:8080' },
        { base_url: 'https://kolibridataportal.learningequality.org' },
        { base_url: `${STUDIO}/` },
    ];
    assert.equal(matchesOrigin(rows, STUDIO), true);
});

test('matchesOrigin rejects when there is no row for the origin', () => {
    assert.equal(matchesOrigin([], STUDIO), false);
    assert.equal(matchesOrigin([{ base_url: 'http://192.168.1.50:8080' }], STUDIO), false);
    // A prefix is not a match.
    assert.equal(matchesOrigin([{ base_url: 'https://studio.learningequality.org.evil.test' }], STUDIO), false);
});

test('matchesOrigin tolerates rows without base_url', () => {
    assert.equal(matchesOrigin([{}, { base_url: undefined }, { base_url: STUDIO }], STUDIO), true);
    assert.equal(matchesOrigin([{}, { base_url: undefined }], STUDIO), false);
});

// ─── KolibriApiError ─────────────────────────────────────────────────────────

test('KolibriApiError tells an expired session from other failures', () => {
    // Django answers 403 (not 401) when the session died and the CSRF is stale;
    // both must trigger the re-login from the poll.
    assert.equal(new KolibriApiError(401, 'x').isAuthExpired, true);
    assert.equal(new KolibriApiError(403, 'x').isAuthExpired, true);
    assert.equal(new KolibriApiError(404, 'x').isAuthExpired, false);
    assert.equal(new KolibriApiError(500, 'x').isAuthExpired, false);
    assert.equal(new KolibriApiError(503, 'x').isAuthExpired, false);
});

test('KolibriApiError keeps the status and is an Error', () => {
    const e = new KolibriApiError(418, 'i am a teapot');
    assert.ok(e instanceof Error);
    assert.equal(e.status, 418);
    assert.equal(e.name, 'KolibriApiError');
    assert.match(e.message, /teapot/);
});

// ─── toRemoteChannel ─────────────────────────────────────────────────────────

test('toRemoteChannel reads the names Kolibri\'s proxy uses', () => {
    // The bug this guards: /api/content/remotechannel/ renames Studio's fields,
    // and reading only Studio's names returned null for size and resource count
    // on every row. Caught on a device, not in review.
    const c = toRemoteChannel({
        id: 'c150ea1d69495d37b5b0ac6f017e9bfb',
        name: '3asafeer',
        total_resources: 160,
        total_file_size: 3417837270,
        lang_code: 'ar',
        version: 9,
    });
    assert.equal(c.totalResources, 160);
    assert.equal(c.publishedSize, 3417837270);
    assert.equal(c.language, 'ar');
    assert.equal(c.version, 9);
});

test('toRemoteChannel still reads the names Studio uses', () => {
    // Both spellings have to work: the same mapper serves the proxy and Studio.
    const c = toRemoteChannel({
        id: 'c150ea1d69495d37b5b0ac6f017e9bfb',
        total_resource_count: 37,
        published_size: 1740285,
        language: 'en',
    });
    assert.equal(c.totalResources, 37);
    assert.equal(c.publishedSize, 1740285);
    assert.equal(c.language, 'en');
});

test('toRemoteChannel prefers the proxy names when a row carries both', () => {
    const c = toRemoteChannel({
        id: 'c150ea1d69495d37b5b0ac6f017e9bfb',
        total_resources: 10, total_resource_count: 99,
        total_file_size: 100, published_size: 999,
    });
    assert.equal(c.totalResources, 10);
    assert.equal(c.publishedSize, 100);
});

test('toRemoteChannel yields null, not zero, when neither name is present', () => {
    // A missing figure must stay distinguishable from a real zero: the picker
    // shows "size unknown" for one and "0 B" for the other.
    const c = toRemoteChannel({ id: 'c150ea1d69495d37b5b0ac6f017e9bfb' });
    assert.equal(c.totalResources, null);
    assert.equal(c.publishedSize, null);
    assert.equal(c.name, '');
});

// ─── failureMessage ──────────────────────────────────────────────────────────

test('failureMessage digs the real cause out of the traceback', () => {
    // Kolibri reports the class name alone. A device test produced exactly this:
    // "Kolibri failed importing Nope: HTTPError", which tells the reader nothing.
    const msg = failureMessage('HTTPError',
        'Traceback (most recent call last):\n'
        + '  File "/x/y.py", line 3, in run\n'
        + 'requests.exceptions.HTTPError: 404 Client Error: Not Found for url: /api/x');
    assert.equal(msg, 'HTTPError: 404 Client Error: Not Found for url: /api/x');
});

test('failureMessage falls back to the class name when there is no traceback', () => {
    assert.equal(failureMessage('HTTPError', null), 'HTTPError');
    assert.equal(failureMessage('HTTPError', '   '), 'HTTPError');
});

test('failureMessage says something even with nothing to work from', () => {
    assert.equal(failureMessage(null, null), 'no detail');
    assert.equal(failureMessage('', ''), 'no detail');
});

test('failureMessage keeps a detail line that does not match the usual shape', () => {
    assert.equal(failureMessage('RuntimeError', 'Traceback:\n  disk quota exceeded'),
        'disk quota exceeded');
});

test('failureMessage does not echo the class name back as if it were detail', () => {
    assert.equal(failureMessage('HTTPError', 'Traceback:\nHTTPError'), 'HTTPError');
});
