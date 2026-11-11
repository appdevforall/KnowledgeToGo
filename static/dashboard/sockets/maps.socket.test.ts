/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import { validateSecureCommand, parseBox, buildArgs, displayBounds, parseEstimate, parseEstimateFrom, listFqrRegions } from './maps.socket';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const SCRIPT = '/opt/iiab/maps/tile-extract/tile-extract.py';
const sudo = (rest: string) => `sudo ${SCRIPT} ${rest}`;

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

test('validateSecureCommand: extract with uppercase/hyphen name + spaces', () => {
    const v = validateSecureCommand(sudo('extract Mexico-North -103.05, 19.72, -98.33, 25.84'));
    assert.equal(v.type, 'extract');
    assert.equal(v.region, 'Mexico-North');
    assert.equal(v.bbox, '-103.05,19.72,-98.33,25.84');
    assert.equal(v.noninteractive, false);
});

test('validateSecureCommand: extract noninteractive flag', () => {
    const v = validateSecureCommand(sudo('extract r1 1,2,3,4 noninteractive'));
    assert.equal(v.type, 'extract');
    assert.equal(v.noninteractive, true);
});

test('validateSecureCommand: delete and update-json', () => {
    assert.equal(validateSecureCommand(sudo('delete Mexico-North')).type, 'delete');
    assert.equal(validateSecureCommand(sudo('update-json')).type, 'update-json');
});

test('validateSecureCommand: rejects non-sudo, bad name, bad box', () => {
    assert.ok(validateSecureCommand('rm -rf /').error);
    assert.ok(validateSecureCommand(sudo('extract bad!name 1,2,3,4')).error);
    assert.ok(validateSecureCommand(sudo('extract r1 1,2,3')).error);
});

test('buildArgs mirrors the validated command', () => {
    assert.deepEqual(buildArgs({ type: 'delete', region: 'r1' }), [SCRIPT, 'delete', 'r1']);
    assert.deepEqual(buildArgs({ type: 'update-json', region: '' }), [SCRIPT, 'update-json']);
    assert.deepEqual(
        buildArgs({ type: 'extract', region: 'r1', bbox: '1,2,3,4', noninteractive: true }),
        [SCRIPT, 'extract', 'r1', '1,2,3,4', 'noninteractive'],
    );
});
