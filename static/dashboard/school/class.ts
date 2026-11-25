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

// One class: its code, its lesson and its students. A new class is a new object, so nothing of
// the old class (lesson, seats, Done marks) goes into the next one.
interface Session {
    code: string;
    lang: string | null;    // the language of the teacher page: the student pages use it too
    lesson: Lesson | null;
    lessonId: number;
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
            this.session = { code: this.newCode(), lang: null, lesson: null, lessonId: 0, byToken: new Map() };
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

    // A new page clears the Done marks: Done means done with this page. null: no class.
    publish(lesson: Lesson): { id: number; lesson: Lesson; newPage: boolean } | null {
        const s = this.session;
        if (!s) return null;
        const newPage = !s.lesson || s.lesson.url !== lesson.url;
        if (newPage) s.byToken.forEach((st) => { st.done = false; });
        s.lesson = lesson;
        s.lessonId += 1;
        return { id: s.lessonId, lesson, newPage };
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
