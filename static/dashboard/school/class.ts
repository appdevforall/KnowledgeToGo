// K2Go School: the state of the class on the teacher box. Pure: no Express, no I/O.
// The box is the one owner of "who is in the class": a student is online while its live channel
// is open, not while it sends acks (M0 finding: counts from acks or from open tabs disagree).
// The state is in memory: a dash-node restart clears it, and the class ends.
//
// A class identifies phones, not people: each student token gets a seat number in the class
// ("Eq. 01" on the pages). No name goes to the box (K2G-P5). One phone is one seat: the
// "Another student" button of a shared phone was removed.
import crypto from 'crypto';

export interface Student {
    seat: number;
    token: string;
    streams: number;    // open live channels of this student (a reload can overlap two)
    done: boolean;
    offlineSince: number | null;   // ms; null while a live channel is open
    lastHeard: number;             // ms; the last join, channel open or ack of the student
}

export interface RosterEntry {
    seat: number;
    online: boolean;
    done: boolean;
    quiet: boolean;     // the channel is open, but the student sent no word for QUIET_MS
}

// What the teacher sends: the page path and the scroll place. Other fields pass through.
export interface Lesson {
    url: string;
    [key: string]: unknown;
}

// Seats 1 to 99: two digits on the pages.
export const MAX_SEATS = 99;
// The class cap: near the size of a real class, so a flood of fake joins cannot fill the teacher
// screen with hundreds of entries.
export const MAX_STUDENTS = 50;
// A student with no open channel for this long leaves the class: a Wi-Fi drop of some seconds
// keeps the place, a phone that left does not stay in the teacher list for ever.
export const OFFLINE_EXPIRE_MS = 120000;
// A full class gives the place of a student that is offline for longer than this to a new one.
// A shorter drop (a Wi-Fi drop, a reload) keeps the place.
export const OFFLINE_GRACE_MS = 30000;
// A phone that loses the Wi-Fi does not close its channel: the box sees no error for minutes.
// The page sends an ack every 5 s; a channel with no word for this long is dead. Longer than
// one minute, because a browser runs the timers of a hidden page only once a minute.
export const SILENT_MS = 90000;
// A student with an open channel and no word for this long shows as "bad" on the teacher page,
// long before the box closes the channel (rehearsal F7). Two acks of 5 s missed, plus a margin.
export const QUIET_MS = 12000;
// Joins per client address in JOIN_WINDOW_MS. A real student joins once (again after a box
// restart or a long drop); a script that joins in a loop is stopped.
export const JOIN_LIMIT = 5;
export const JOIN_WINDOW_MS = 60000;
const MAX_LESSON_BYTES = 2048;
// Marks (M2): the parts of the lesson page that the teacher marked. A limit, so that a page stays
// readable and the list stays small on 30 phones.
export const MAX_MARKS = 10;
// A phrase mark can go over at most this many blocks.
export const MAX_MARK_BLOCKS = 20;
const MAX_MARK_INDEX = 1000000;
// Frame CSS px of a box mark. Down the page: far more than a page. Across the page: some frame
// widths (the frame is 400 px wide), so a box stays near the page.
const MAX_MARK_PX = 1000000;
const MAX_MARK_ACROSS = 2000;
// A teacher page that comes back within this time (a reload, a theme change) continues the same
// class, with the same code. With no teacher page for longer, the class ends by itself.
export const CLASS_GAP_MS = 60000;
// The class code: what the students see in the list and the teacher sees on the page. No 0/O and
// 1/I, so it is easy to read aloud. Random per class: it does not identify the phone (K2G-P5).
export const CODE_CHARS = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
export const CODE_LENGTH = 4;

export function newClassCode(): string {
    const bytes = crypto.randomBytes(CODE_LENGTH);
    let code = '';
    for (let i = 0; i < CODE_LENGTH; i += 1) code += CODE_CHARS[bytes[i] % CODE_CHARS.length];
    return code;
}

// What the box tells the class network: is a class open, and its code. Nothing else.
export interface ClassState {
    open: boolean;
    code: string | null;
}

// A lesson URL must be a path on the same box: the student frame loads it, and the scroll works
// only for a page of the same origin. A browser reads "/\host" as "//host" (another site), so the
// check parses the URL as a browser does and refuses a backslash (review S6).
const SAME_BOX = 'http://box';
export function sameBoxPath(url: string): boolean {
    if (!url.startsWith('/') || url.includes('\\')) return false;
    try {
        return new URL(url, SAME_BOX).origin === SAME_BOX;
    } catch {
        return false;
    }
}

export function validLesson(raw: unknown): Lesson | null {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
    const url = (raw as { url?: unknown }).url;
    if (typeof url !== 'string' || !sameBoxPath(url)) return null;
    if (JSON.stringify(raw).length > MAX_LESSON_BYTES) return null;
    return raw as Lesson;
}

// A mark, with the block system of www/anchor.js: b is a block index, t its print (so a student
// with another version of the page does not show the mark at a wrong place).
//   block:  the whole block; cell: one cell of a table row block.
//   phrase: from character s of block b to character e (not included) of block eb.
//   box:    a rectangle in frame CSS px (the frame has the same width on every phone, follow.js):
//           x and w across the page; the top edge y px below the top of block b, the bottom edge
//           ey px below the top of block eb. Each edge follows its own block when the page flows
//           again (another text size), so a box around a picture stays on the picture. o: 1 for
//           an oval in the same rectangle.
export type Mark =
    | { k: 'b'; b: number; t: string; cell?: number }
    | { k: 'p'; b: number; t: string; s: number; eb: number; et: string; e: number }
    | { k: 'r'; b: number; t: string; y: number; eb: number; et: string; ey: number; x: number; w: number; o?: 1 };

// The box gives each mark an id: the teacher removes a mark by its id.
export type StoredMark = Mark & { id: number };

function index(raw: unknown): number | null {
    return typeof raw === 'number' && Number.isInteger(raw) && raw >= 0 && raw <= MAX_MARK_INDEX ? raw : null;
}

function px(raw: unknown, min: number, max = MAX_MARK_PX): number | null {
    return typeof raw === 'number' && Number.isInteger(raw) && raw >= min && raw <= max ? raw : null;
}

function print(raw: unknown): string | null {
    return typeof raw === 'string' && /^[0-9a-z]{1,8}$/.test(raw) ? raw : null;
}

// A clean copy of a mark from the teacher page. Only the known fields go on: the box sends the
// mark to all students. 'long': a phrase over more than MAX_MARK_BLOCKS blocks (the teacher page
// tells the teacher).
export function validMark(raw: unknown): { mark: Mark } | { error: 'invalid' | 'long' } {
    const invalid = { error: 'invalid' as const };
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return invalid;
    const m = raw as Record<string, unknown>;
    const b = index(m.b);
    const t = print(m.t);
    if (b === null || t === null) return invalid;
    if (m.k === 'b') {
        if (m.cell === undefined) return { mark: { k: 'b', b, t } };
        const cell = index(m.cell);
        return cell === null ? invalid : { mark: { k: 'b', b, t, cell } };
    }
    if (m.k === 'p') {
        const s = index(m.s);
        const eb = index(m.eb);
        const et = print(m.et);
        const e = index(m.e);
        if (s === null || eb === null || et === null || e === null) return invalid;
        if (eb < b || (eb === b && e <= s)) return invalid;
        if (eb - b >= MAX_MARK_BLOCKS) return { error: 'long' };
        return { mark: { k: 'p', b, t, s, eb, et, e } };
    }
    if (m.k === 'r') {
        const y = px(m.y, -MAX_MARK_PX);
        const eb = index(m.eb);
        const et = print(m.et);
        const ey = px(m.ey, -MAX_MARK_PX);
        const x = px(m.x, -MAX_MARK_ACROSS, MAX_MARK_ACROSS);
        const w = px(m.w, 1, MAX_MARK_ACROSS);
        if (y === null || eb === null || et === null || ey === null || x === null || w === null) return invalid;
        if (m.o !== undefined && m.o !== 1) return invalid;
        return { mark: m.o === 1 ? { k: 'r', b, t, y, eb, et, ey, x, w, o: 1 } : { k: 'r', b, t, y, eb, et, ey, x, w } };
    }
    return invalid;
}

function sameMark(stored: StoredMark, mark: Mark): boolean {
    const { id: _id, ...fields } = stored;
    return JSON.stringify(fields) === JSON.stringify(mark);
}

// The marks of the lesson page. url: the lesson page (the marks belong to it).
export interface Marks {
    url: string;
    list: StoredMark[];
}

export type MarkRefusal = 'no-class' | 'other-page' | 'full';
// The result of a mark operation: changed is false when the list stays the same.
export type MarkResult = { changed: boolean } | { refused: MarkRefusal };

// One class: its code, its lesson and its students. A new class is a new object, so nothing of
// the old class (lesson, seats, Done marks) goes into the next one.
interface Session {
    code: string;
    lang: string | null;    // the language of the teacher page: the student pages use it too
    lesson: Lesson | null;
    lessonId: number;
    // The marks of the lesson page: a new page clears them (publish).
    marks: StoredMark[];
    markId: number;
    byToken: Map<string, Student>;
}

// A language key as the class pages use it (www/text.js): 2 or 3 lower-case letters.
export function cleanLang(raw: unknown): string | null {
    return typeof raw === 'string' && /^[a-z]{2,3}$/.test(raw) ? raw : null;
}

export type JoinResult =
    | { student: Student }
    | { refused: 'no-class' | 'too-many' | 'full' };

export class ClassRoom {
    private session: Session | null = null;
    // Teacher pages connected to the class channel. The class opens with the first one, and ends
    // with "End class" or CLASS_GAP_MS after the last one left.
    private teachers = 0;
    private closedAt: number | null = null;
    // Join times per client address (the S1 limit). Old times go in each join and in prune().
    private readonly joins = new Map<string, number[]>();

    constructor(
        private readonly maxStudents = MAX_STUDENTS,
        private readonly newToken: () => string = () => crypto.randomBytes(16).toString('hex'),
        private readonly now: () => number = Date.now,
        private readonly newCode: () => string = newClassCode,
    ) {}

    // lang: the language of the teacher page. The last teacher page that opens sets it.
    teacherOpen(lang: unknown = null): void {
        if (this.lapsed()) this.session = null;
        if (!this.session) {
            this.session = {
                code: this.newCode(), lang: null, lesson: null, lessonId: 0, marks: [], markId: 0, byToken: new Map(),
            };
        }
        this.session.lang = cleanLang(lang) ?? this.session.lang;
        this.teachers += 1;
        this.closedAt = null;
    }

    teacherClose(): void {
        this.teachers = Math.max(0, this.teachers - 1);
        if (this.teachers === 0) this.closedAt = this.now();
    }

    // True when the class has had no teacher page for longer than CLASS_GAP_MS: the teacher left
    // with no "End class". The caller ends it (the students must hear it).
    lapsed(): boolean {
        return this.session !== null && this.teachers === 0 && this.closedAt !== null &&
            this.now() - this.closedAt > CLASS_GAP_MS;
    }

    // The class is open from the first teacher page until the end. A teacher page that loads again
    // keeps it open, so the students and the Wi-Fi announcement do not see a gap.
    state(): ClassState {
        return { open: this.session !== null, code: this.session ? this.session.code : null };
    }

    // The language of the class (of its teacher page), or null.
    lang(): string | null { return this.session ? this.session.lang : null; }

    // The class ends: the students, their tokens and the lesson go. The next teacher page that
    // opens starts a new class with a new code.
    end(): void {
        this.session = null;
    }

    // ip: the client address (the join limit). code: the class that the page joined before, if
    // any; a page of an ended class does not go into the next class by itself.
    join(ip: string, code?: unknown): JoinResult {
        const s = this.session;
        if (!s || (code !== undefined && code !== null && code !== s.code)) return { refused: 'no-class' };
        const t = this.now();
        const recent = (this.joins.get(ip) ?? []).filter((at) => t - at < JOIN_WINDOW_MS);
        if (recent.length >= JOIN_LIMIT) {
            this.joins.set(ip, recent);
            return { refused: 'too-many' };
        }
        if (s.byToken.size >= this.maxStudents) this.dropOldestOffline(s);
        const seat = this.freeSeat(s);
        if (s.byToken.size >= this.maxStudents || seat === null) return { refused: 'full' };
        recent.push(t);
        this.joins.set(ip, recent);
        const token = this.newToken();
        const student: Student = { seat, token, streams: 0, done: false, offlineSince: t, lastHeard: t };
        s.byToken.set(token, student);
        return { student };
    }

    student(token: unknown): Student | undefined {
        return typeof token === 'string' && this.session ? this.session.byToken.get(token) : undefined;
    }

    open(student: Student): void {
        student.streams += 1;
        student.offlineSince = null;
        student.lastHeard = this.now();
    }

    // Returns true when the student was quiet: the teacher page must hear that it is back.
    heard(student: Student): boolean {
        const wasQuiet = this.quiet(student);
        student.lastHeard = this.now();
        return wasQuiet;
    }

    // True when the open channels of this student are dead: no word for maxMs.
    silent(student: Student, maxMs = SILENT_MS): boolean {
        return student.streams > 0 && this.now() - student.lastHeard > maxMs;
    }

    quiet(student: Student): boolean { return this.silent(student, QUIET_MS); }

    close(student: Student): void {
        student.streams = Math.max(0, student.streams - 1);
        if (student.streams === 0) student.offlineSince = this.now();
    }

    // Removes the students that are offline for longer than maxMs, and the old join times. A
    // removed token stops working; if the phone comes back, the page joins again. Returns how
    // many students left.
    prune(maxMs = OFFLINE_EXPIRE_MS): number {
        const t = this.now();
        this.joins.forEach((times, ip) => {
            const recent = times.filter((at) => t - at < JOIN_WINDOW_MS);
            if (recent.length) this.joins.set(ip, recent); else this.joins.delete(ip);
        });
        if (!this.session) return 0;
        let gone = 0;
        for (const [token, s] of this.session.byToken) {
            if (s.offlineSince !== null && s.offlineSince <= t - maxMs) {
                this.session.byToken.delete(token);
                gone += 1;
            }
        }
        return gone;
    }

    setDone(student: Student, done: boolean): void { student.done = done; }

    // A new page clears the Done marks (Done means done with this page) and the marks of the old
    // page. null: no class.
    publish(lesson: Lesson): { id: number; lesson: Lesson; newPage: boolean } | null {
        const s = this.session;
        if (!s) return null;
        const newPage = !s.lesson || s.lesson.url !== lesson.url;
        if (newPage) {
            s.byToken.forEach((st) => { st.done = false; });
            s.marks = [];
        }
        s.lesson = lesson;
        s.lessonId += 1;
        return { id: s.lessonId, lesson, newPage };
    }

    // The marks of the lesson page, or null (no class, or no lesson yet).
    marks(): Marks | null {
        const s = this.session;
        return s && s.lesson ? { url: s.lesson.url, list: s.marks } : null;
    }

    // url: the page that the teacher marked. A mark for another page (a new page went out while the
    // teacher marked) is refused.
    addMark(url: string, mark: Mark): MarkResult {
        const s = this.lessonPage(url);
        if ('refused' in s) return s;
        // The same mark twice (two fast taps) stays one mark.
        if (s.marks.some((m) => sameMark(m, mark))) return { changed: false };
        if (s.marks.length >= MAX_MARKS) return { refused: 'full' };
        s.markId += 1;
        s.marks = [...s.marks, { ...mark, id: s.markId }];
        return { changed: true };
    }

    // The teacher moved a mark: mark is its new place. It keeps its id. A mark that is gone (an
    // eraser on another page of the teacher) changes nothing.
    moveMark(url: string, id: unknown, mark: Mark): MarkResult {
        const s = this.lessonPage(url);
        if ('refused' in s) return s;
        const i = s.marks.findIndex((m) => m.id === id);
        if (i < 0 || sameMark(s.marks[i], mark)) return { changed: false };
        s.marks = s.marks.map((m, k) => (k === i ? { ...mark, id: m.id } : m));
        return { changed: true };
    }

    removeMark(url: string, id: unknown): MarkResult {
        const s = this.lessonPage(url);
        if ('refused' in s) return s;
        const left = s.marks.filter((m) => m.id !== id);
        const changed = left.length !== s.marks.length;
        s.marks = left;
        return { changed };
    }

    clearMarks(url: string): MarkResult {
        const s = this.lessonPage(url);
        if ('refused' in s) return s;
        const changed = s.marks.length > 0;
        s.marks = [];
        return { changed };
    }

    current(): { id: number; lesson: Lesson } | null {
        const s = this.session;
        return s && s.lesson ? { id: s.lessonId, lesson: s.lesson } : null;
    }

    roster(): RosterEntry[] {
        if (!this.session) return [];
        return [...this.session.byToken.values()]
            .map((s) => ({ seat: s.seat, online: s.streams > 0, done: s.done, quiet: this.quiet(s) }))
            .sort((a, b) => a.seat - b.seat);
    }

    joinClients(): number { return this.joins.size; }

    private lessonPage(url: unknown): Session | { refused: MarkRefusal } {
        const s = this.session;
        if (!s) return { refused: 'no-class' };
        if (!s.lesson || s.lesson.url !== url) return { refused: 'other-page' };
        return s;
    }

    // The lowest free seat, so the numbers stay small. A seat is free again when its student
    // leaves the class.
    private freeSeat(s: Session): number | null {
        const taken = new Set<number>();
        s.byToken.forEach((st) => taken.add(st.seat));
        for (let seat = 1; seat <= MAX_SEATS; seat += 1) if (!taken.has(seat)) return seat;
        return null;
    }

    // A full class: the student that is offline for the longest time, past the grace, leaves.
    private dropOldestOffline(s: Session): void {
        const limit = this.now() - OFFLINE_GRACE_MS;
        let oldest: Student | null = null;
        s.byToken.forEach((st) => {
            if (st.streams === 0 && st.offlineSince !== null && st.offlineSince <= limit &&
                (!oldest || st.offlineSince < (oldest.offlineSince as number))) oldest = st;
        });
        if (oldest) s.byToken.delete((oldest as Student).token);
    }
}
