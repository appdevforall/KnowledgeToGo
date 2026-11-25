/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import { ClassRoom, cleanName, validLesson, MAX_NAME, CLASS_GAP_MS, newClassCode } from './class';

function counter(): () => string {
    let n = 0;
    return () => `token${String(++n).padStart(4, '0')}abcdef`;
}

test('cleanName: trims, drops control chars, caps the length, never empty', () => {
    assert.equal(cleanName('  Student A  '), 'Student A');
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
    const s = room.join('Student A')!;
    assert.equal(room.student(s.token), s);
    assert.equal(room.student('nope'), undefined);
    assert.equal(room.student(42), undefined);
    assert.deepEqual(room.roster(), [{ id: s.id, name: 'Student A', online: false, done: false }]);
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
    const s = room.join('Student A')!;
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

test('expireOffline: a student offline for too long leaves; a short drop keeps the place', () => {
    let now = 1000;
    const room = new ClassRoom(10, counter(), () => now);
    const stays = room.join('Stays')!;
    const drops = room.join('Drops')!;
    const never = room.join('Never opened')!;
    room.open(stays);
    room.open(drops);
    now += 10000;
    room.close(drops);           // Wi-Fi off
    now += 30000;
    assert.equal(room.expireOffline(60000), 0);
    room.open(drops);            // Wi-Fi back after 30 s: same place
    now += 60000;
    assert.equal(room.expireOffline(60000), 1);   // the one that never opened a channel
    assert.equal(room.student(never.token), undefined);
    assert.deepEqual(room.roster().map((s) => s.name), ['Stays', 'Drops']);
    room.close(stays);
    now += 59999;
    assert.equal(room.expireOffline(60000), 0);
    now += 1;
    assert.equal(room.expireOffline(60000), 1);
    assert.equal(room.student(stays.token), undefined);
    assert.equal(room.student(drops.token), drops);
});

test('silent: an open channel with no word for too long is dead; an ack keeps it alive', () => {
    let now = 0;
    const room = new ClassRoom(10, counter(), () => now);
    const s = room.join('Student A')!;
    assert.equal(room.silent(s, 90000), false);   // no channel: nothing to close
    room.open(s);
    now += 60000;
    room.heard(s);
    now += 90000;
    assert.equal(room.silent(s, 90000), false);
    now += 1;
    assert.equal(room.silent(s, 90000), true);
    room.close(s);
    assert.equal(room.silent(s, 90000), false);
    room.open(s);                                  // a new channel counts as a word
    assert.equal(room.silent(s, 90000), false);
});

test('class code: new when a class opens, kept over a short teacher gap, new after a long one', () => {
    let t = 0;
    let n = 0;
    const room = new ClassRoom(1000, counter(), () => t, () => `C${++n}`);
    assert.deepEqual(room.state(), { open: false, code: null });
    room.teacherOpen();
    assert.deepEqual(room.state(), { open: true, code: 'C1' });
    room.teacherOpen();                      // a second teacher channel (reload overlap)
    room.teacherClose();
    assert.deepEqual(room.state(), { open: true, code: 'C1' });
    room.teacherClose();
    assert.deepEqual(room.state(), { open: false, code: null });   // closed: no code on the network
    t += CLASS_GAP_MS;                       // the page comes back in time: same class
    room.teacherOpen();
    assert.deepEqual(room.state(), { open: true, code: 'C1' });
    room.teacherClose();
    t += CLASS_GAP_MS + 1;                   // too late: a new class
    room.teacherOpen();
    assert.deepEqual(room.state(), { open: true, code: 'C2' });
    room.teacherClose();
    room.teacherClose();                     // an extra close does not go below zero
    room.teacherOpen();
    assert.equal(room.state().open, true);
});

test('newClassCode: 4 characters from the easy-to-read set', () => {
    for (let i = 0; i < 200; i += 1) assert.match(newClassCode(), /^[2-9A-HJ-NP-Z]{4}$/);
});
