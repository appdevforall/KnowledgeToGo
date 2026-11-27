// K2Go School: a verified class (school-trust-design.md 6.4). The teacher key of a teacher phone is
// in the Android Keystore: it never leaves the phone, so the box cannot sign with it. For each class
// the box makes a class key pair. The private class key stays in this process only. The teacher app
// signs a vouch {school, serial, code, classKey, until} with its teacher key. A student phone sends
// a nonce to /school/hello; the box answers with the certificate, the vouch, and a proof of the
// class key over (nonce, code, box address). The student app checks the chain:
// root -> certificate -> vouch -> proof (ClassCheck.java).
import crypto from 'crypto';
import { checkTeacher, readVouch, RootRecord, signProof } from './trust';

interface Current {
    code: string;
    key: crypto.KeyObject;
    pub: string;        // SPKI DER, base64
    cert?: string;
    vouch?: string;
    until?: number;     // seconds
}

export interface Hello { cert: string; vouch: string; proof: string }

export class ClassVouches {
    private cur: Current | null = null;

    // The class key of the open class (a new key for a new code). null: no class is open.
    keyFor(code: string | null): string | null {
        if (!code) { this.cur = null; return null; }
        if (!this.cur || this.cur.code !== code) {
            const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
            this.cur = { code, key: privateKey, pub: publicKey.export({ format: 'der', type: 'spki' }).toString('base64') };
        }
        return this.cur.pub;
    }

    // The app gives the certificate of this phone and the vouch that it signed. The box checks them
    // as a student phone will, so a wrong vouch shows at once on the teacher phone.
    accept(code: unknown, root: RootRecord | null, cert: unknown, vouch: unknown, now: number): boolean {
        const c = this.cur;
        if (!c || code !== c.code || !root || typeof cert !== 'string' || typeof vouch !== 'string') return false;
        const check = checkTeacher(root, cert, null, now);
        if (check.verdict !== 'verified' || !check.cert) return false;
        const v = readVouch(check.cert, vouch, now);
        if (!v || v.code !== c.code || v.classKey !== c.pub) return false;
        c.cert = cert;
        c.vouch = vouch;
        c.until = v.until;
        return true;
    }

    // Is the open class vouched for (the app asks again only when it is not).
    vouched(code: string | null, now: number): boolean {
        const c = this.cur;
        return !!c && !!code && c.code === code && !!c.vouch && (c.until ?? 0) > now;
    }

    // The answer to a student phone. null: no vouch for this class (it shows as not verified), or a
    // field that is not valid.
    hello(code: string | null, nonce: unknown, host: string | null, now: number): Hello | null {
        const c = this.cur;
        if (!c || !code || c.code !== code || !c.cert || !c.vouch || (c.until ?? 0) <= now) return null;
        if (typeof nonce !== 'string' || !host) return null;
        const proof = signProof(c.key, nonce, code, host);
        return proof ? { cert: c.cert, vouch: c.vouch, proof } : null;
    }
}

// Each hello costs the box one signature. A student phone asks about every 5 s per class; a phone
// that asks much more often waits (no CPU for a loop on the class network).
export const HELLO_WINDOW_MS = 10000;
export const HELLO_PER_WINDOW = 10;
const MAX_ADDRESSES = 500;

export class HelloLimit {
    private by = new Map<string, { since: number; n: number }>();

    allow(address: string, now: number): boolean {
        let e = this.by.get(address);
        if (!e || now - e.since >= HELLO_WINDOW_MS) {
            if (!e && this.by.size >= MAX_ADDRESSES) {
                for (const [a, x] of this.by) if (now - x.since >= HELLO_WINDOW_MS) this.by.delete(a);
                if (this.by.size >= MAX_ADDRESSES) return false;
            }
            e = { since: now, n: 0 };
            this.by.set(address, e);
        }
        e.n++;
        return e.n <= HELLO_PER_WINDOW;
    }
}

// The address of the box as the student phone used it. nginx sets the header from $server_addr and
// $server_port (dash-node-nginx.conf): never the Host header, which the caller writes. An IPv4
// address on an IPv6 socket comes as ::ffff:a.b.c.d.
export function boxAddress(header: string | undefined): string | null {
    if (!header) return null;
    const h = header.trim().replace(/^::ffff:/i, '');
    return /^[\x21-\x7e]{1,100}$/.test(h) ? h : null;
}
