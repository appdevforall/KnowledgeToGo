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
