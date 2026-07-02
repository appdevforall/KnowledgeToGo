import test from 'node:test';
import assert from 'node:assert/strict';
import { validateSecureCommand, parseBox, buildArgs, displayBounds } from './maps.socket';

const SCRIPT = '/opt/iiab/maps/tile-extract/tile-extract.py';
const sudo = (rest: string) => `sudo ${SCRIPT} ${rest}`;

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
