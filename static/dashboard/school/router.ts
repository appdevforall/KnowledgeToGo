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
import { ClassRoom, MAX_MARK_BLOCKS, MAX_MARKS, Student, validLesson, validMark } from './class';
import { readRole, SchoolRole } from './role';

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
// roleOf: the role of this phone (role.ts); a test gives its own.
export function createSchoolRouter(classroom: ClassRoom, roleOf: () => SchoolRole = readRole): Router {
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

    // The code and the name of the class, for the head bar of the teacher pages.
    function classEvent(): { type: 'class'; code: string | null; name: string | null } {
        const st = classroom.state();
        return { type: 'class', code: st.code, name: st.name };
    }

    // The marks of the lesson page. The box is the one owner of the list: the teacher page draws it
    // from here too. A change goes to all pages of the class. An operation that changed nothing
    // goes back to the teacher pages only: a teacher page that missed a change (a box restart)
    // shows the list of the box again.
    const NO_MARKS = { url: null, list: [] };
    function marksOut(students: boolean): void {
        const marks = classroom.marks() ?? NO_MARKS;
        if (students) studentStreams.forEach((_student, s) => send(s, marks, 'marks'));
        toTeachers({ type: 'marks', ...marks });
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
        const marks = classroom.marks();
        if (marks) send(res, marks, 'marks');                  // and its marks
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

    // Is a class open, its code, name and age. The box is the one owner of all four. The teacher
    // app reads it to announce the class on the Wi-Fi (mDNS); a student app reads it to show the
    // class in its list, to find its class again after a drop, and to stop when the class ended.
    // The name is what the teacher chose (the page says: no student names); no device data
    // (K2G-P5). The mDNS announcement has the code only.
    router.get('/state', (_req: Request, res: Response): void => {
        // A lapsed class is no open class, also before the ping round ends it.
        if (classroom.lapsed()) endClass();
        res.setHeader('Cache-Control', 'no-store');
        res.json(classroom.state());
    });

    // Exit of a class page (teacher or student) after the class ended. The app closes the class
    // screen at this address and does not load it; a browser goes to the library of the box.
    router.get('/exit', (_req: Request, res: Response): void => {
        res.redirect(303, '/');
    });

    router.use('/teacher', localOnly);
    // A student phone opens no class: no teacher page, no start, no live channel (book chapter 08).
    router.use('/teacher', (_req: Request, res: Response, next: NextFunction): void => {
        if (roleOf() === 'student') { res.status(403).json({ error: 'student phone' }); return; }
        next();
    });

    router.post('/teacher/publish', (req: Request, res: Response): void => {
        const lesson = validLesson(req.body);
        if (!lesson) { res.status(400).json({ error: 'invalid lesson' }); return; }
        const sent = classroom.publish(lesson);
        if (!sent) { res.status(409).json({ error: 'no-class' }); return; }
        studentStreams.forEach((_student, s) => send(s, sent.lesson, undefined, sent.id));
        if (sent.newPage) marksOut(true);   // the marks of the old page go
        if (sent.newPage) rosterToTeachers();
        res.status(204).end();
    });

    // The teacher marks a part of the lesson page (M2). url: the page that the teacher marked.
    router.post('/teacher/marks', (req: Request, res: Response): void => {
        const body = (req.body ?? {}) as { op?: unknown; url?: unknown; mark?: unknown; id?: unknown };
        const url = typeof body.url === 'string' ? body.url : '';
        let r;
        if (body.op === 'add' || body.op === 'move') {
            const valid = validMark(body.mark);
            if ('error' in valid) {
                res.status(400).json(valid.error === 'long' ? { error: 'long', max: MAX_MARK_BLOCKS } : { error: 'invalid mark' });
                return;
            }
            r = body.op === 'add' ? classroom.addMark(url, valid.mark) : classroom.moveMark(url, body.id, valid.mark);
        } else if (body.op === 'remove') {
            r = classroom.removeMark(url, body.id);
        } else if (body.op === 'clear') {
            r = classroom.clearMarks(url);
        } else {
            res.status(400).json({ error: 'invalid op' });
            return;
        }
        if ('refused' in r) {
            // The page shows the limits of the box: they live in class.ts only.
            res.status(r.refused === 'full' ? 422 : 409).json(r.refused === 'full' ? { error: 'full', max: MAX_MARKS } : { error: r.refused });
            return;
        }
        marksOut(r.changed);
        res.status(204).end();
    });

    // "Teach a class" in the app: open a class with its name (or null: no name), before the app
    // opens the teacher page. The box cleans the name (cleanName). 409: a class is open already,
    // with its own name; the app opens the teacher page of that class.
    router.post('/teacher/start', (req: Request, res: Response): void => {
        if (classroom.lapsed()) endClass();
        if (classroom.start((req.body as { name?: unknown })?.name) === 'open') {
            res.status(409).json({ error: 'open' });
            return;
        }
        res.status(201).json({ code: classroom.state().code });
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
        // A teacher page that comes back after the class gap starts a new class. The old class
        // ends here, before the ping round sees it, so its students hear "ended" (review M1).
        if (classroom.lapsed()) endClass();
        if (page) classroom.teacherOpen(req.query.lang);
        // A teacher page that loads again (a reload, a new app screen, a theme change) goes back
        // to the lesson of the class, not to its start page.
        const now = classroom.current();
        send(res, { type: 'lesson', lesson: now ? now.lesson : null });
        send(res, { type: 'marks', ...(classroom.marks() ?? NO_MARKS) });
        send(res, { type: 'roster', students: classroom.roster() });
        send(res, classEvent());
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

// An error under /school/ (for example a malformed JSON body from express.json) gets a short
// answer: the default handler of Express sends the stack and the file paths of the box, and
// /school/ is open to the class network (review L1). Mount it after the router, on /school.
export function schoolErrors(err: unknown, _req: Request, res: Response, next: NextFunction): void {
    if (res.headersSent) { next(err); return; }
    const status = (err as { status?: unknown })?.status;
    const client = typeof status === 'number' && status >= 400 && status < 500;
    res.status(client ? status : 500).json({ error: client ? 'bad request' : 'server error' });
}

export const schoolRouter = createSchoolRouter(new ClassRoom());
