// K2Go School: the class pages and the live channel (server-sent events), on the teacher box.
// nginx exposes /school/ to the class network and /school/teacher/ to localhost only
// (dash-node-nginx.conf). /k2go-api/ stays localhost only (D12). The browser of a student needs
// nothing more than EventSource: SSE lives only here, on the server.
import express, { NextFunction, Request, Response, Router } from 'express';
import path from 'path';
import { ClassRoom, Student, validLesson } from './class';

const PING_MS = 15000;
// Source pages, next to this folder in the dashboard tree (tsc does not copy them to dist).
const WWW = path.join(__dirname, '..', '..', 'school', 'www');

export const classroom = new ClassRoom();
const studentStreams = new Set<Response>();
const teacherStreams = new Set<Response>();

function openStream(res: Response): void {
    res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-store',
        'X-Accel-Buffering': 'no',   // nginx must pass each event at once
    });
    res.write('retry: 3000\n\n');
}

function send(res: Response, data: unknown, event?: string, id?: number): void {
    let out = '';
    if (event) out += `event: ${event}\n`;
    if (id !== undefined) out += `id: ${id}\n`;
    res.write(`${out}data: ${JSON.stringify(data)}\n\n`);
}

function toTeachers(data: unknown): void {
    teacherStreams.forEach((res) => send(res, data));
}

// Each join, open and close changes the roster. Send it at most every 300 ms: with one send per
// change, N students joining make N rosters of up to N entries.
let rosterTimer: NodeJS.Timeout | null = null;
function rosterToTeachers(): void {
    if (rosterTimer) return;
    rosterTimer = setTimeout(() => {
        rosterTimer = null;
        toTeachers({ type: 'roster', students: classroom.roster() });
    }, 300);
}

// An event with no data line is not delivered to the page (EventSource spec), so the ping
// carries one: the student watchdog counts on it.
setInterval(() => {
    studentStreams.forEach((res) => send(res, 1, 'ping'));
    teacherStreams.forEach((res) => send(res, 1, 'ping'));
}, PING_MS).unref();

// nginx sets X-Real-IP; dash-node itself listens on loopback only. A second guard behind the
// nginx allow list.
function localOnly(req: Request, res: Response, next: NextFunction): void {
    const ip = req.get('x-real-ip') ?? req.socket.remoteAddress ?? '';
    if (['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(ip)) { next(); return; }
    res.status(404).end();
}

function studentOf(req: Request, res: Response): Student | undefined {
    const token = req.method === 'GET' ? req.query.token : (req.body as { token?: unknown })?.token;
    const student = classroom.student(token);
    if (!student) res.status(401).json({ error: 'join first' });
    return student;
}

export const schoolRouter = Router();

schoolRouter.post('/join', (req: Request, res: Response): void => {
    const student = classroom.join((req.body as { name?: unknown })?.name);
    if (!student) { res.status(503).json({ error: 'class full' }); return; }
    res.status(201).json({ id: student.id, token: student.token, name: student.name });
    rosterToTeachers();
});

schoolRouter.get('/live', (req: Request, res: Response): void => {
    const student = studentOf(req, res);
    if (!student) return;
    openStream(res);
    const now = classroom.current();
    if (now) send(res, now.lesson, undefined, now.id);   // a late student gets the page at once
    classroom.open(student);
    studentStreams.add(res);
    rosterToTeachers();
    res.on('close', () => {
        studentStreams.delete(res);
        classroom.close(student);
        rosterToTeachers();
    });
});

schoolRouter.post('/ack', (req: Request, res: Response): void => {
    const student = studentOf(req, res);
    if (!student) return;
    const body = req.body as { seq?: unknown; done?: unknown; dy?: unknown; h?: unknown };
    const done = body.done === true;
    const changed = student.done !== done;
    classroom.setDone(student, done);
    toTeachers({ type: 'ack', id: student.id, seq: Number(body.seq), dy: Number(body.dy), h: Number(body.h) });
    if (changed) rosterToTeachers();
    res.status(204).end();
});

schoolRouter.use('/teacher', localOnly);

schoolRouter.post('/teacher/publish', (req: Request, res: Response): void => {
    const lesson = validLesson(req.body);
    if (!lesson) { res.status(400).json({ error: 'invalid lesson' }); return; }
    const sent = classroom.publish(lesson);
    studentStreams.forEach((s) => send(s, sent.lesson, undefined, sent.id));
    if (sent.newPage) rosterToTeachers();
    res.status(204).end();
});

schoolRouter.get('/teacher/live', (req: Request, res: Response): void => {
    openStream(res);
    send(res, { type: 'roster', students: classroom.roster() });
    teacherStreams.add(res);
    res.on('close', () => { teacherStreams.delete(res); });
});

schoolRouter.use(express.static(WWW, {
    setHeaders: (res) => { res.setHeader('Cache-Control', 'no-store'); },
}));
