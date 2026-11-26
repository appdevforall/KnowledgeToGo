// K2Go School: the setup ring (design: school-trust-design.md 6.2, 6.3, 8). A phone of the school opens
// a ring for some minutes with a 4-digit PIN on its screen and on the board. A teacher ring (on device
// zero) takes the public keys of teacher phones; device zero approves each one and the app signs its
// certificate. A student ring gives the root record of the school to each phone that knows the PIN.
// The box only carries the messages: it keeps no private key, and the app signs. RingProof.java in the
// app makes and checks the same HMAC values.
import crypto from 'crypto';

export type RingKind = 'teacher' | 'student';

export const RING_MS = 10 * 60 * 1000;
export const MAX_WRONG_PER_ADDRESS = 5;
export const MAX_WRONG = 30;        // then the ring closes: a PIN of 4 digits must not be guessed
export const MAX_REQUESTS = 60;
const PIN = /^[0-9]{4}$/;
const NONCE = /^[A-Za-z0-9_-]{16,64}$/;
const ID = /^[0-9a-f]{16}$/;

// HMAC-SHA256 with the PIN as the key, hex. Each kind of message has its own tag.
export function mac(pin: string, ...fields: string[]): string {
    return crypto.createHmac('sha256', Buffer.from(pin, 'utf8')).update(fields.join('\n'), 'utf8').digest('hex');
}

function same(a: string, b: string): boolean {
    const x = Buffer.from(a, 'utf8');
    const y = Buffer.from(b, 'utf8');
    return x.length === y.length && crypto.timingSafeEqual(x, y);
}

interface Request {
    id: string;
    nonce: string;
    key: string;          // the public key of the teacher phone (SPKI DER, base64)
    at: number;
    cert?: string;
    refused?: boolean;
}

export interface RingView {
    open: boolean;
    kind: RingKind | null;
    closesIn: number;                 // ms
    closedBy: 'time' | 'guesses' | 'host' | null;
    requests: { id: string; key: string; at: number; approved: boolean; refused: boolean }[];
}

export type JoinAnswer =
    | { status: 200; body: { root: string; mac: string } }
    | { status: 202; body: { id: string } }
    | { status: 400 | 403 | 404 | 409 | 429; body: { error: string } };

// One ring per box. Time comes from the caller (tests).
export class Ring {
    private kind: RingKind | null = null;
    private pin = '';
    private rootToken = '';
    private until = 0;
    private closedBy: RingView['closedBy'] = null;
    private wrong = 0;
    private wrongBy = new Map<string, number>();
    private requests = new Map<string, Request>();

    open(kind: unknown, pin: unknown, rootToken: unknown, now: number): boolean {
        if ((kind !== 'teacher' && kind !== 'student') || typeof pin !== 'string' || !PIN.test(pin)
            || typeof rootToken !== 'string' || rootToken.length === 0 || rootToken.length > 4096) return false;
        this.kind = kind;
        this.pin = pin;
        this.rootToken = rootToken;
        this.until = now + RING_MS;
        this.closedBy = null;
        this.wrong = 0;
        this.wrongBy.clear();
        this.requests.clear();
        return true;
    }

    close(by: 'time' | 'guesses' | 'host'): void {
        if (!this.kind) return;
        this.kind = null;
        this.pin = '';
        this.closedBy = by;
        this.requests.clear();
        this.wrongBy.clear();
    }

    private live(now: number): boolean {
        if (this.kind && now >= this.until) this.close('time');
        return this.kind !== null;
    }

    view(now: number): RingView {
        const open = this.live(now);
        return {
            open,
            kind: this.kind,
            closesIn: open ? this.until - now : 0,
            closedBy: open ? null : this.closedBy,
            requests: Array.from(this.requests.values()).map((r) => ({
                id: r.id, key: r.key, at: r.at, approved: !!r.cert, refused: !!r.refused,
            })),
        };
    }

    // A wrong PIN costs a try for the address and for the ring.
    private wrongPin(address: string): JoinAnswer {
        this.wrong++;
        const n = (this.wrongBy.get(address) ?? 0) + 1;
        this.wrongBy.set(address, n);
        if (this.wrong >= MAX_WRONG) this.close('guesses');
        return { status: 403, body: { error: 'pin' } };
    }

    join(address: string, body: unknown, now: number): JoinAnswer {
        if (!this.live(now)) return { status: 404, body: { error: 'no-ring' } };
        if ((this.wrongBy.get(address) ?? 0) >= MAX_WRONG_PER_ADDRESS) return { status: 429, body: { error: 'tries' } };
        const b = (body ?? {}) as { nonce?: unknown; key?: unknown; proof?: unknown };
        if (typeof b.nonce !== 'string' || !NONCE.test(b.nonce) || typeof b.proof !== 'string') {
            return { status: 400, body: { error: 'invalid' } };
        }
        if (this.kind === 'student') {
            if (!same(b.proof, mac(this.pin, 'k2go-ring-join', b.nonce))) return this.wrongPin(address);
            return { status: 200, body: { root: this.rootToken, mac: mac(this.pin, 'k2go-ring-root', b.nonce, this.rootToken) } };
        }
        if (typeof b.key !== 'string' || b.key.length === 0 || b.key.length > 200) return { status: 400, body: { error: 'invalid' } };
        if (!same(b.proof, mac(this.pin, 'k2go-ring-join', b.nonce, b.key))) return this.wrongPin(address);
        // The same key again (a new join after Cancel or an app restart): the same request, and the
        // answer goes to the newest join, which proved the PIN with its own nonce.
        for (const r of this.requests.values()) {
            if (r.key === b.key) {
                r.nonce = b.nonce;
                return { status: 202, body: { id: r.id } };
            }
        }
        if (this.requests.size >= MAX_REQUESTS) return { status: 409, body: { error: 'full' } };
        const id = crypto.randomBytes(8).toString('hex');
        this.requests.set(id, { id, nonce: b.nonce, key: b.key, at: now });
        return { status: 202, body: { id } };
    }

    // The teacher phone asks for its answer. The nonce of its join proves that it is the same phone.
    result(body: unknown, now: number): { status: number; body: unknown } {
        if (!this.live(now)) return { status: 404, body: { error: 'no-ring' } };
        const b = (body ?? {}) as { id?: unknown; nonce?: unknown };
        const r = typeof b.id === 'string' ? this.requests.get(b.id) : undefined;
        if (!r || r.nonce !== b.nonce) return { status: 404, body: { error: 'unknown' } };
        if (r.refused) return { status: 403, body: { error: 'refused' } };
        if (!r.cert) return { status: 202, body: { waiting: true } };
        return {
            status: 200,
            body: { root: this.rootToken, cert: r.cert, mac: mac(this.pin, 'k2go-ring-cert', r.nonce, this.rootToken, r.cert) },
        };
    }

    // Device zero answers one request: a certificate that the app signed, or a refusal (cert null).
    answer(id: unknown, cert: unknown, now: number): boolean {
        if (!this.live(now) || this.kind !== 'teacher' || typeof id !== 'string' || !ID.test(id)) return false;
        const r = this.requests.get(id);
        if (!r) return false;
        if (cert === null) {
            r.refused = true;
            return true;
        }
        if (typeof cert !== 'string' || cert.length === 0 || cert.length > 4096) return false;
        r.cert = cert;
        return true;
    }
}
