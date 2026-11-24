// K2Go School: the state of the class on the teacher box. Pure: no Express, no I/O.
// The box is the one owner of "who is in the class": a student is online while its live channel
// is open, not while it sends acks (M0 finding: counts from acks or from open tabs disagree).
// The state is in memory: a dash-node restart clears it, and the pages join again.
import crypto from 'crypto';

export interface Student {
    id: string;
    token: string;
    name: string;
    streams: number;    // open live channels of this student (a reload can overlap two)
    done: boolean;
}

export interface RosterEntry {
    id: string;
    name: string;
    online: boolean;
    done: boolean;
}

// What the teacher sends: the page path and the scroll place. Other fields pass through.
export interface Lesson {
    url: string;
    [key: string]: unknown;
}

export const MAX_NAME = 24;
const MAX_LESSON_BYTES = 2048;

export function cleanName(raw: unknown): string {
    const name = String(raw ?? '').replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, MAX_NAME);
    return name || 'Student';
}

// A lesson URL must be a path on the same box: the student frame loads it, and the scroll works
// only for a page of the same origin.
export function validLesson(raw: unknown): Lesson | null {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
    const url = (raw as { url?: unknown }).url;
    if (typeof url !== 'string' || !url.startsWith('/') || url.startsWith('//')) return null;
    if (JSON.stringify(raw).length > MAX_LESSON_BYTES) return null;
    return raw as Lesson;
}

export class ClassRoom {
    private readonly byToken = new Map<string, Student>();
    private lesson: Lesson | null = null;
    private lessonId = 0;

    constructor(
        private readonly maxStudents = 1000,
        private readonly newToken: () => string = () => crypto.randomBytes(16).toString('hex'),
    ) {}

    join(name: unknown): Student | null {
        if (this.byToken.size >= this.maxStudents) this.dropOffline();
        if (this.byToken.size >= this.maxStudents) return null;
        const token = this.newToken();
        const student: Student = { id: token.slice(0, 8), token, name: cleanName(name), streams: 0, done: false };
        this.byToken.set(token, student);
        return student;
    }

    student(token: unknown): Student | undefined {
        return typeof token === 'string' ? this.byToken.get(token) : undefined;
    }

    open(student: Student): void { student.streams += 1; }

    close(student: Student): void { student.streams = Math.max(0, student.streams - 1); }

    setDone(student: Student, done: boolean): void { student.done = done; }

    // A new page clears the Done marks: Done means done with this page.
    publish(lesson: Lesson): { id: number; lesson: Lesson; newPage: boolean } {
        const newPage = !this.lesson || this.lesson.url !== lesson.url;
        if (newPage) this.byToken.forEach((s) => { s.done = false; });
        this.lesson = lesson;
        this.lessonId += 1;
        return { id: this.lessonId, lesson, newPage };
    }

    current(): { id: number; lesson: Lesson } | null {
        return this.lesson ? { id: this.lessonId, lesson: this.lesson } : null;
    }

    roster(): RosterEntry[] {
        return [...this.byToken.values()].map((s) => ({ id: s.id, name: s.name, online: s.streams > 0, done: s.done }));
    }

    // Students that left (no open channel) give their place to new ones.
    private dropOffline(): void {
        for (const [token, s] of this.byToken) {
            if (s.streams === 0) this.byToken.delete(token);
        }
    }
}
