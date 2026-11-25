/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';

// The marks run in the browser (www/marks.js, plain JS); their pure part runs here too.
const M = require('./www/marks.js');

const r = (left: number, top: number, right: number, bottom: number) => ({ left, top, right, bottom });

test('mergeLines: one rectangle per line; empty rects go', () => {
    const lines = M.mergeLines([
        r(10, 100, 60, 120),     // line 2, first text node
        r(0, 80, 200, 100),      // line 1
        r(60, 101, 90, 119),     // line 2, a link inside it
        r(90, 100, 90, 120),     // empty
        r(0, 120, 40, 140),      // line 3: touches line 2, does not overlap
    ]);
    assert.deepEqual(lines, [r(0, 80, 200, 100), r(10, 100, 90, 120), r(0, 120, 40, 140)]);
    assert.deepEqual(M.mergeLines([]), []);
});

test('trimEnds: the ends move to the nearest block with text; no text left gives null', () => {
    const texts = ['First paragraph.', '', '\n  ', 'Last one.', '\nCell'];
    const text = (i: number) => texts[i];
    assert.deepEqual(M.trimEnds(text, 0, 6, 0, 15), { b: 0, s: 6, eb: 0, e: 15 });
    assert.deepEqual(M.trimEnds(text, 0, 6, 3, 0), { b: 0, s: 6, eb: 0, e: 16 });    // ends at the start of a block
    assert.deepEqual(M.trimEnds(text, 1, 0, 3, 4), { b: 3, s: 0, eb: 3, e: 4 });     // starts on an empty block
    assert.deepEqual(M.trimEnds(text, 0, 16, 3, 9), { b: 3, s: 0, eb: 3, e: 9 });    // starts at the end of a block
    assert.deepEqual(M.trimEnds(text, 3, 2, 4, 1), { b: 3, s: 2, eb: 3, e: 9 });     // a space only before the end
    assert.equal(M.trimEnds(text, 1, 0, 2, 2), null);                                 // no text at all
    assert.equal(M.trimEnds(text, 3, 5, 3, 5), null);
});

test('isLight: light text means a dark page', () => {
    assert.equal(M.isLight('rgb(238, 238, 238)'), true);
    assert.equal(M.isLight('rgba(255, 255, 255, 0.87)'), true);
    assert.equal(M.isLight('rgb(32, 33, 34)'), false);
    assert.equal(M.isLight('rgb(6, 69, 173)'), false);       // a link blue
    assert.equal(M.isLight(''), false);
});

test('clamp: a rect stays inside the page', () => {
    assert.deepEqual(M.clamp(r(-2, -2, 402, 50), 400, 1000), r(0, 0, 400, 50));
    assert.deepEqual(M.clamp(r(10, 990, 20, 1002), 400, 1000), r(10, 990, 20, 1000));
});

test('hitTest: a phrase wins over the block around it; then the smallest mark; else null', () => {
    const drawn = [
        { id: 1, k: 'b', rects: [r(0, 0, 400, 100)] },
        { id: 2, k: 'p', rects: [r(10, 20, 100, 40), r(0, 40, 50, 60)] },
        { id: 3, k: 'b', rects: [r(0, 0, 200, 50)] },
    ];
    assert.equal(M.hitTest(drawn, 20, 30), 2);
    assert.equal(M.hitTest(drawn, 20, 55), 2);
    assert.equal(M.hitTest(drawn, 150, 30), 3);    // the smaller block
    assert.equal(M.hitTest(drawn, 300, 80), 1);
    assert.equal(M.hitTest(drawn, 300, 120), null);
    assert.equal(M.hitTest([], 0, 0), null);
});
