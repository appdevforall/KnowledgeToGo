/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import {
    ClassRoom, Student, validLesson, CLASS_GAP_MS, JOIN_LIMIT, JOIN_WINDOW_MS, OFFLINE_GRACE_MS, QUIET_MS,
    newClassCode,
} from './class';

function counter(): () => string {
    let n = 0;
    return () => `token${String(++n).padStart(4, '0')}abcdef`;
}

test('validLesson: only a path on the same box', () => {
    assert.ok(validLesson({ url: '/kiwix/content/x/A/y', a: { b: 3, o: 0.5 } }));
    assert.equal(validLesson({ url: 'http://evil.example/' }), null);
    assert.equal(validLesson({ url: '//evil.example/' }), null);
    assert.equal(validLesson({ url: '/\\evil.example/' }), null);      // a browser reads it as //evil.example
    assert.equal(validLesson({ url: '/\\/evil.example/' }), null);
    assert.equal(validLesson({ url: '/\t/evil.example/' }), null);     // the URL parser drops tab and newline
    assert.equal(validLesson({ url: '/\n/evil.example/' }), null);
    assert.ok(validLesson({ url: '/kiwix/content/x/A/y?q=1#top' }));
    assert.equal(validLesson({ url: 'javascript:alert(1)' }), null);
    assert.equal(validLesson({}), null);
    assert.equal(validLesson([]), null);
    assert.equal(validLesson(null), null);
    assert.equal(validLesson({ url: '/p', pad: 'x'.repeat(5000) }), null);
});

// A room with an open class (a teacher page connected).
function openRoom(max = 10, clock: () => number = Date.now): ClassRoom {
    const room = new ClassRoom(max, counter(), clock, () => 'C1');
    room.teacherOpen();
    return room;
}

// Joins from different client addresses, so the join limit does not count.
let ipN = 0;
function joined(room: ClassRoom): Student {
    const r = room.join(`192.168.1.${++ipN}`);
    assert.ok('student' in r, JSON.stringify(r));
    return r.student;
}

test('join: gives a token and a seat; the student is online only while a channel is open', () => {
    const room = openRoom();
    const s = joined(room);
    assert.equal(s.seat, 1);
    assert.equal(room.student(s.token), s);
    assert.equal(room.student('nope'), undefined);
    assert.equal(room.student(42), undefined);
    assert.deepEqual(room.roster(), [{ seat: 1, online: false, done: false, quiet: false }]);
    assert.ok(!('name' in s), 'no name on the box');
    room.open(s);
    room.open(s);   // a reload can overlap two channels
    room.close(s);
    assert.equal(room.roster()[0].online, true);
    room.close(s);
    room.close(s);  // never below zero
    assert.equal(room.roster()[0].online, false);
});

test('seats: the lowest free seat; a seat stays with its token over reconnects', () => {
    const room = openRoom();
    const a = joined(room);
    const b = joined(room);
    const c = joined(room);
    assert.deepEqual([a.seat, b.seat, c.seat], [1, 2, 3]);
    room.open(b);
    room.close(b);                 // Wi-Fi drop
    room.open(b);                  // back: same object, same seat
    assert.equal(room.student(b.token)!.seat, 2);
    room.prune(0);                 // a and c never opened a channel: they leave
    assert.deepEqual(room.roster().map((s) => s.seat), [2]);
    assert.equal(joined(room).seat, 1);   // the free seat is used again
    assert.equal(joined(room).seat, 3);
});

test('join: refused when no class is open, or for a page of an ended class', () => {
    const room = new ClassRoom(10, counter(), Date.now, () => 'C1');
    assert.deepEqual(room.join('192.168.1.10'), { refused: 'no-class' });
    room.teacherOpen();
    assert.ok('student' in room.join('192.168.1.10', 'C1'));
    assert.deepEqual(room.join('192.168.1.10', 'OLD1'), { refused: 'no-class' });
    room.end();
    assert.deepEqual(room.join('192.168.1.10'), { refused: 'no-class' });
});

test('join: at most JOIN_LIMIT joins per client address in a window', () => {
    let t = 0;
    const room = openRoom(100, () => t);
    for (let i = 0; i < JOIN_LIMIT; i += 1) assert.ok('student' in room.join('192.168.1.50'));
    assert.deepEqual(room.join('192.168.1.50'), { refused: 'too-many' });
    assert.ok('student' in room.join('192.168.1.51'));          // another phone is not stopped
    t += JOIN_WINDOW_MS - 1;
    assert.deepEqual(room.join('192.168.1.50'), { refused: 'too-many' });
    t += 1;
    assert.ok('student' in room.join('192.168.1.50'));
    t += JOIN_WINDOW_MS;
    room.prune();
    assert.equal(room.joinClients(), 0);                       // old join times go
});

test('join: a full class drops only a student offline past the grace, oldest first', () => {
    let t = 0;
    const room = openRoom(3, () => t);
    const a = joined(room);
    const b = joined(room);
    const c = joined(room);
    [a, b, c].forEach((s) => room.open(s));
    assert.deepEqual(room.join('192.168.1.200'), { refused: 'full' });
    room.close(b);                                  // b: a short Wi-Fi drop
    t += OFFLINE_GRACE_MS - 1;
    assert.deepEqual(room.join('192.168.1.201'), { refused: 'full' });   // b keeps its place
    room.close(c);
    t += 2;
    const d = room.join('192.168.1.202');           // b is past the grace, c is not
    assert.ok('student' in d);
    assert.equal(room.student(b.token), undefined);
    assert.equal(room.student(c.token), c);
    assert.equal(d.student.seat, 2);                // the seat of b
});

test('publish: keeps the last lesson for late students; a new page clears Done', () => {
    const room = openRoom();
    assert.equal(room.current(), null);
    const s = joined(room);
    const first = room.publish({ url: '/p1', y: 0 })!;
    assert.equal(first.newPage, true);
    room.setDone(s, true);
    const scroll = room.publish({ url: '/p1', y: 300 })!;
    assert.equal(scroll.newPage, false);
    assert.equal(room.roster()[0].done, true);
    assert.deepEqual(room.current(), { id: scroll.id, lesson: { url: '/p1', y: 300 } });
    room.publish({ url: '/p2', y: 0 });
    assert.equal(room.roster()[0].done, false);
    assert.ok(room.current()!.id > scroll.id);
    room.end();
    assert.equal(room.publish({ url: '/p3' }), null);   // no class: nothing to publish
});

test('prune: a student offline for too long leaves; a short drop keeps the place', () => {
    let now = 1000;
    const room = openRoom(10, () => now);
    const stays = joined(room);
    const drops = joined(room);
    const never = joined(room);
    room.open(stays);
    room.open(drops);
    now += 10000;
    room.close(drops);           // Wi-Fi off
    now += 30000;
    assert.equal(room.prune(60000), 0);
    room.open(drops);            // Wi-Fi back after 30 s: same place
    now += 60000;
    assert.equal(room.prune(60000), 1);   // the one that never opened a channel
    assert.equal(room.student(never.token), undefined);
    assert.deepEqual(room.roster().map((s) => s.seat), [stays.seat, drops.seat]);
    room.close(stays);
    now += 59999;
    assert.equal(room.prune(60000), 0);
    now += 1;
    assert.equal(room.prune(60000), 1);
    assert.equal(room.student(stays.token), undefined);
    assert.equal(room.student(drops.token), drops);
});

test('quiet: an open channel with no word for QUIET_MS; the next ack says it is back', () => {
    let now = 0;
    const room = openRoom(10, () => now);
    const s = joined(room);
    room.open(s);
    now += QUIET_MS;
    assert.equal(room.roster()[0].quiet, false);
    now += 1;
    assert.equal(room.roster()[0].quiet, true);    // rehearsal F7: long before SILENT_MS
    assert.equal(room.heard(s), true);             // it was quiet
    assert.equal(room.roster()[0].quiet, false);
    assert.equal(room.heard(s), false);
});

test('silent: an open channel with no word for too long is dead; an ack keeps it alive', () => {
    let now = 0;
    const room = openRoom(10, () => now);
    const s = joined(room);
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

test('class session: one code per class, kept over a short teacher gap; a long gap ends it', () => {
    let t = 0;
    let n = 0;
    const room = new ClassRoom(1000, counter(), () => t, () => `C${++n}`);
    assert.deepEqual(room.state(), { open: false, code: null });
    room.teacherOpen();
    assert.deepEqual(room.state(), { open: true, code: 'C1' });
    room.teacherOpen();                      // a second teacher channel (reload overlap)
    room.teacherClose();
    room.teacherClose();
    assert.deepEqual(room.state(), { open: true, code: 'C1' });   // a reload: no gap on the network
    t += CLASS_GAP_MS;
    assert.equal(room.lapsed(), false);
    room.teacherOpen();                      // the page comes back in time: same class
    assert.deepEqual(room.state(), { open: true, code: 'C1' });
    room.teacherClose();
    room.teacherClose();                     // an extra close does not go below zero
    t += CLASS_GAP_MS + 1;
    assert.equal(room.lapsed(), true);       // the router ends it and tells the students
    room.teacherOpen();                      // too late: a new class
    assert.deepEqual(room.state(), { open: true, code: 'C2' });
    assert.equal(room.lapsed(), false);
});

test('class session: a new class has no lesson, seat or Done mark of the old one (review S2)', () => {
    let t = 0;
    let n = 0;
    const room = new ClassRoom(1000, counter(), () => t, () => `C${++n}`);
    room.teacherOpen();
    const old = room.join('192.168.1.70');
    assert.ok('student' in old);
    room.open(old.student);
    room.setDone(old.student, true);
    room.publish({ url: '/old' });
    room.teacherClose();                     // the teacher leaves with no "End class"
    t += CLASS_GAP_MS + 1;
    room.teacherOpen();                      // the next lesson day
    assert.deepEqual(room.state(), { open: true, code: 'C2' });
    assert.equal(room.current(), null);
    assert.deepEqual(room.roster(), []);
    assert.equal(room.student(old.student.token), undefined);
});

test('end: the class closes at once, the students and the lesson go, the next class is new', () => {
    let n = 0;
    const room = new ClassRoom(1000, counter(), Date.now, () => `C${++n}`);
    room.teacherOpen();
    const r = room.join('192.168.1.80');
    assert.ok('student' in r);
    room.open(r.student);
    room.publish({ url: '/kiwix/content/x/A/y' });
    room.end();
    assert.deepEqual(room.state(), { open: false, code: null });   // the page is still connected
    assert.deepEqual(room.roster(), []);
    assert.equal(room.student(r.student.token), undefined);        // its token stops working
    assert.equal(room.current(), null);
    assert.equal(room.lapsed(), false);
    room.teacherClose();
    room.teacherOpen();                                            // "Start a new class", at once
    assert.deepEqual(room.state(), { open: true, code: 'C2' });
    room.teacherOpen();                                            // a second page of the same class
    assert.deepEqual(room.state(), { open: true, code: 'C2' });
});

test('newClassCode: 4 characters from the easy-to-read set', () => {
    for (let i = 0; i < 200; i += 1) assert.match(newClassCode(), /^[2-9A-HJ-NP-Z]{4}$/);
});

test('class language: the teacher page sets it; a bad value is ignored; a new class starts with none', () => {
    let t = 0;
    const room = new ClassRoom(10, counter(), () => t, () => 'C1');
    assert.equal(room.lang(), null);
    room.teacherOpen('es');
    assert.equal(room.lang(), 'es');
    room.teacherOpen('<b>');                 // not a language key: the class keeps its language
    assert.equal(room.lang(), 'es');
    room.teacherOpen('fr');                  // the teacher page loads again in another language
    assert.equal(room.lang(), 'fr');
    room.end();
    assert.equal(room.lang(), null);
    room.teacherOpen();
    assert.equal(room.lang(), null);
});
