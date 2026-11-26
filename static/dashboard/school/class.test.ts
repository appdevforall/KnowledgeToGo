/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import {
    ClassRoom, Student, validLesson, validMark, CLASS_GAP_MS, JOIN_LIMIT, JOIN_WINDOW_MS, MAX_MARK_BLOCKS,
    MAX_MARKS, MAX_NAME, OFFLINE_GRACE_MS, QUIET_MS, cleanName, newClassCode,
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

test('validMark: only the known kinds and fields, numbers in range', () => {
    const invalid = { error: 'invalid' };
    assert.deepEqual(validMark({ k: 'b', b: 3, t: 'abc1', extra: 'x' }), { mark: { k: 'b', b: 3, t: 'abc1' } });
    assert.deepEqual(validMark({ k: 'b', b: 3, t: 'abc1', cell: 2 }), { mark: { k: 'b', b: 3, t: 'abc1', cell: 2 } });
    assert.deepEqual(validMark({ k: 'p', b: 3, t: 'a', s: 4, eb: 3, et: 'a', e: 9 }),
        { mark: { k: 'p', b: 3, t: 'a', s: 4, eb: 3, et: 'a', e: 9 } });
    assert.ok('mark' in validMark({ k: 'p', b: 3, t: 'a', s: 40, eb: 4, et: 'b', e: 2 }));   // ends in the next block
    assert.deepEqual(validMark({ k: 'p', b: 3, t: 'a', s: 4, eb: 3, et: 'a', e: 4 }), invalid);   // empty
    assert.deepEqual(validMark({ k: 'p', b: 3, t: 'a', s: 4, eb: 2, et: 'a', e: 9 }), invalid);   // ends before it starts
    assert.ok('mark' in validMark({ k: 'p', b: 0, t: 'a', s: 0, eb: MAX_MARK_BLOCKS - 1, et: 'a', e: 1 }));
    assert.deepEqual(validMark({ k: 'p', b: 0, t: 'a', s: 0, eb: MAX_MARK_BLOCKS, et: 'a', e: 1 }), { error: 'long' });
    assert.deepEqual(validMark({ k: 'b', b: -1, t: 'a' }), invalid);
    assert.deepEqual(validMark({ k: 'b', b: 1.5, t: 'a' }), invalid);
    assert.deepEqual(validMark({ k: 'b', b: '1', t: 'a' }), invalid);
    assert.deepEqual(validMark({ k: 'b', b: 1, t: '<b>' }), invalid);
    assert.deepEqual(validMark({ k: 'b', b: 1, t: 'a', cell: -2 }), invalid);
    const box = { k: 'r', b: 2, t: 'a', y: -4, eb: 5, et: 'b', ey: 30, x: 10, w: 100 };
    assert.deepEqual(validMark({ ...box, z: 1 }), { mark: box });
    assert.deepEqual(validMark({ ...box, w: 0 }), invalid);              // no width
    assert.deepEqual(validMark({ ...box, x: 1.5 }), invalid);
    assert.deepEqual(validMark({ ...box, x: -5000 }), invalid);          // far off the page
    assert.deepEqual(validMark({ ...box, w: 5000 }), invalid);
    assert.deepEqual(validMark({ ...box, ey: 2e6 }), invalid);
    assert.deepEqual(validMark({ ...box, et: '<' }), invalid);
    assert.deepEqual(validMark({ ...box, o: 1 }), { mark: { ...box, o: 1 } });    // an oval
    assert.deepEqual(validMark({ ...box, o: 2 }), invalid);
    assert.deepEqual(validMark({ ...box, o: true }), invalid);
    assert.deepEqual(validMark({ k: 'x', b: 1, t: 'a' }), invalid);
    // EPUB marks: a CFI range of the book, no block.
    const cfi = 'epubcfi(/6/4[ch1]!/4,/2/1:0,/6/1:9)';
    assert.deepEqual(validMark({ k: 'p', cfi, b: 3 }), { mark: { k: 'p', cfi } });
    assert.deepEqual(validMark({ k: 'r', cfi, o: 1 }), { mark: { k: 'r', cfi, o: 1 } });
    assert.deepEqual(validMark({ k: 'b', cfi }), { mark: { k: 'b', cfi } });
    assert.deepEqual(validMark({ k: 'p', cfi, o: 1 }), invalid);                       // an oval is a box
    assert.deepEqual(validMark({ k: 'x', cfi }), invalid);
    assert.deepEqual(validMark({ k: 'p', cfi: '/6/4!/4/2' }), invalid);                // no epubcfi()
    assert.deepEqual(validMark({ k: 'p', cfi: 'epubcfi(/6/4!/4/2\n)' }), invalid);    // a control character
    assert.deepEqual(validMark({ k: 'p', cfi: 'epubcfi(/6/4[cap\u00edtulo1]!/4/2)' }), { mark: { k: 'p', cfi: 'epubcfi(/6/4[cap\u00edtulo1]!/4/2)' } });   // an id in Spanish
    assert.deepEqual(validMark({ k: 'p', cfi: 'epubcfi(/6/4!/4/2\u0085)' }), invalid);   // a C1 control character
    // A box with its corners from its first and last word; all four, whole numbers.
    const corners = { x: -50, y: -20, ex: 30, ey: 25 };
    assert.deepEqual(validMark({ k: 'r', cfi, ...corners, o: 1 }), { mark: { k: 'r', cfi, o: 1, ...corners } });
    assert.deepEqual(validMark({ k: 'r', cfi, ...corners }), { mark: { k: 'r', cfi, ...corners } });
    assert.deepEqual(validMark({ k: 'r', cfi, x: 1, y: 2, ex: 3 }), invalid);          // a corner value is missing
    assert.deepEqual(validMark({ k: 'r', cfi, ...corners, ey: 1.5 }), invalid);
    assert.deepEqual(validMark({ k: 'r', cfi, ...corners, x: 2e6 }), invalid);
    assert.deepEqual(validMark({ k: 'p', cfi, ...corners }), invalid);                 // corners are for a box
    assert.deepEqual(validMark({ k: 'p', cfi: 'epubcfi(/' + '2/'.repeat(250) + ')' }), invalid);   // too long
    assert.deepEqual(validMark({ k: 'p', cfi: 5 }), invalid);
    assert.deepEqual(validMark([]), invalid);
    assert.deepEqual(validMark(null), invalid);
});

test('marks: belong to the lesson page; a new page and the end of the class clear them', () => {
    const room = openRoom();
    const block = { k: 'b' as const, b: 1, t: 'a' };
    assert.deepEqual(room.addMark('/p1', block), { refused: 'other-page' });   // no lesson yet
    assert.equal(room.marks(), null);
    room.publish({ url: '/p1' });
    assert.deepEqual(room.addMark('/p1', block), { changed: true });
    assert.deepEqual(room.addMark('/p1', { ...block }), { changed: false });      // two fast taps: one mark
    assert.deepEqual(room.addMark('/p2', block), { refused: 'other-page' });
    assert.equal(room.publish({ url: '/p1', a: { b: 9 } })!.newPage, false);      // a scroll keeps them
    assert.deepEqual(room.marks(), { url: '/p1', list: [{ ...block, id: 1 }] });
    assert.equal(room.publish({ url: '/p2' })!.newPage, true);
    assert.deepEqual(room.marks(), { url: '/p2', list: [] });
    room.addMark('/p2', block);
    room.end();
    room.teacherOpen();
    assert.equal(room.marks(), null);                                          // a new class: no marks
    assert.deepEqual(room.addMark('/p3', block), { refused: 'other-page' });
    room.end();
    assert.deepEqual(room.addMark('/p3', block), { refused: 'no-class' });
});

test('marks: at most MAX_MARKS; remove by id; clear; ids are not used again', () => {
    const room = openRoom();
    room.publish({ url: '/p' });
    for (let i = 0; i < MAX_MARKS; i += 1) {
        assert.deepEqual(room.addMark('/p', { k: 'b', b: i, t: 'a' }), { changed: true });
    }
    const ids = room.marks()!.list.map((m) => m.id);
    assert.deepEqual(room.addMark('/p', { k: 'b', b: 99, t: 'a' }), { refused: 'full' });
    assert.deepEqual(room.removeMark('/p', ids[0]), { changed: true });
    assert.deepEqual(room.removeMark('/p', ids[0]), { changed: false });          // already gone
    assert.deepEqual(room.removeMark('/other', ids[1]), { refused: 'other-page' });
    room.addMark('/p', { k: 'b', b: 99, t: 'a' });
    const again = room.marks()!.list.find((m) => 'b' in m && m.b === 99)!;
    assert.ok(!ids.includes(again.id));
    const box = { k: 'r' as const, b: 1, t: 'a', y: 0, eb: 2, et: 'a', ey: 10, x: 5, w: 50 };
    room.removeMark('/p', ids[1]);
    assert.deepEqual(room.addMark('/p', box), { changed: true });
    const id = room.marks()!.list.find((m) => m.k === 'r')!.id;
    const at = room.marks()!.list.findIndex((m) => m.id === id);
    assert.deepEqual(room.moveMark('/p', id, { ...box, x: 40, o: 1 }), { changed: true });
    const moved = room.marks()!.list[at];
    assert.equal(moved.id, id);                                                     // same id, same place in the list
    assert.deepEqual({ ...moved, id: 0 }, { ...box, x: 40, o: 1, id: 0 });
    assert.deepEqual(room.moveMark('/p', id, { ...box, x: 40, o: 1 }), { changed: false });   // no move
    assert.deepEqual(room.moveMark('/p', 999, box), { changed: false });            // gone
    assert.deepEqual(room.moveMark('/other', id, box), { refused: 'other-page' });
    const block = room.marks()!.list.find((m) => m.k === 'b')!;
    assert.deepEqual(room.moveMark('/p', block.id, box), { changed: false });      // only a box moves
    assert.deepEqual(room.moveMark('/p', id, { k: 'b', b: 3, t: 'a' }), { changed: false });
    room.removeMark('/p', ids[2]);
    assert.deepEqual(room.addMark('/p', box), { changed: true });
    const n =room.marks()!.list.length;
    assert.deepEqual(room.moveMark('/p', id, box), { changed: true });              // onto the other box: one mark
    assert.equal(room.marks()!.list.length, n - 1);
    assert.deepEqual(room.clearMarks('/p'), { changed: true });
    assert.deepEqual(room.clearMarks('/p'), { changed: false });
    assert.deepEqual(room.marks()!.list, []);
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
    assert.deepEqual(room.state(), { open: false, code: null, name: null, ageSec: null });
    room.teacherOpen();
    assert.deepEqual(room.state(), { open: true, code: 'C1', name: null, ageSec: 0 });
    room.teacherOpen();                      // a second teacher channel (reload overlap)
    room.teacherClose();
    room.teacherClose();
    assert.deepEqual(room.state(), { open: true, code: 'C1', name: null, ageSec: 0 });   // a reload: no gap on the network
    t += CLASS_GAP_MS;
    assert.equal(room.lapsed(), false);
    room.teacherOpen();                      // the page comes back in time: same class
    assert.deepEqual(room.state(), { open: true, code: 'C1', name: null, ageSec: 60 });
    room.teacherClose();
    room.teacherClose();                     // an extra close does not go below zero
    t += CLASS_GAP_MS + 1;
    assert.equal(room.lapsed(), true);       // the router ends it and tells the students
    room.end();                              // (router endClass)
    room.teacherOpen();                      // too late: a new class
    assert.deepEqual(room.state(), { open: true, code: 'C2', name: null, ageSec: 0 });
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
    assert.equal(room.lapsed(), true);
    room.end();                              // the router ends a lapsed class (endClass)
    room.teacherOpen();                      // the next lesson day
    assert.deepEqual(room.state(), { open: true, code: 'C2', name: null, ageSec: 0 });
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
    assert.deepEqual(room.state(), { open: false, code: null, name: null, ageSec: null });   // the page is still connected
    assert.deepEqual(room.roster(), []);
    assert.equal(room.student(r.student.token), undefined);        // its token stops working
    assert.equal(room.current(), null);
    assert.equal(room.lapsed(), false);
    room.teacherClose();
    room.teacherOpen();                                            // "Start a new class", at once
    assert.deepEqual(room.state(), { open: true, code: 'C2', name: null, ageSec: 0 });
    room.teacherOpen();                                            // a second page of the same class
    assert.deepEqual(room.state(), { open: true, code: 'C2', name: null, ageSec: 0 });
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

test('cleanName: trim, one space, no control characters, at most MAX_NAME code points', () => {
    assert.equal(cleanName('  Math   101 '), 'Math 101');
    assert.equal(cleanName('1\tA\n'), '1 A');                          // tab and newline are spaces
    assert.equal(cleanName('Ma\u0000th\u0007'), 'Math');              // C0 controls go
    assert.equal(cleanName(`a${String.fromCodePoint(0x202e)}b${String.fromCodePoint(0x2066)}c`), 'abc');   // bidi controls go
    assert.equal(cleanName('x\uD800y'), 'xy');                        // a lone surrogate goes
    assert.equal(cleanName('a \u0000 b'), 'a b');                     // no double space after a drop
    assert.equal(cleanName('x'.repeat(30)), 'x'.repeat(MAX_NAME));
    const emoji = '\u{1F4D8}';                                          // one code point, two UTF-16 units
    assert.equal(cleanName(emoji.repeat(25)), emoji.repeat(MAX_NAME));
    const accent = String.fromCodePoint(0xe1);   // two UTF-8 bytes, one code point
    assert.equal(cleanName(`Matem${accent}ticas para todos 1 A`), `Matem${accent}ticas para tod`);
    assert.equal(cleanName(`${'y'.repeat(19)} z`), 'y'.repeat(19));     // no space at the end of the cut
    assert.equal(cleanName('   '), null);
    assert.equal(cleanName(String.fromCodePoint(0x200b, 0xfeff)), null);   // format characters only
    assert.equal(cleanName(`a${String.fromCodePoint(0x200c)}b`), `a${String.fromCodePoint(0x200c)}b`);   // ZWNJ stays (Persian)
    assert.equal(cleanName('\u0000'), null);
    assert.equal(cleanName(42), null);
    assert.equal(cleanName(null), null);
});

test('class start: the app opens the class with its name; no rename; gone with the class', () => {
    let t = 0;
    let n = 0;
    const room = new ClassRoom(10, counter(), () => t, () => `C${++n}`);
    assert.equal(room.start('   '), 'ok');                     // an empty name: no name
    assert.deepEqual(room.state(), { open: true, code: 'C1', name: null, ageSec: 0 });
    assert.equal(room.start('Art'), 'open');                    // no rename during the class
    room.end();
    assert.equal(room.start('\u0000'), 'ok');                   // no character can stay: no name
    assert.equal(room.state().name, null);
    room.end();
    assert.equal(room.start(7), 'ok');                          // not a text: no name
    assert.equal(room.state().name, null);
    room.end();
    assert.equal(room.start('  Math   101 '), 'ok');
    assert.deepEqual(room.state(), { open: true, code: 'C4', name: 'Math 101', ageSec: 0 });
    room.teacherOpen();                                         // the teacher page comes: same class
    assert.equal(room.state().name, 'Math 101');
    room.teacherClose();
    room.end();
    assert.equal(room.state().name, null);
    room.teacherOpen();                                         // a teacher page with no start: no name
    assert.deepEqual(room.state(), { open: true, code: 'C5', name: null, ageSec: 0 });
    room.teacherClose();
    room.end();
    assert.equal(room.start(null), 'ok');
    assert.equal(room.lapsed(), false);
    t += CLASS_GAP_MS + 1;                                      // no teacher page came
    assert.equal(room.lapsed(), true);
});

test('class age: whole seconds since the class opened, on the clock of the box', () => {
    let t = 1000;
    const room = new ClassRoom(10, counter(), () => t, () => 'C1');
    room.teacherOpen();
    t += 999;
    assert.equal(room.state().ageSec, 0);
    t += 1;
    assert.equal(room.state().ageSec, 1);
    t += 2 * 3600 * 1000;
    assert.equal(room.state().ageSec, 7201);
    room.teacherOpen();                       // a second teacher page does not start the age again
    assert.equal(room.state().ageSec, 7201);
    t -= 10 * 3600 * 1000;                    // the box clock went back: never a negative age
    assert.equal(room.state().ageSec, 0);
});
