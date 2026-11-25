/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';

// The block anchor runs in the browser (www/anchor.js, plain JS); its pure part runs here too.
const A = require('./www/anchor.js');

const rect = (top: number, bottom: number) => ({ top, bottom });

test('printOf: same text gives the same print; space does not count; only the start counts', () => {
    assert.equal(A.printOf('The  first\n line'), A.printOf(' The first line '));
    assert.notEqual(A.printOf('The first line'), A.printOf('The first lime'));
    assert.equal(A.printOf('x'.repeat(48) + 'a'), A.printOf('x'.repeat(48) + 'b'));
    assert.equal(A.printOf(undefined), A.printOf(''));
});

test('pickAnchor: the first block that the top edge cuts, and the part above the edge', () => {
    const a = A.pickAnchor([rect(-300, -100), rect(-100, -100), rect(-40, 60), rect(60, 200)]);
    assert.deepEqual(a, { b: 2, n: 4, o: 0.4, d: 40 });
});

test('pickAnchor: skips blocks with no height (hidden) and blocks fully above the edge', () => {
    const a = A.pickAnchor([rect(-50, 0), rect(10, 10), rect(25, 80)]);
    assert.equal(a.b, 2);
    assert.equal(a.d, -25);      // the edge is in the gap, 25 px above the block
    assert.equal(a.o, 0);
});

test('pickAnchor: no block below the edge gives b = -1', () => {
    assert.deepEqual(A.pickAnchor([rect(-90, -10)]), { b: -1, n: 1, o: 0, d: 0 });
    assert.deepEqual(A.pickAnchor([]), { b: -1, n: 0, o: 0, d: 0 });
});

test('resolveBlock: same page: same index', () => {
    const prints = ['a', 'b', 'c', 'd'];
    assert.deepEqual(A.resolveBlock(prints, { b: 2, n: 4, t: 'c' }), { i: 2, exact: true });
});

test('resolveBlock: a block more or less on the student: the nearest block with the same print', () => {
    assert.deepEqual(A.resolveBlock(['x', 'a', 'b', 'c', 'd'], { b: 2, n: 4, t: 'c' }), { i: 3, exact: true });
    assert.deepEqual(A.resolveBlock(['a', 'c', 'd'], { b: 2, n: 4, t: 'c' }), { i: 1, exact: true });
    // Two blocks with the same text: the nearer one wins.
    assert.deepEqual(A.resolveBlock(['r', 'q', 'r', 'q', 'z', 'z', 'r'], { b: 5, n: 7, t: 'r' }), { i: 6, exact: true });
});

test('resolveBlock: another page: the same relative place, not exact', () => {
    const prints = ['p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z'];
    assert.deepEqual(A.resolveBlock(prints, { b: 50, n: 101, t: 'nope' }), { i: 5, exact: false });
    assert.deepEqual(A.resolveBlock(prints, { b: 100, n: 101, t: 'nope' }), { i: 10, exact: false });
    assert.deepEqual(A.resolveBlock(['p'], { b: 3, n: 1, t: 'nope' }), { i: 0, exact: false });
});

test('resolveBlock: no anchor or no blocks: nothing to find', () => {
    assert.equal(A.resolveBlock(['a'], { b: -1, n: 0 }).i, -1);
    assert.equal(A.resolveBlock([], { b: 0, n: 1, t: 'a' }).i, -1);
    assert.equal(A.resolveBlock(['a'], null).i, -1);
});

test('targetY: inside the block the part counts, so a larger text size keeps the line', () => {
    const a = { b: 3, o: 0.25, d: 30 };
    assert.equal(A.targetY(1000, 120, a), 1030);    // same size as the teacher
    assert.equal(A.targetY(1500, 240, a), 1560);    // text twice as large: still a quarter in
});

test('targetY: above a block the pixels of the gap count; never above the page top', () => {
    assert.equal(A.targetY(1000, 120, { b: 3, o: 0, d: -12 }), 988);
    assert.equal(A.targetY(1000, 120, { b: 3, o: 0, d: 0 }), 1000);
    assert.equal(A.targetY(5, 120, { b: 0, o: 0, d: -40 }), 0);
});

test('a round trip: the anchor of the teacher lands the student on the same line', () => {
    // Teacher: blocks 100 px tall from y = 0; view top at 1234 (block 12, 34 px in).
    const scrollT = 1234;
    const teacher = Array.from({ length: 30 }, (_, i) => rect(i * 100 - scrollT, i * 100 + 100 - scrollT));
    const a = A.pickAnchor(teacher);
    a.t = 'b12';
    // Student: larger text, blocks 150 px tall, and one more block at the top of its page.
    const prints = ['extra', ...Array.from({ length: 30 }, (_, i) => 'b' + i)];
    const found = A.resolveBlock(prints, a);
    assert.deepEqual(found, { i: 13, exact: true });
    assert.equal(A.targetY(found.i * 150, 150, a), 13 * 150 + 51);   // 34 % of the block
});

test('targetY: with the character of the teacher, its line goes to the top, part of the line too', () => {
    const a = { b: 3, o: 0.25, d: 30, c: 120, l: 0.5 };
    assert.equal(A.targetY(1000, 120, a, 1040, 20), 1050);
    // The character was not found on the student: the part of the block counts.
    assert.equal(A.targetY(1000, 120, a, undefined, undefined), 1030);
    assert.equal(A.targetY(1000, 120, a, null, null), 1030);
});

test('nextVisible: skips the spaces at the end of a line; a text of spaces keeps c', () => {
    assert.equal(A.nextVisible('ab  \n cd', 2), 6);
    assert.equal(A.nextVisible('ab cd', 1), 1);
    assert.equal(A.nextVisible('ab   ', 2), 2);
});

test('locate: the text node and the offset of a character of the block', () => {
    assert.deepEqual(A.locate([3, 0, 4], 0), { k: 0, off: 0 });
    assert.deepEqual(A.locate([3, 0, 4], 3), { k: 2, off: 0 });
    assert.deepEqual(A.locate([3, 0, 4], 6), { k: 2, off: 3 });
    assert.equal(A.locate([3, 0, 4], 7), null);
    assert.equal(A.locate([3], -1), null);
    assert.equal(A.locate([], 0), null);
});
