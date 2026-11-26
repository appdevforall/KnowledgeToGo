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

// The EPUB marks (www/epubmarks.js): a CFI range of the book, drawn on each phone where its words are.
const E = require('./www/epubmarks.js');

test('epub inSection: only a CFI of the section on the screen', () => {
    assert.equal(E.inSection('epubcfi(/6/4[ch1]!/4/2/1:0)', '/6/4[ch1]'), true);
    assert.equal(E.inSection('epubcfi(/6/4[ch1]!/4,/2/1:0,/6/1:9)', '/6/4[ch1]'), true);   // a range
    assert.equal(E.inSection('epubcfi(/6/14[ch6]!/4/2/1:0)', '/6/4[ch1]'), false);         // another section
    assert.equal(E.inSection('epubcfi(/6/4[ch1]x!/4/2/1:0)', '/6/4[ch1]'), false);
    assert.equal(E.inSection('epubcfi(/6/4[ch1]!/4/2/1:0)', ''), false);
    assert.equal(E.inSection(undefined, '/6/4[ch1]'), false);
});

test('epub columns: the rects of each column of the page, in column order', () => {
    const cols = E.columns([
        r(420, 10, 700, 30),     // column 2, line 1
        r(20, 300, 300, 320),    // column 1, last line
        r(20, 280, 380, 300),    // column 1
        r(420, 30, 520, 50),     // column 2, line 2
        r(500, 60, 500, 80),     // empty
    ]);
    assert.deepEqual(cols, [[r(20, 300, 300, 320), r(20, 280, 380, 300)], [r(420, 10, 700, 30), r(420, 30, 520, 50)]]);
    assert.deepEqual(E.columns([]), []);
});

test('epub shapes: words get one rect per line; a box and an oval go around each column', () => {
    const rects = [r(20, 280, 380, 300), r(20, 300, 120, 320), r(420, 10, 700, 30)];
    assert.deepEqual(E.shapes({ k: 'p' }, rects), [r(20, 280, 380, 300), r(20, 300, 120, 320), r(420, 10, 700, 30)]);
    assert.deepEqual(E.shapes({ k: 'r' }, rects), [r(17, 277, 383, 323), r(417, 7, 703, 33)]);
    assert.deepEqual(E.shapes({ k: 'b' }, [r(50, 50, 150, 100)]), [r(47, 47, 153, 103)]);
    const oval = E.shapes({ k: 'r', o: 1 }, [r(0, 0, 94, 44)])[0];
    // The corners of the box are on the oval: ((x/a)^2 + (y/b)^2 = 1 at a corner).
    const a = (oval.right - oval.left) / 2;
    const b = (oval.bottom - oval.top) / 2;
    const cx = (oval.left + oval.right) / 2;
    const cy = (oval.top + oval.bottom) / 2;
    assert.ok(Math.abs(((-3 - cx) / a) ** 2 + ((-3 - cy) / b) ** 2 - 1) < 1e-9);
});

test('epub corners: a drawn box keeps its size on the teacher page and follows its words', () => {
    const a = r(100, 200, 140, 220);     // the first word in the box
    const b = r(300, 240, 350, 260);     // the last word in the box
    const drawn = r(90, 195, 360, 270);
    const c = E.corners(a, 20, b, 20, drawn);
    assert.deepEqual(c, { x: -50, y: -25, ex: 50, ey: 50 });
    assert.deepEqual(E.cornerBox(a, 20, b, 20, c), drawn);                        // the same box
    // Another phone: the words are at other places, the font is larger.
    assert.deepEqual(E.cornerBox(r(20, 400, 70, 425), 25, r(260, 450, 320, 475), 25, c), r(7.5, 393.75, 332.5, 487.5));
    // The line breaks between the two words: the corners cross, no box (the outline instead).
    assert.equal(E.cornerBox(r(300, 200, 350, 220), 20, r(20, 240, 60, 260), 20, { x: 0, y: 0, ex: -200, ey: 0 }), null);
});

test('epub shapes: a box with corners is the drawn box; in two columns it goes around the words', () => {
    const m = { k: 'r', x: -50, y: -25, ex: 50, ey: 50 };
    const ends = { a: r(100, 200, 140, 220), fa: 20, b: r(300, 240, 350, 260), fb: 20 };
    assert.deepEqual(E.shapes(m, [r(100, 200, 380, 220), r(20, 240, 350, 260)], ends), [r(90, 195, 360, 270)]);
    assert.deepEqual(E.shapes({ ...m, o: 1 }, [r(100, 200, 380, 220)], ends), [r(90, 195, 360, 270)]);   // an oval: the same box
    assert.deepEqual(E.shapes(m, [r(20, 280, 380, 300), r(420, 10, 700, 30)], ends), [r(17, 277, 383, 303), r(417, 7, 703, 33)]);
    assert.deepEqual(E.shapes(m, [r(20, 280, 380, 300)], null), [r(17, 277, 383, 303)]);                   // no words found
});

test('epub fitted: a box that no longer holds its first and last word gives way to the outline', () => {
    const m = { k: 'r', x: -50, y: -25, ex: 50, ey: 50 };
    const rects = [r(100, 200, 380, 220)];
    assert.deepEqual(E.fitted(m, rects, { a: r(100, 200, 140, 220), fa: 20, b: r(300, 200, 350, 220), fb: 20 }), r(90, 195, 360, 230));
    // On a narrow phone the last word wraps to the next line, at its start: the box from the corners
    // is thin and leaves the last word out, so no fitted box.
    assert.equal(E.fitted(m, [r(100, 200, 380, 220), r(20, 240, 120, 260)], { a: r(100, 200, 140, 220), fa: 20, b: r(20, 240, 120, 260), fb: 20 }), null);
    assert.equal(E.fitted({ k: 'r' }, rects, { a: r(100, 200, 140, 220), fa: 20, b: r(300, 200, 350, 220), fb: 20 }), null);   // no corners
    assert.equal(E.fitted({ k: 'p', x: 0, y: 0, ex: 0, ey: 0 }, rects, null), null);
});

// The marks of the box map (www/mapmarks.js).
const G = require('./www/mapmarks.js');

test('map arrow: the line stops at the base of the head; the head points to the end', () => {
    const a = G.arrowShape(0, 0, 100, 0, 22, 13);
    assert.deepEqual(a.line, [0, 0, 78, 0]);
    assert.deepEqual(a.head, [[100, 0], [78, 13], [78, -13]]);
    const short = G.arrowShape(0, 0, 0, 20, 22, 13);                  // a short arrow: a smaller head
    assert.deepEqual(short.line, [0, 0, 0, 10]);
    assert.equal(short.head[0][1], 20);
    assert.ok(Math.abs(Math.abs(short.head[1][0]) - 13 * 10 / 22) < 1e-9);
    assert.equal(G.arrowShape(5, 5, 5, 5, 22, 13), null);             // no length
});

test('map places: whole numbers of 1/1000000 degree; the first place goes into -180..180', () => {
    assert.deepEqual(G.geoOf([{ lng: -3.7037904, lat: 40.4167754 }, { lng: -3.69, lat: 40.42 }]), [-3703790, 40416775, -3690000, 40420000]);
    // A teacher on the world copy to the right: the shape keeps its width over the 180 degree line.
    assert.deepEqual(G.geoOf([{ lng: 539, lat: 0 }, { lng: 541, lat: 1 }]), [179000000, 0, 181000000, 1000000]);
    assert.deepEqual(G.placesOf({ geo: [179000000, 0, 181000000, 1000000] }), [[179, 0], [181, 1]]);
    assert.equal(G.turnsTo(-170, 175), 360);      // the copy nearest to the view
    assert.equal(G.turnsTo(10, 20), 0);
});

test('map hit: an arrow near its line, a box inside or near its edge, the smallest box first', () => {
    const shapes = [
        { id: 1, k: 'r', pts: [[0, 0], [200, 0], [200, 200], [0, 200]] },
        { id: 2, k: 'r', pts: [[50, 50], [100, 50], [100, 100], [50, 100]] },
        { id: 3, k: 'a', pts: [[0, 150], [150, 150]] },
        { id: 4, k: 'r', o: 1, pts: [[300, 0], [400, 0], [400, 100], [300, 100]] },
    ];
    assert.equal(G.hit(shapes, 75, 75, 16), 2);         // inside both boxes: the smaller one
    assert.equal(G.hit(shapes, 20, 20, 16), 1);
    assert.equal(G.hit(shapes, 100, 160, 16), 3);       // near the arrow, inside a box: the arrow
    assert.equal(G.hit(shapes, 210, 100, 16), 1);       // near the edge, outside
    assert.equal(G.hit(shapes, 260, 100, 16), null);
    assert.equal(G.hit(shapes, 350, 50, 16), 4);        // inside the oval
    assert.equal(G.hit(shapes, 305, 5, 4), null);       // the corner of the box, out of the oval
    assert.equal(G.hit([], 0, 0, 16), null);
});

test('map oval: goes through the middle of each side of the shape', () => {
    const pts = G.ovalPoints([[0, 0], [100, 0], [100, 50], [0, 50]], 4);
    const near = (p: number[], x: number, y: number) => Math.abs(p[0] - x) < 1e-9 && Math.abs(p[1] - y) < 1e-9;
    assert.ok(near(pts[0], 100, 25) && near(pts[1], 50, 50) && near(pts[2], 0, 25) && near(pts[3], 50, 0));
});

test('map marks: from screen points to places and back, with a fake map', () => {
    // A fake map: 1 px = 0.001 degree, the place (0, 0) at the screen point (200, 200).
    const map = {
        project: (p: number[]) => ({ x: 200 + p[0] * 1000, y: 200 - p[1] * 1000 }),
        unproject: (p: number[]) => ({ lng: (p[0] - 200) / 1000, lat: (200 - p[1]) / 1000 }),
        getCenter: () => ({ lng: 0, lat: 0 }),
    };
    assert.equal(G.arrowMark(map, { x: 200, y: 200 }, { x: 205, y: 205 }), null);              // a tap
    const a = G.arrowMark(map, { x: 200, y: 200 }, { x: 300, y: 100 });
    assert.deepEqual(a, { k: 'a', geo: [0, 0, 100000, 100000] });
    assert.deepEqual(G.shapesOf(map, [{ ...a, id: 7 }]), [{ id: 7, k: 'a', o: undefined, pts: [[200, 200], [300, 100]] }]);
    const box = G.boxMark(map, { left: 100, top: 100, right: 300, bottom: 200 });
    assert.deepEqual(box.geo, [-100000, 100000, 100000, 100000, 100000, 0, -100000, 0]);
    assert.equal(G.boxMark(map, { left: 100, top: 100, right: 105, bottom: 200 }), null);
    assert.deepEqual(G.moveMark(map, { ...box, o: 1, id: 3 }, 10, -20), { k: 'r', o: 1, geo: [-90000, 120000, 110000, 120000, 110000, 20000, -90000, 20000] });
    assert.deepEqual(G.shapesOf(map, [{ k: 'b', b: 1, t: 'a' }, { k: 'p', cfi: 'epubcfi(/6)' }]), []);   // other kinds
});
