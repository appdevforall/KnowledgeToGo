// K2Go School: the class pages and the live channel (server-sent events), on the teacher box.
// nginx exposes /school/ to the class network and /school/teacher/ to localhost only
// (dash-node-nginx.conf). /k2go-api/ stays localhost only (D12). The browser of a student needs
// nothing more than EventSource: SSE lives only here, on the server.
//
// No name and no token in a URL (K2G-P5, review B1): the student token goes in the body of a POST
// and in a cookie for the live channel (EventSource cannot send a header or a body). nginx keeps
// no access log for /school/ (dash-node-nginx.conf).
import express, { NextFunction, Request, Response, Router } from 'express';
import path from 'path';
import { ClassRoom, Student, validLesson } from './class';

const PING_MS = 10000;     // the watchdog of the pages counts on it (www/follow.js)
// The teacher page shows a student as "bad" when the box marks it quiet (QUIET_MS). This round
// sends the roster with the quiet marks, so the mark is late by at most this time.
const STATUS_MS = 2000;
// Source pages, next to this folder in the dashboard tree (tsc does not copy them to dist).
const WWW = path.join(__dirname, '..', '..', 'school', 'www');
// The cookie of the live channel. The student page sets it before it opens the channel.
export const TOKEN_COOKIE = 'k2go-school';

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

export function cookieToken(header: string | undefined): string | undefined {
    if (!header) return undefined;
    for (const part of header.split(';')) {
        const eq = part.indexOf('=');
        if (eq > 0 && part.slice(0, eq).trim() === TOKEN_COOKIE) return part.slice(eq + 1).trim();
    }
    return undefined;
}

// nginx sets X-Real-IP; dash-node itself listens on loopback only.
function clientIp(req: Request): string {
    return req.get('x-real-ip') ?? req.socket.remoteAddress ?? '';
}

// A second guard behind the nginx allow list.
function localOnly(req: Request, res: Response, next: NextFunction): void {
    if (['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(clientIp(req))) { next(); return; }
    res.status(404).end();
}

// One router per class room. The box has one (schoolRouter); a test makes its own.
export function createSchoolRouter(classroom: ClassRoom): Router {
    const studentStreams = new Map<Response, Student>();
    const teacherStreams = new Set<Response>();

    function toTeachers(data: unknown): void {
        teacherStreams.forEach((res) => send(res, data));
    }

    // Each join, open and close changes the roster. Send it at most every 300 ms: with one send
    // per change, N students joining make N rosters of up to N entries.
    let rosterTimer: NodeJS.Timeout | null = null;
    function rosterToTeachers(): void {
        if (rosterTimer) return;
        rosterTimer = setTimeout(() => {
            rosterTimer = null;
            toTeachers({ type: 'roster', students: classroom.roster() });
        }, 300);
    }

    // The class ends ("End class", or no teacher page for too long): the students get "ended"
    // and lose their channel, the box forgets the class. The teacher app stops the Wi-Fi
    // announcement at its next read of /state.
    function endClass(): void {
        classroom.end();
        studentStreams.forEach((_student, s) => { s.write('event: ended\ndata: 1\n\n'); s.end(); });
        toTeachers({ type: 'ended' });
        rosterToTeachers();
    }

    // An event with no data line is not delivered to the page (EventSource spec), so the ping
    // carries one: the student watchdog counts on it.
    // Each ping round also closes the dead channels (the close handler marks the student offline),
    // removes the students that are offline for too long, and ends a class with no teacher.
    setInterval(() => {
        studentStreams.forEach((student, res) => {
            if (classroom.silent(student)) res.destroy();
            else send(res, 1, 'ping');
        });
        teacherStreams.forEach((res) => send(res, 1, 'ping'));
        if (classroom.lapsed()) endClass();
        else if (classroom.prune() > 0) rosterToTeachers();
    }, PING_MS).unref();

    // The quiet marks change with time, not with an event.
    setInterval(() => {
        if (teacherStreams.size && studentStreams.size) rosterToTeachers();
    }, STATUS_MS).unref();

    function studentOf(req: Request, res: Response): Student | undefined {
        const token = req.method === 'GET' ? cookieToken(req.get('cookie')) : (req.body as { token?: unknown })?.token;
        const student = classroom.student(token);
        if (!student) res.status(401).json({ error: 'join first' });
        return student;
    }

    const router = Router();

    router.post('/join', (req: Request, res: Response): void => {
        const r = classroom.join(clientIp(req), (req.body as { code?: unknown })?.code);
        if ('refused' in r) {
            const status = r.refused === 'no-class' ? 409 : r.refused === 'too-many' ? 429 : 503;
            res.status(status).json({ error: r.refused });
            return;
        }
        const code = classroom.state().code;
        res.status(201).json({ token: r.student.token, seat: r.student.seat, code, lang: classroom.lang() });
        rosterToTeachers();
    });

    router.get('/live', (req: Request, res: Response): void => {
        const student = studentOf(req, res);
        if (!student) return;
        openStream(res);
        const now = classroom.current();
        if (now) send(res, now.lesson, undefined, now.id);   // a late student gets the page at once
        // After the lesson: a page that comes back with its token gets its seat, its class and its
        // Done mark of this page (the box owns them; the page keeps the token and the class code).
        send(res, { done: student.done, seat: student.seat, code: classroom.state().code, lang: classroom.lang() }, 'self');
        // One channel per student: a new one (reload, reconnect) replaces the old one, which can
        // be dead with no error (the heartbeat of the new one would keep it open). A live old one
        // is another tab of the same phone with the same token: tell it, so it does not open again.
        studentStreams.forEach((s, old) => {
            if (s !== student) return;
            const kill = (): void => { old.destroy(); };
            old.write('event: replaced\ndata: 1\n\n', kill);
            setTimeout(kill, 1000).unref();   // a dead socket may never flush the write
        });
        classroom.open(student);
        studentStreams.set(res, student);
        rosterToTeachers();
        res.on('close', () => {
            studentStreams.delete(res);
            classroom.close(student);
            rosterToTeachers();
        });
    });

    router.post('/ack', (req: Request, res: Response): void => {
        const student = studentOf(req, res);
        if (!student) return;
        // An ack with no seq only says "still here" (every 5 s).
        const body = req.body as { seq?: unknown; done?: unknown; match?: unknown };
        if (classroom.heard(student)) rosterToTeachers();   // it was quiet: now it is back
        if (body.seq !== undefined) {
            const done = body.done === true;
            const changed = student.done !== done;
            classroom.setDone(student, done);
            toTeachers({ type: 'ack', seat: student.seat, seq: Number(body.seq), match: body.match !== false });
            if (changed) rosterToTeachers();
        }
        res.status(204).end();
    });

    // Is a class open, and its code. The box is the one owner of both. The teacher app reads it to
    // announce the class on the Wi-Fi (mDNS); a student app reads it to check a class it found, to
    // find its class again after a drop, and to stop when the class ended. Only the open flag and
    // the random code: no name, no device data (K2G-P5). The same two values are in the mDNS
    // announcement.
    router.get('/state', (_req: Request, res: Response): void => {
        res.setHeader('Cache-Control', 'no-store');
        res.json(classroom.state());
    });

    router.use('/teacher', localOnly);

    router.post('/teacher/publish', (req: Request, res: Response): void => {
        const lesson = validLesson(req.body);
        if (!lesson) { res.status(400).json({ error: 'invalid lesson' }); return; }
        const sent = classroom.publish(lesson);
        if (!sent) { res.status(409).json({ error: 'no-class' }); return; }
        studentStreams.forEach((_student, s) => send(s, sent.lesson, undefined, sent.id));
        if (sent.newPage) rosterToTeachers();
        res.status(204).end();
    });

    router.post('/teacher/end', (_req: Request, res: Response): void => {
        endClass();
        res.status(204).end();
    });

    // Only the teacher page (?role=page) opens the class. Other channels, for example a recorder of
    // a test, get the same events and do not keep a class open. ?lang=: the language of the teacher
    // page; the student pages of the class use it (join answer and "self").
    router.get('/teacher/live', (req: Request, res: Response): void => {
        const page = req.query.role === 'page';
        openStream(res);
        if (page) classroom.teacherOpen(req.query.lang);
        // A teacher page that loads again (a reload, a new app screen, a theme change) goes back
        // to the lesson of the class, not to its start page.
        const now = classroom.current();
        send(res, { type: 'lesson', lesson: now ? now.lesson : null });
        send(res, { type: 'roster', students: classroom.roster() });
        send(res, { type: 'class', code: classroom.state().code });
        teacherStreams.add(res);
        res.on('close', () => {
            teacherStreams.delete(res);
            if (page) classroom.teacherClose();
        });
    });

    // The pages change with each deploy: no cache. The class font does not change (a new font gets
    // a new file name), and 30 phones load it: cache it.
    router.use(express.static(WWW, {
        setHeaders: (res, file) => {
            res.setHeader('Cache-Control', file.endsWith('.ttf') ? 'public, max-age=604800' : 'no-store');
        },
    }));

    return router;
}

export const schoolRouter = createSchoolRouter(new ClassRoom());
