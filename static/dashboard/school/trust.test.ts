/// <reference types="node" />
// The school trust against the shared vectors. TrustCheckTest.java runs the same vectors in the app.
import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { checkProof, checkTeacher, deriveRole, mergeRemoved, readRemoved, readRoot, RootRecord, sign, signProof, TRUST_V } from './trust';

const V = JSON.parse(fs.readFileSync(path.join(__dirname, 'trust-vectors.json'), 'utf8'));
const root = readRoot(V.root) as RootRecord;
const removed = mergeRemoved(readRemoved(root, V.removed.first), readRemoved(root, V.removed.second));

test('the root record: its own signature and a school id from its key', () => {
    assert.ok(root);
    assert.equal(root.school, V.school);
    assert.equal(root.name, 'Test school');
    assert.ok(readRoot(V.rootOtherSchool));                 // a valid root of another school
    assert.equal(readRoot(V.rootWrongSchoolId), null);       // the id does not come from the key
    assert.equal(readRoot(V.rootSignedByOther), null);       // not signed by its own key
    assert.equal(readRoot(V.teacher.verified), null);        // another kind
    assert.equal(readRoot('x'.repeat(5000)), null);          // too long
});

test('teacher certificates: each verdict', () => {
    const v = (t: unknown) => checkTeacher(root, t, removed, V.now).verdict;
    assert.equal(v(V.teacher.verified), 'verified');
    assert.equal(v(V.teacher.expired), 'expired');
    assert.equal(v(V.teacher.notYet), 'not-yet');
    assert.equal(v(V.teacher.otherSchool), 'other-school');
    assert.equal(v(V.teacher.signedByOther), 'bad-signature');
    assert.equal(v(V.teacher.tampered), 'bad-signature');
    assert.equal(v(V.teacher.removed), 'removed');
    assert.equal(v(V.teacher.wrongKind), 'malformed');
    assert.equal(v(V.teacher.badVersion), 'malformed');
    assert.equal(v(V.teacher.notAToken), 'malformed');
    assert.equal(v(undefined), 'malformed');
    assert.equal(checkTeacher(root, V.teacher.verified, removed, V.now).cert?.label, 'Room 3B');
});

test('removed lists: only the root signs them; a merge keeps every serial', () => {
    assert.equal(readRemoved(root, V.removed.signedByOther), null);
    assert.equal(readRemoved(root, V.teacher.verified), null);
    assert.deepEqual(removed?.serials, ['b2b2b2b2b2b2b2b2', 'c3c3c3c3c3c3c3c3']);
    assert.equal(removed?.issued, V.now - 50);
    // An older list after a newer one removes nothing.
    const again = mergeRemoved(removed, readRemoved(root, V.removed.first));
    assert.deepEqual(again?.serials, removed?.serials);
    assert.equal(mergeRemoved(null, null), null);
});

test('the derived role', () => {
    assert.equal(deriveRole(null, null), 'none');
    assert.equal(deriveRole(root, null), 'student');
    assert.equal(deriveRole(root, checkTeacher(root, V.teacher.verified, removed, V.now)), 'teacher');
    assert.equal(deriveRole(root, checkTeacher(root, V.teacher.removed, removed, V.now)), 'student');
    assert.equal(deriveRole(root, checkTeacher(root, V.teacher.expired, removed, V.now)), 'student');
});

test('the class proof: the box holds the teacher key, for this nonce, class and address', () => {
    const cert = checkTeacher(root, V.teacher.verified, removed, V.now).cert!;
    const p = V.proof;
    assert.ok(checkProof(cert, p.nonce, p.code, p.host, p.sig));
    assert.ok(!checkProof(cert, p.nonce, 'XXXX', p.host, p.sig));
    assert.ok(!checkProof(cert, 'other-nonce', p.code, p.host, p.sig));
    assert.ok(!checkProof(cert, p.nonce, p.code, '192.168.1.99:8085', p.sig));   // relayed by another box
    assert.ok(!checkProof(cert, p.nonce, p.code, p.host, 'AAAA'));
    // A fresh proof with the test teacher key; a field with a newline or a space is refused.
    const key = crypto.createPrivateKey({ key: Buffer.from(V.keys.teacherPkcs8, 'base64'), format: 'der', type: 'pkcs8' });
    assert.ok(checkProof(cert, 'n2', 'ABCD', 'h:1', signProof(key, 'n2', 'ABCD', 'h:1')));
    assert.equal(signProof(key, 'n2\nABCD', 'X', 'h:1'), null);
    assert.equal(signProof(key, 'n 2', 'X', 'h:1'), null);
    assert.equal(signProof(key, '', 'X', 'h:1'), null);
    assert.ok(!checkProof(cert, 'n2\nABCD', 'X', 'h:1', signProof(key, 'n2', 'ABCD', 'h:1')));
});

test('a certificate ends with the school year of its root; lists of two schools do not merge', () => {
    const rootKey = crypto.createPrivateKey({ key: Buffer.from(V.keys.rootPkcs8, 'base64'), format: 'der', type: 'pkcs8' });
    const t = sign(rootKey, { kind: 'teacher', v: TRUST_V, school: root.school, key: V.keys.teacherSpki, label: 'R', serial: 'd5d5d5d5d5d5d5d5', from: 0, until: root.until + 1000 });
    assert.equal(checkTeacher(root, t, null, root.until - 1).verdict, 'verified');
    assert.equal(checkTeacher(root, t, null, root.until).verdict, 'expired');
    const other = { kind: 'removed' as const, v: TRUST_V, school: 'ffffffffffffffff', issued: V.now + 9, serials: ['a1a1a1a1a1a1a1a1'] };
    assert.deepEqual(mergeRemoved(removed, other), removed);
});

test('a teacher check with no root is malformed', () => {
    assert.equal(checkTeacher(null, V.teacher.verified, null, V.now).verdict, 'malformed');
});

test('a key of another curve is refused', () => {
    const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'secp384r1' });
    const spki = publicKey.export({ format: 'der', type: 'spki' }).toString('base64');
    const rootKey = crypto.createPrivateKey({ key: Buffer.from(V.keys.rootPkcs8, 'base64'), format: 'der', type: 'pkcs8' });
    const t = sign(rootKey, { kind: 'teacher', v: TRUST_V, school: root.school, key: spki, label: 'R', serial: 'd4d4d4d4d4d4d4d4', from: 0, until: V.now + 10 });
    const cert = checkTeacher(root, t, null, V.now).cert!;
    assert.ok(!checkProof(cert, 'n', 'c', 'h:1', signProof(privateKey, 'n', 'c', 'h:1')));
});

test('a certificate that the app signed (Java) passes on the box', () => {
    assert.ok(V.javaSigned, 'trust-vectors.json has no javaSigned entry: run TrustCheckTest and add it');
    assert.equal(checkTeacher(root, V.javaSigned.teacher, null, V.now).verdict, 'verified');
    assert.deepEqual(readRemoved(root, V.javaSigned.removed)?.serials, ['e5e5e5e5e5e5e5e5']);
});
