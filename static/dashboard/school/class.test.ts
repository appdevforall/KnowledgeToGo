/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import { ClassRoom, cleanName, validLesson, MAX_NAME } from './class';

function counter(): () => string {
    let n = 0;
    return () => `token${String(++n).padStart(4, '0')}abcdef`;
}

test('cleanName: trims, drops control chars, caps the length, never empty', () => {
    assert.equal(cleanName('  Ana  '), 'Ana');
    assert.equal(cleanName('A\u0000b\nc'), 'Abc');
    assert.equal(cleanName('x'.repeat(100)).length, MAX_NAME);
    assert.equal(cleanName(''), 'Student');
    assert.equal(cleanName(undefined), 'Student');
});

test('validLesson: only a path on the same box', () => {
    assert.ok(validLesson({ url: '/kiwix/content/x/A/y', a: { b: 3, o: 0.5 } }));
    assert.equal(validLesson({ url: 'http://evil.example/' }), null);
    assert.equal(validLesson({ url: '//evil.example/' }), null);
    assert.equal(validLesson({ url: 'javascript:alert(1)' }), null);
    assert.equal(validLesson({}), null);
    assert.equal(validLesson([]), null);
    assert.equal(validLesson(null), null);
    assert.equal(validLesson({ url: '/p', pad: 'x'.repeat(5000) }), null);
});

test('join: gives a token; the student is online only while a channel is open', () => {
    const room = new ClassRoom(10, counter());
    const s = room.join('Ana')!;
    assert.equal(room.student(s.token), s);
    assert.equal(room.student('nope'), undefined);
    assert.equal(room.student(42), undefined);
    assert.deepEqual(room.roster(), [{ id: s.id, name: 'Ana', online: false, done: false }]);
    room.open(s);
    room.open(s);   // a reload can overlap two channels
    room.close(s);
    assert.equal(room.roster()[0].online, true);
    room.close(s);
    room.close(s);  // never below zero
    assert.equal(room.roster()[0].online, false);
});

test('join: a full class drops students with no open channel, else refuses', () => {
    const room = new ClassRoom(2, counter());
    const a = room.join('A')!;
    const b = room.join('B')!;
    room.open(a);
    room.open(b);
    assert.equal(room.join('C'), null);
    room.close(b);
    const c = room.join('C')!;
    assert.ok(c);
    assert.deepEqual(room.roster().map((s) => s.name), ['A', 'C']);
});

test('publish: keeps the last lesson for late students; a new page clears Done', () => {
    const room = new ClassRoom(10, counter());
    assert.equal(room.current(), null);
    const s = room.join('Ana')!;
    const first = room.publish({ url: '/p1', y: 0 });
    assert.equal(first.newPage, true);
    room.setDone(s, true);
    const scroll = room.publish({ url: '/p1', y: 300 });
    assert.equal(scroll.newPage, false);
    assert.equal(room.roster()[0].done, true);
    assert.deepEqual(room.current(), { id: scroll.id, lesson: { url: '/p1', y: 300 } });
    room.publish({ url: '/p2', y: 0 });
    assert.equal(room.roster()[0].done, false);
    assert.ok(room.current()!.id > scroll.id);
});
