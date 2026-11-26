/// <reference types="node" />
// Makes trust-vectors.json: the shared test vectors of trust.ts (box) and TrustCheck.java (app).
// Run one time when the format changes: node --require ts-node/register school/trust-vectors.gen.ts
// The keys in the file are TEST keys only. The "javaSigned" entry is added by hand from a Java run
// (see TrustCheckTest), so the box also checks a signature that the app made.
import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { schoolId, sign, signProof, TRUST_V } from './trust';

const NOW = 1798761600;   // 2027-01-01T00:00:00Z
const YEAR_END = 1814400000;   // 2027-07-01T00:00:00Z

function keys(): { priv: crypto.KeyObject; spki: string; pkcs8: string } {
    const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    return {
        priv: privateKey,
        spki: publicKey.export({ format: 'der', type: 'spki' }).toString('base64'),
        pkcs8: privateKey.export({ format: 'der', type: 'pkcs8' }).toString('base64'),
    };
}

const root = keys();
const other = keys();
const teacher = keys();
const school = schoolId(Buffer.from(root.spki, 'base64'));
const otherSchool = schoolId(Buffer.from(other.spki, 'base64'));

const rootBody = { kind: 'root', v: TRUST_V, school, name: 'Test school', rootKey: root.spki, until: YEAR_END };
const cert = (o: object, k = root.priv): string => sign(k, {
    kind: 'teacher', v: TRUST_V, school, key: teacher.spki, label: 'Room 3B', serial: 'a1a1a1a1a1a1a1a1',
    from: NOW - 86400, until: YEAR_END, ...o,
});
const valid = cert({});
const [vBody, vSig] = valid.split('.');
const tamperedBody = Buffer.from(JSON.stringify({ ...JSON.parse(Buffer.from(vBody, 'base64').toString()), label: 'Room 9Z' }))
    .toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

const out = {
    note: 'TEST KEYS ONLY. Made by trust-vectors.gen.ts. Used by school/trust.test.ts and TrustCheckTest.java.',
    now: NOW,
    keys: { rootPkcs8: root.pkcs8, rootSpki: root.spki, teacherPkcs8: teacher.pkcs8, teacherSpki: teacher.spki },
    school,
    root: sign(root.priv, rootBody),
    rootOtherSchool: sign(other.priv, { ...rootBody, school: otherSchool, rootKey: other.spki }),
    rootWrongSchoolId: sign(root.priv, { ...rootBody, school: otherSchool }),
    rootSignedByOther: sign(other.priv, rootBody),
    teacher: {
        verified: valid,
        expired: cert({ from: NOW - 2 * 86400, until: NOW - 1 }),
        notYet: cert({ from: NOW + 86400 }),
        otherSchool: cert({ school: otherSchool }, other.priv),
        signedByOther: cert({}, other.priv),
        tampered: `${tamperedBody}.${vSig}`,
        removed: cert({ serial: 'b2b2b2b2b2b2b2b2' }),
        wrongKind: sign(root.priv, { kind: 'removed', v: TRUST_V, school, issued: NOW, serials: [] }),
        badVersion: cert({ v: 2 }),
        notAToken: 'not-a-token',
    },
    removed: {
        first: sign(root.priv, { kind: 'removed', v: TRUST_V, school, issued: NOW - 100, serials: ['b2b2b2b2b2b2b2b2'] }),
        second: sign(root.priv, { kind: 'removed', v: TRUST_V, school, issued: NOW - 50, serials: ['c3c3c3c3c3c3c3c3'] }),
        signedByOther: sign(other.priv, { kind: 'removed', v: TRUST_V, school, issued: NOW, serials: ['a1a1a1a1a1a1a1a1'] }),
    },
    proof: { nonce: 'n0nce-1234', code: '86GZ', host: '192.168.1.20:8085', sig: signProof(teacher.priv, 'n0nce-1234', '86GZ', '192.168.1.20:8085') },
    javaSigned: null as unknown,
};

// New keys make the old "javaSigned" entry invalid: it starts empty, and the tests fail until a
// Java run adds it again (TrustCheckTest.printJavaSignedVector).
const file = path.join(__dirname, 'trust-vectors.json');
fs.writeFileSync(file, `${JSON.stringify(out, null, 2)}\n`);
console.log(`wrote ${file}; add javaSigned from a Java run`);
