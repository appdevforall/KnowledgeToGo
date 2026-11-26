/// <reference types="node" />
// The download sources of the books runner (no network).
import test from 'node:test';
import assert from 'node:assert/strict';
import { bookSources, sourceOrder } from './books.sources';

test('a numeric id gets the catalog URL first, then the mirror file', () => {
    assert.deepEqual(bookSources('11', 'https://www.gutenberg.org/ebooks/11.epub3.images'), [
        'https://www.gutenberg.org/ebooks/11.epub3.images',
        'https://gutenberg.pglaf.org/cache/epub/11/pg11-images-3.epub',
    ]);
});

test('an id that is not a number gets only the catalog URL', () => {
    assert.deepEqual(bookSources('11/../x', 'https://example.org/a.epub'), ['https://example.org/a.epub']);
    assert.deepEqual(bookSources('', 'https://example.org/a.epub'), ['https://example.org/a.epub']);
});

test('a catalog URL that is already the mirror URL is not repeated', () => {
    const m = 'https://gutenberg.pglaf.org/cache/epub/84/pg84-images-3.epub';
    assert.deepEqual(bookSources('84', m), [m]);
});

test('the order starts at the last source that worked', () => {
    assert.deepEqual(sourceOrder(2, 0), [0, 1]);
    assert.deepEqual(sourceOrder(2, 1), [1, 0]);
    assert.deepEqual(sourceOrder(3, 2), [2, 0, 1]);
    assert.deepEqual(sourceOrder(2, 5), [0, 1]);
    assert.deepEqual(sourceOrder(1, 0), [0]);
});
