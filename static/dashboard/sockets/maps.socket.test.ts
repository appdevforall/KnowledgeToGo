/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import {
    parseBox, displayBounds, parseEstimate, parseEstimateFrom, listFqrRegions, hasModernCli, installedHasModernCli,
    extractArgs, estimateArgs, deleteArgs, classifyExtractEnd, ExtractEnd, NET_ERR_RE, LOCK_BUSY_RE, REGION_NAME_RE, TILE_EXTRACT,
    jobOwnsRegion,
} from './maps.socket';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const SCRIPT = '/opt/iiab/maps/tile-extract/tile-extract.py';

test('listFqrRegions: lists <name>.fqr dirs and reads meta.json bbox (K2GO-566)', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'fqr-'));
    try {
        fs.mkdirSync(path.join(root, 'ttest.fqr'));
        fs.writeFileSync(path.join(root, 'ttest.fqr', 'meta.json'),
            JSON.stringify({ bbox: [-108.5577, 28.0298, -101.073, 36.1073] }));
        // incomplete region (no meta.json): still listed by name so it can be deleted, no bounds
        fs.mkdirSync(path.join(root, 'broken.fqr'));
        // a non-.fqr dir and a stray file: ignored
        fs.mkdirSync(path.join(root, 'notes'));
        fs.writeFileSync(path.join(root, 'readme.txt'), 'x');

        const out = listFqrRegions(root);
        assert.deepEqual(Object.keys(out).sort(), ['broken', 'ttest']);
        assert.deepEqual(out.ttest.ui_bounds, [-108.5577, 28.0298, -101.073, 36.1073]);
        assert.deepEqual(out.ttest.bbox, [-108.5577, 28.0298, -101.073, 36.1073]);
        assert.deepEqual(out.broken, {});
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});

test('listFqrRegions: a missing maps dir is empty, not an error (K2GO-566)', () => {
    assert.deepEqual(listFqrRegions(path.join(os.tmpdir(), 'no-such-maps-' + Date.now())), {});
});

test('displayBounds prefers ui_bounds, then render_bounds/bbox/array', () => {
    assert.deepEqual(
        displayBounds({ ui_bounds: [176, -10, 184, 10], render_bounds: [-180, -10, 180, 10] }),
        [176, -10, 184, 10],
    );
    assert.deepEqual(displayBounds({ render_bounds: [1, 2, 3, 4] }), [1, 2, 3, 4]);
    assert.deepEqual(displayBounds({ bbox: [1, 2, 3, 4] }), [1, 2, 3, 4]);
    assert.deepEqual(displayBounds([1, 2, 3, 4]), [1, 2, 3, 4]);
    assert.equal(displayBounds({}), null);
    assert.equal(displayBounds(null), null);
});

test('parseBox accepts a valid box and strips spaces', () => {
    const r = parseBox('-103.05, 19.72, -98.33, 25.84');
    assert.equal(r.ok, true);
    if (r.ok) assert.equal(r.box, '-103.05,19.72,-98.33,25.84');
});

test('parseBox rejects bad count, empty coord, non-numeric, order, span', () => {
    assert.equal(parseBox('1,2,3').ok, false);
    assert.equal(parseBox('1,,3,4').ok, false);
    assert.equal(parseBox('1,x,3,4').ok, false);
    assert.equal(parseBox('5,2,3,4').ok, false);      // minLon > maxLon
    assert.equal(parseBox('-200,0,200,10').ok, false); // span >= 360
});

test('parseEstimate reads the interactive size prompt into bytes (ADFA-4879)', () => {
    // Real output captured from tile-extract.py's approve_download_size().
    const prompt = 'Determining download size...\n' +
        'New region will be 8.54 MB downloaded, 8.34 MB on disk. This will\n' +
        'leave about 73.58 GB free space on this partition. Continue? y/n ';
    const e = parseEstimate(prompt);
    assert.ok(e);
    assert.equal(e!.transfer, 8_540_000);
    assert.equal(e!.archive, 8_340_000);
    assert.equal(e!.free_after, 73_580_000_000);
    assert.equal(e!.free, 73_580_000_000 + 8_340_000);
});

test('parseEstimateFrom reads the prompt from stdout (older script) or stderr (newer script)', () => {
    const prompt = 'New region will be 9.7 MB downloaded, 9.6 MB on disk. This will\n' +
        'leave about 60.96 GB free space on this partition. Continue? y/n ';
    const fromOut = parseEstimateFrom(prompt, '');
    const fromErr = parseEstimateFrom('', 'Determining download size...\n\n' + prompt);
    assert.ok(fromOut);
    assert.deepEqual(fromErr, fromOut);
    assert.equal(fromErr!.transfer, 9_700_000);
    assert.equal(parseEstimateFrom('', 'Determining download size...'), null);
});

test('parseEstimate returns null before the size line appears', () => {
    assert.equal(parseEstimate('Determining download size...'), null);
    assert.equal(parseEstimate(''), null);
});

// ---- tile-extract.py CLI (iiab#4574) ----------------------------------------------------------
// Excerpts of the two scripts' argument handling (iiab/iiab roles/maps/templates/tile-extract.py.j2).
const MODERN_SRC = [
    'def parse_args():',
    '    box.add_argument(',
    '        "--bbox", dest="extract_box_option", metavar="MIN_LON,MIN_LAT,MAX_LON,MAX_LAT",',
    '    )',
    '        command.add_argument(',
    '            "-y", "--yes", action="store_true",',
].join('\n');
const LEGACY_SRC = [
    '        case "delete":',
    '            assert len(sys.argv) == 3, "delete takes one argument: extract_name"',
    '            noninteractive = sys.argv[4] if len(sys.argv) > 4 else ""',
].join('\n');

test('TILE_EXTRACT is the box path of the script', () => {
    assert.equal(TILE_EXTRACT, SCRIPT);
});

test('hasModernCli tells the argparse CLI from the older positional one', () => {
    assert.equal(hasModernCli(MODERN_SRC), true);
    assert.equal(hasModernCli(LEGACY_SRC), false);
    assert.equal(hasModernCli('"--yes" only'), false);   // both markers are needed
});

test('installedHasModernCli reads the installed script; unreadable means the current CLI', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'te-'));
    try {
        const f = path.join(dir, 'tile-extract.py');
        fs.writeFileSync(f, MODERN_SRC);
        assert.equal(installedHasModernCli(f), true);
        fs.writeFileSync(f, LEGACY_SRC);
        assert.equal(installedHasModernCli(f), false);
        assert.equal(installedHasModernCli(path.join(dir, 'missing.py')), true);
    } finally {
        fs.rmSync(dir, { recursive: true, force: true });
    }
});

test('extractArgs: current CLI uses --bbox=, --yes, --progress json, --threads 8', () => {
    assert.deepEqual(extractArgs('Mexico-North', '-103.05,19.72,-98.33,25.84', true), [
        'env', 'PYTHONUNBUFFERED=1', SCRIPT, 'extract', 'Mexico-North', '--bbox=-103.05,19.72,-98.33,25.84',
        '--yes', '--progress', 'json', '--threads', '8',
    ]);
});

test('extractArgs: older CLI keeps the positional box, noninteractive and the env settings', () => {
    assert.deepEqual(extractArgs('r1', '1,2,3,4', false), [
        'env', 'IIAB_PMTILES_PROGRESS=json', 'IIAB_PMTILES_DOWNLOAD_THREADS=8', 'PYTHONUNBUFFERED=1',
        SCRIPT, 'extract', 'r1', '1,2,3,4', 'noninteractive',
    ]);
});

test('estimateArgs: an interactive extract (no --yes), so the confirm stops it', () => {
    assert.deepEqual(estimateArgs('est_x', '-1,2,3,4', true), [SCRIPT, 'extract', 'est_x', '--bbox=-1,2,3,4']);
    assert.deepEqual(estimateArgs('est_x', '-1,2,3,4', false), [SCRIPT, 'extract', 'est_x', '-1,2,3,4']);
});

test('deleteArgs: --yes on the current CLI; the older delete takes only the name', () => {
    assert.deepEqual(deleteArgs('r1', true), [SCRIPT, 'delete', 'r1', '--yes']);
    assert.deepEqual(deleteArgs('r1', false), [SCRIPT, 'delete', 'r1']);
});

test('REGION_NAME_RE matches the script rule (letters, digits, - and _, 1..34)', () => {
    assert.ok(REGION_NAME_RE.test('Mexico-North_2'));
    assert.ok(!REGION_NAME_RE.test(''));
    assert.ok(!REGION_NAME_RE.test('a'.repeat(35)));
    assert.ok(!REGION_NAME_RE.test('bad name'));
    assert.ok(!REGION_NAME_RE.test('../x'));
});

// ---- classifyExtractEnd ------------------------------------------------------------------------
const end = (o: Partial<ExtractEnd>): ExtractEnd =>
    ({ stop: null, stalled: false, lastJsonExit: null, exitCode: 1, stderrTail: '', ...o });

// Messages of the current script (iiab#4574), as it prints them.
const BUSY = 'Error: another Full Quality Region download or delete is running.';
const CATALOG = "Exception: Cannot download the map data catalog: HTTPSConnectionPool(host='raw.githubusercontent.com', port=443): Read timed out. (read timeout=100)";
const MIRRORS = 'Exception: Failed to download https://example.org/maps/terrarium.2026-10-01.z00-z12.pmtiles from any of the available mirrors.';
const CONFLICT = 'Error: a region with this name, or an overlapping region, now exists. Nothing was downloaded.';

test('classifyExtractEnd: the engine flags and the watchdog come first', () => {
    assert.equal(classifyExtractEnd(end({ stop: 'canceled', stderrTail: BUSY })), 'canceled');
    assert.equal(classifyExtractEnd(end({ stop: 'canceled', exitCode: 3 })), 'canceled');
    assert.equal(classifyExtractEnd(end({ stop: 'paused' })), 'paused');
    assert.equal(classifyExtractEnd(end({ stalled: true })), 'paused');
});

test('classifyExtractEnd: resumable ends keep the partial', () => {
    assert.equal(classifyExtractEnd(end({ lastJsonExit: 2, exitCode: 2 })), 'paused');   // network gave up
    assert.equal(classifyExtractEnd(end({ exitCode: 3 })), 'paused');                     // outside SIGTERM
    assert.equal(classifyExtractEnd(end({ stderrTail: CATALOG })), 'paused');
    assert.equal(classifyExtractEnd(end({ stderrTail: MIRRORS })), 'paused');
    // the older script let the requests error through as a traceback
    assert.equal(classifyExtractEnd(end({ stderrTail: 'requests.exceptions.ConnectionError: Max retries exceeded with url' })), 'paused');
});

test('classifyExtractEnd: a held box lock is busy (nothing ran)', () => {
    assert.equal(classifyExtractEnd(end({ stderrTail: BUSY })), 'busy');
});

test('classifyExtractEnd: real failures are errors', () => {
    assert.equal(classifyExtractEnd(end({ exitCode: 2, stderrTail: 'usage: tile-extract.py' })), 'error');   // argparse, not the network
    assert.equal(classifyExtractEnd(end({ lastJsonExit: 4, exitCode: 4 })), 'error');    // file on the server changed
    assert.equal(classifyExtractEnd(end({ stderrTail: CONFLICT })), 'error');
    assert.equal(classifyExtractEnd(end({ stderrTail: 'Region download failed: signal 9 stopped pmtiles.' })), 'error');
    assert.equal(classifyExtractEnd(end({ exitCode: null })), 'error');
});

test('classifyExtractEnd: a pmtiles failure code wins over an earlier retried network line', () => {
    const reset = 'retrying after: read: connection reset by peer\nConnection reset by peer';
    assert.equal(classifyExtractEnd(end({ lastJsonExit: 4, exitCode: 4, stderrTail: reset })), 'error');
    assert.equal(classifyExtractEnd(end({ lastJsonExit: 1, stderrTail: reset })), 'error');
    // a finished layer (0) then the script's own mirror request failing is still resumable
    assert.equal(classifyExtractEnd(end({ lastJsonExit: 0, stderrTail: MIRRORS })), 'paused');
});

test('jobOwnsRegion: only a region dir the job made is purged', () => {
    assert.equal(jobOwnsRegion(false, ''), true);          // made during the final move
    assert.equal(jobOwnsRegion(true, ''), false);          // it was there before the job
    assert.equal(jobOwnsRegion(false, CONFLICT), false);   // another run made it under the lock
});

test('the busy and network patterns do not overlap', () => {
    assert.ok(!NET_ERR_RE.test(BUSY));
    assert.ok(!LOCK_BUSY_RE.test(CATALOG) && !LOCK_BUSY_RE.test(MIRRORS));
});
