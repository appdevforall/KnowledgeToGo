// K2Go School: the school trust (design: k2go-school notes, school-trust-design.md).
// Device zero keeps the school root key. It signs one certificate per teacher phone and the list
// of removed teachers. Every enrolled phone pins the root record. A phone with a root and a valid
// certificate is a teacher; with a root and no valid certificate, a student; with no root, it has
// no role (as before roles). TrustCheck.java in the app has the same rules: the shared test vectors
// (trust-vectors.json) keep the two equal.
//
// A signed token is "<base64url body>.<base64url signature>". The signature is ECDSA P-256 with
// SHA-256 (DER) over the body bytes as they are: no JSON canonical form is necessary. The body has a
// "kind", so a token of one kind is never read as another kind.
import crypto from 'crypto';

export const TRUST_V = 1;
export const MAX_TOKEN = 4096;
// A removed list must fit in one token: about 25 chars per serial in base64.
export const MAX_SERIALS = 120;
// Domain separation for the class proof: the bytes are never a JSON body.
const PROOF_TAG = 'k2go-class-proof';

export interface RootRecord { kind: 'root'; v: number; school: string; name: string; rootKey: string; until: number }
export interface TeacherCert { kind: 'teacher'; v: number; school: string; key: string; label: string; serial: string; from: number; until: number }
export interface RemovedList { kind: 'removed'; v: number; school: string; issued: number; serials: string[] }

export type Verdict = 'verified' | 'malformed' | 'other-school' | 'bad-signature' | 'not-yet' | 'expired' | 'removed';
export type DerivedRole = 'none' | 'teacher' | 'student';

const B64URL = /^[A-Za-z0-9_-]+$/;
const SERIAL = /^[0-9a-f]{16}$/;
const SCHOOL = /^[0-9a-f]{16}$/;

function b64url(buf: Buffer): string {
    return buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function unb64url(s: string): Buffer | null {
    if (!B64URL.test(s)) return null;
    return Buffer.from(s.replace(/-/g, '+').replace(/_/g, '/'), 'base64');
}

// The school id: the first 16 hex chars of SHA-256 of the root public key (SPKI DER).
export function schoolId(rootKeySpki: Buffer): string {
    return crypto.createHash('sha256').update(rootKeySpki).digest('hex').slice(0, 16);
}

export function sign(privateKey: crypto.KeyObject, body: object): string {
    const bytes = Buffer.from(JSON.stringify(body), 'utf8');
    return `${b64url(bytes)}.${b64url(crypto.sign('sha256', bytes, privateKey))}`;
}

function verifyBytes(keyB64: string, data: Buffer, sig: Buffer): boolean {
    try {
        const key = crypto.createPublicKey({ key: Buffer.from(keyB64, 'base64'), format: 'der', type: 'spki' });
        const ec = key.asymmetricKeyDetails;
        if (key.asymmetricKeyType !== 'ec' || ec?.namedCurve !== 'prime256v1') return false;
        return crypto.verify('sha256', data, key, sig);
    } catch {
        return false;
    }
}

interface Token { body: Buffer; sig: Buffer; json: Record<string, unknown> }

function decode(token: unknown, kind: string): Token | null {
    if (typeof token !== 'string' || token.length > MAX_TOKEN) return null;
    const parts = token.split('.');
    if (parts.length !== 2) return null;
    const body = unb64url(parts[0]);
    const sig = unb64url(parts[1]);
    if (!body || !sig) return null;
    let json: unknown;
    try { json = JSON.parse(body.toString('utf8')); } catch { return null; }
    if (!json || typeof json !== 'object' || Array.isArray(json)) return null;
    const o = json as Record<string, unknown>;
    if (o.kind !== kind || o.v !== TRUST_V) return null;
    return { body, sig, json: o };
}

const isText = (x: unknown, max: number): x is string => typeof x === 'string' && x.length <= max;
const isTime = (x: unknown): x is number => Number.isSafeInteger(x) && (x as number) >= 0;

// The root record: signed by its own key, and the school id must come from that key. The ring PIN
// (phase 4) guards which root a phone pins; this check guards the record itself.
export function readRoot(token: unknown): RootRecord | null {
    const t = decode(token, 'root');
    if (!t) return null;
    const o = t.json;
    if (!isText(o.school, 16) || !SCHOOL.test(o.school) || !isText(o.name, 80) || !isText(o.rootKey, 200)
        || !isTime(o.until)) return null;
    const spki = Buffer.from(o.rootKey, 'base64');
    if (schoolId(spki) !== o.school || !verifyBytes(o.rootKey, t.body, t.sig)) return null;
    return { kind: 'root', v: TRUST_V, school: o.school, name: o.name, rootKey: o.rootKey, until: o.until };
}

// The list of removed teachers, signed by the root of this school. null: not a valid list.
export function readRemoved(root: RootRecord, token: unknown): RemovedList | null {
    const t = decode(token, 'removed');
    if (!t) return null;
    const o = t.json;
    if (o.school !== root.school || !isTime(o.issued) || !Array.isArray(o.serials)
        || o.serials.length > MAX_SERIALS || !o.serials.every((s) => typeof s === 'string' && SERIAL.test(s))) return null;
    if (!verifyBytes(root.rootKey, t.body, t.sig)) return null;
    return { kind: 'removed', v: TRUST_V, school: root.school, issued: o.issued, serials: o.serials as string[] };
}

// Lists are merged, never replaced: a device zero that comes back from an old backup cannot bring a
// removed teacher back. issued: the newest of the two.
export function mergeRemoved(a: RemovedList | null, b: RemovedList | null): RemovedList | null {
    if (!a) return b;
    if (!b || a.school !== b.school) return a;
    const serials = Array.from(new Set([...a.serials, ...b.serials])).sort();
    return { kind: 'removed', v: TRUST_V, school: a.school, issued: Math.max(a.issued, b.issued), serials };
}

export interface TeacherCheck { verdict: Verdict; cert?: TeacherCert }

// A teacher certificate against the pinned root, the removed list and the time (seconds).
export function checkTeacher(root: RootRecord | null, token: unknown, removed: RemovedList | null, now: number): TeacherCheck {
    const t = decode(token, 'teacher');
    if (!root || !t) return { verdict: 'malformed' };
    const o = t.json;
    if (!isText(o.school, 16) || !isText(o.key, 200) || !isText(o.label, 40) || !isText(o.serial, 16)
        || !SERIAL.test(o.serial) || !isTime(o.from) || !isTime(o.until)) return { verdict: 'malformed' };
    if (o.school !== root.school) return { verdict: 'other-school' };
    if (!verifyBytes(root.rootKey, t.body, t.sig)) return { verdict: 'bad-signature' };
    const cert: TeacherCert = {
        kind: 'teacher', v: TRUST_V, school: o.school, key: o.key, label: o.label, serial: o.serial, from: o.from, until: o.until,
    };
    if (now < cert.from) return { verdict: 'not-yet', cert };
    // A certificate ends with the school year of its root at the latest.
    if (now >= Math.min(cert.until, root.until)) return { verdict: 'expired', cert };
    if (removed && removed.serials.includes(cert.serial)) return { verdict: 'removed', cert };
    return { verdict: 'verified', cert };
}

// The role of a phone: no root -> no role; a verified certificate -> teacher; else student. A root
// past its "until" still gives a student (restrictive) until the phone pins a root again.
export function deriveRole(root: RootRecord | null, cert: TeacherCheck | null): DerivedRole {
    if (!root) return 'none';
    return cert?.verdict === 'verified' ? 'teacher' : 'student';
}

// Each field of the proof: printable ASCII with no space, so no field can carry a newline and
// pass for two fields.
const PROOF_FIELD = /^[\x21-\x7e]{1,100}$/;

// host: the address of the box (IP:port) as the student phone used it. The box takes its own
// address from nginx ($server_addr), never from the Host header. So a false box that sends the
// nonce on to a real teacher box gets a proof for the address of the real box, and the student
// phone refuses it (relay attack).
function proofBytes(nonce: unknown, code: unknown, host: unknown): Buffer | null {
    const fields = [nonce, code, host];
    if (!fields.every((f) => typeof f === 'string' && PROOF_FIELD.test(f))) return null;
    return Buffer.from([PROOF_TAG, ...fields].join('\n'), 'utf8');
}

// The teacher box proves that it holds the private key of its certificate, for this nonce, this
// class and this address. null: a field is not valid.
export function signProof(teacherKey: crypto.KeyObject, nonce: string, code: string, host: string): string | null {
    const data = proofBytes(nonce, code, host);
    return data ? b64url(crypto.sign('sha256', data, teacherKey)) : null;
}

export function checkProof(cert: TeacherCert, nonce: string, code: string, host: string, proof: unknown): boolean {
    if (typeof proof !== 'string' || proof.length > 200) return false;
    const data = proofBytes(nonce, code, host);
    const sig = unb64url(proof);
    return !!data && !!sig && verifyBytes(cert.key, data, sig);
}
