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

test('wordAround: the word at a character, also right after it; none in a space', () => {
    const text = 'In his early years,  Charles';
    assert.deepEqual(M.wordAround(text, 8), { start: 7, end: 12 });     // "early"
    assert.deepEqual(M.wordAround(text, 7), { start: 7, end: 12 });
    assert.deepEqual(M.wordAround(text, 12), { start: 7, end: 12 });    // the caret right after it
    assert.deepEqual(M.wordAround(text, 18), { start: 13, end: 19 });   // "years," with its comma
    assert.equal(M.wordAround(text, 20), null);                         // in the double space
    assert.deepEqual(M.wordAround(text, 28), { start: 21, end: 28 });   // the end of the text
    assert.equal(M.wordAround('', 0), null);
});

test('wordAround with Intl.Segmenter: no punctuation in a word, words of a language with no spaces', () => {
    const en = M.segmentsFor('en');
    assert.deepEqual(M.wordAround('early years, Charles', 9, en), { start: 6, end: 11 });   // "years", no comma
    assert.deepEqual(M.wordAround('early years, Charles', 11, en), { start: 6, end: 11 });  // the caret after it
    assert.equal(M.wordAround('a , b', 2, en), null);                                      // the comma alone
    const zh = M.segmentsFor('zh');
    const text = '中文句子没有空格在这里';
    const w = M.wordAround(text, 0, zh);
    assert.ok(w && w.start === 0 && w.end < text.length, JSON.stringify(w));               // a word, not the whole text
});

test('shapeAt: a box or oval under the point or near it, the smallest one', () => {
    const drawn = [
        { id: 1, k: 'b', rects: [r(0, 0, 400, 100)] },
        { id: 2, k: 'r', rects: [r(10, 10, 300, 90)] },
        { id: 3, k: 'r', rects: [r(50, 30, 100, 60)] },
    ];
    assert.equal(M.shapeAt(drawn, 60, 40, 16).id, 3);       // inside both boxes: the small one
    assert.equal(M.shapeAt(drawn, 200, 50, 16).id, 2);
    assert.equal(M.shapeAt(drawn, 305, 50, 16).id, 2);      // near the edge, out of it
    assert.equal(M.shapeAt(drawn, 330, 50, 16), null);      // too far; a block mark is no shape
    assert.deepEqual(M.shapeAt(drawn, 60, 40, 16).rect, r(50, 30, 100, 60));
});

test('phraseBetween: from the first word to the last one, in any order of the drag', () => {
    const texts = ['First paragraph here.', '', 'Second one, longer.'];
    const prints = ['p0', 'p1', 'p2'];
    const text = (i: number) => texts[i];
    const a = { b: 0, start: 6, end: 15 };     // "paragraph"
    const z = { b: 2, start: 7, end: 11 };     // "one,"
    const mark = { k: 'p', b: 0, t: 'p0', s: 6, eb: 2, et: 'p2', e: 11 };
    assert.deepEqual(M.phraseBetween(text, prints, a, z), mark);
    assert.deepEqual(M.phraseBetween(text, prints, z, a), mark);      // a drag upwards
    assert.deepEqual(M.phraseBetween(text, prints, a, a), { k: 'p', b: 0, t: 'p0', s: 6, eb: 0, et: 'p0', e: 15 });
    const back = { b: 0, start: 0, end: 5 };   // "First", left of a on the same line
    assert.deepEqual(M.phraseBetween(text, prints, a, back), { k: 'p', b: 0, t: 'p0', s: 0, eb: 0, et: 'p0', e: 15 });
});

test('clamp: a rect stays inside the page', () => {
    assert.deepEqual(M.clamp(r(-2, -2, 402, 50), 400, 1000), r(0, 0, 400, 50));
    assert.deepEqual(M.clamp(r(10, 990, 20, 1002), 400, 1000), r(10, 990, 20, 1000));
});

test('boxRect: x holds across the page; each edge holds its place in its own block', () => {
    const m = { x: 40, y: 10, ey: 30, w: 100 };
    const origin = { left: 0 };
    assert.deepEqual(M.boxRect({ top: 200 }, { top: 240 }, origin, m), r(40, 210, 140, 270));
    // Bigger text: the blocks move apart, the box grows with the part of the page that it holds.
    assert.deepEqual(M.boxRect({ top: 300 }, { top: 380 }, origin, m), r(40, 310, 140, 410));
    assert.deepEqual(M.boxRect({ top: 0 }, { top: 0 }, { left: 8 }, { ...m, y: -20 }), r(48, -20, 148, 30));
    assert.equal(M.boxRect({ top: 200 }, { top: 100 }, origin, m), null);   // the edges cross
});

test('blockAtY: the block under the edge, the one that overlaps the box across first', () => {
    const b = (top: number, bottom: number, left = 0, right = 400) => ({ top, bottom, left, right });
    // 0: a paragraph; 1: a text column and 2: a picture beside it, at the same height; 3: a block below.
    const rects = [b(0, 50), b(60, 200, 0, 190), b(60, 140, 210, 400), b(60, 60), b(220, 300)];
    assert.equal(M.blockAtY(rects, 10, 0, 400, true), 0);
    assert.equal(M.blockAtY(rects, 100, 220, 380, true), 2);     // the picture, not the text beside it
    assert.equal(M.blockAtY(rects, 100, 10, 100, true), 1);
    assert.equal(M.blockAtY(rects, 100, 400, 500, true), 1);     // no overlap across: the first one
    assert.equal(M.blockAtY(rects, 55, 0, 400, true), 1);        // a top edge in a gap: the block below
    assert.equal(M.blockAtY(rects, 55, 0, 400, false), 0);       // a bottom edge in a gap: the block above
    assert.equal(M.blockAtY(rects, -10, 0, 400, false), 0);      // above all: the first block
    assert.equal(M.blockAtY(rects, 400, 0, 400, true), 4);       // below all: the last block
    assert.equal(M.blockAtY([], 10, 0, 400, true), -1);
});

test('hitTest: a phrase wins over the block around it; then the smallest mark; else null', () => {
    const drawn = [
        { id: 1, k: 'b', rects: [r(0, 0, 400, 100)] },
        { id: 2, k: 'p', rects: [r(10, 20, 100, 40), r(0, 40, 50, 60)] },
        { id: 3, k: 'b', rects: [r(0, 0, 200, 50)] },
        { id: 4, k: 'r', rects: [r(0, 0, 400, 300)] },
    ];
    assert.equal(M.hitTest(drawn, 20, 30), 2);
    assert.equal(M.hitTest(drawn, 20, 55), 2);
    assert.equal(M.hitTest(drawn, 150, 30), 3);    // the smaller block
    assert.equal(M.hitTest(drawn, 300, 80), 1);
    assert.equal(M.hitTest(drawn, 300, 120), 4);    // only the box is there
    assert.equal(M.hitTest(drawn, 300, 400), null);
    assert.equal(M.hitTest([], 0, 0), null);
});
