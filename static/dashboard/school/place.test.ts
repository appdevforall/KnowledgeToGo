/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';

// The place in the apps of the box runs in the browser (www/place.js, plain JS); its pure part
// runs here too.
const P = require('./www/place.js');

test('kindOf: a Kiwix page follows with the block anchor; the libraries and other apps are for the teacher only', () => {
    assert.equal(P.kindOf('/kiwix/content/wikipedia_en/A/Ray_Charles'), 'page');
    assert.equal(P.kindOf('/kiwix/search?content=x&pattern=y'), 'page');
    assert.equal(P.kindOf('/kiwix/'), null);
    assert.equal(P.kindOf('/kiwix'), null);
    assert.equal(P.kindOf('/kiwix/nojs/x'), null);
    assert.equal(P.kindOf('/home/'), null);
    assert.equal(P.kindOf('/books/'), null);
    assert.equal(P.kindOf('/books/book/1'), null);
    assert.equal(P.kindOf('/kolibri/en/'), null);
    assert.equal(P.kindOf('/forgejo/'), null);
    assert.equal(P.kindOf('/info/FAQ.html'), null);
    assert.equal(P.kindOf('/kiwixx/a'), null);
    assert.equal(P.kindOf(''), null);
});

test('kindOf: the maps page is a map, also with a hash or a search', () => {
    assert.equal(P.kindOf('/maps/'), 'map');
    assert.equal(P.kindOf('/maps/index.html'), 'map');
    assert.equal(P.kindOf('/maps/#4.6/-74/0/0/9/m/f/hybrid'), 'map');
    assert.equal(P.kindOf('/maps/?x=1'), 'map');
    assert.equal(P.kindOf('/maps/maplibre-gl.js'), null);
    assert.equal(P.isApp('map'), true);
    assert.equal(P.isApp('page'), false);
    assert.equal(P.isApp(null), false);
});

test('mapPlace: rounds, and keeps the bearing from -180 to 180', () => {
    assert.deepEqual(P.mapPlace(4.61234567, -74.0812346, 9.12345, 370, 12.345),
        { app: 'map', lat: 4.612346, lon: -74.081235, z: 9.12, b: 10, p: 12.3 });
    assert.equal(P.mapPlace(0, 0, 0, -190, 0).b, 170);
    assert.equal(P.mapPlace(0, 0, 0, 180, 0).b, 180);
    assert.equal(P.mapPlace(0, 0, 0, -180, 0).b, 180);
});

test('mapAt: small differences are the same place; bearing 179.9 and -179.8 are near', () => {
    const a = P.mapPlace(4.6, -74, 9, 179.9, 0);
    assert.equal(P.mapAt(P.mapPlace(4.600001, -74.000001, 9.001, 179.9, 0), a), true);
    assert.equal(P.mapAt(P.mapPlace(4.6, -74, 9, -179.8, 0), a), true);
    assert.equal(P.mapAt(P.mapPlace(4.7, -74, 9, 179.9, 0), a), false);
    assert.equal(P.mapAt(P.mapPlace(4.6, -74, 9.5, 179.9, 0), a), false);
    assert.equal(P.mapAt(P.mapPlace(4.6, -74, 9, 179.9, 30), a), false);
});

test('valid: a place from the box must have the fields of its app', () => {
    assert.equal(P.valid(P.mapPlace(1, 2, 3, 4, 5), 'map'), true);
    assert.equal(P.valid({ app: 'map', lat: 1, lon: 2, z: 3, b: 4 }, 'map'), false);
    assert.equal(P.valid({ app: 'map', lat: 'x', lon: 2, z: 3, b: 4, p: 0 }, 'map'), false);
    assert.equal(P.valid({ app: 'map', lat: Infinity, lon: 2, z: 3, b: 4, p: 0 }, 'map'), false);
    assert.equal(P.valid(P.mapPlace(1, 2, 3, 4, 5), 'page'), false);
    assert.equal(P.valid({ b: 3, n: 10, o: 0, d: 0 }, 'map'), false);
    assert.equal(P.valid(null, 'map'), false);
});

test('openUrl: a map opens at the place in its hash; other pages open as they are', () => {
    const a = P.mapPlace(4.6, -74, 9, 10, 20);
    assert.equal(P.openUrl('/maps/', a), '/maps/#4.6/-74/20/10/9');
    assert.equal(P.openUrl('/maps/#1/2/3/4/5', a), '/maps/#4.6/-74/20/10/9');
    assert.equal(P.openUrl('/maps/', null), '/maps/');
    assert.equal(P.openUrl('/kiwix/content/x/A/y', a), '/kiwix/content/x/A/y');
});

test('kindOf: the Calibre-Web PDF reader is a pdf; the catalog and other formats are for the teacher only', () => {
    assert.equal(P.kindOf('/books/read/2/pdf'), 'pdf');
    assert.equal(P.kindOf('/books/read/2/pdf#page=3'), 'pdf');
    assert.equal(P.kindOf('/books/read/x/pdf'), null);
    assert.equal(P.kindOf('/books/show/2/pdf'), null);
    assert.equal(P.kindOf('/books/read/2/cbz'), null);
    assert.equal(P.isApp('pdf'), true);
});

test('pdf: the scale goes to pdf.js as a name or as a factor; the place has page, top and scale', () => {
    assert.equal(P.pdfScaleValue('auto'), 'auto');
    assert.equal(P.pdfScaleValue(150), '1.5');
    const want = { app: 'pdf', page: 3, top: 400, left: -13, z: 'auto' };
    assert.equal(P.pdfAt({ app: 'pdf', page: 3, top: 402, left: -13, z: 'auto' }, want), true);
    assert.equal(P.pdfAt({ app: 'pdf', page: 4, top: 400, left: -13, z: 'auto' }, want), false);
    assert.equal(P.pdfAt({ app: 'pdf', page: 3, top: 300, left: -13, z: 'auto' }, want), false);
    assert.equal(P.pdfAt({ app: 'pdf', page: 3, top: 400, left: -13, z: 150 }, want), false);
    assert.equal(P.valid(want, 'pdf'), true);
    assert.equal(P.valid({ ...want, z: 150 }, 'pdf'), true);
    assert.equal(P.valid({ ...want, page: 0 }, 'pdf'), false);
    assert.equal(P.valid({ ...want, top: null }, 'pdf'), false);
    assert.equal(P.valid(want, 'map'), false);
    assert.equal(P.openUrl('/books/read/2/pdf', want), '/books/read/2/pdf');
});

test('epub: the Calibre-Web EPUB reader follows a CFI', () => {
    assert.equal(P.kindOf('/books/read/1/epub'), 'epub');
    assert.equal(P.kindOf('/books/read/1/epub#epubcfi(/6/8!/4/2/1:0)'), 'epub');
    assert.equal(P.isApp('epub'), true);
    assert.equal(P.valid({ app: 'epub', cfi: 'epubcfi(/6/8!/4/2/1:0)' }, 'epub'), true);
    assert.equal(P.valid({ app: 'epub', cfi: '/6/8' }, 'epub'), false);
    assert.equal(P.valid({ app: 'epub' }, 'epub'), false);
    assert.equal(P.valid({ app: 'epub', cfi: 'epubcfi(/6/8!/4/2/1:0)' }, 'pdf'), false);
});

test('APPS: the one list of the apps that the class follows; kindOf reads it', () => {
    ['Kiwix', 'Maps', 'Books'].forEach((id) => assert.ok(P.APPS.some((a: { id: string }) => a.id === id), id));
    P.APPS.forEach((a: { home: string }) => assert.match(a.home, /^\/[a-z]+\/$/));
    assert.equal(P.kindOf('/school/start.html'), null);
    assert.equal(P.kindOf('/home/'), null);
    assert.equal(P.kindOf('/books/read/3/mp4'), null);
    assert.equal(P.kindOf('/maps/'), 'map');
});

test('text.js: every app of APPS has its card words in each language', () => {
    const fs = require('fs');
    const path = require('path');
    const src: string = fs.readFileSync(path.join(__dirname, 'www', 'text.js'), 'utf8');
    const langs = src.split('\n').filter((l) => /^ {4}[a-z]+: \{/.test(l));
    assert.equal(langs.length, 34);
    langs.forEach((l) => {
        const table = JSON.parse(l.slice(l.indexOf('{'), l.lastIndexOf('}') + 1));
        P.APPS.forEach((a: { id: string }) => {
            assert.equal(typeof table['app' + a.id], 'string', l.slice(0, 8) + ' app' + a.id);
            assert.ok(table['app' + a.id].length > 0);
        });
    });
});
