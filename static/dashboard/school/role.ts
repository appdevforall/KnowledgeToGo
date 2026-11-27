// K2Go School: the role of this phone, from the school trust (school-trust-design.md 5). The app
// keeps the trust files in its no_backup folder and binds the folder here (SchoolTrustFiles.GUEST_DIR
// in the app): root.token (the pinned root of the school) and teacher.cert (the certificate of a
// teacher phone). No root: no role. A root and a verified certificate: teacher. Else: student. The
// app derives the role with the same rule (PhoneRole.java). The role is not stored: no file can
// say "teacher" without a certificate that the school signed.
import fs from 'fs';
import path from 'path';
import { checkTeacher, deriveRole, DerivedRole, MAX_TOKEN, readRoot, RootRecord, TeacherCheck } from './trust';

export type SchoolRole = DerivedRole;

export const TRUST_DIR = '/run/k2go/school';
export const ROOT_FILE = 'root.token';
export const CERT_FILE = 'teacher.cert';

// The text of a trust file, or null: no file (or no bind, for example a box started by hand), or a
// file that is too long to be a token.
function readToken(file: string): string | null {
    try {
        const st = fs.statSync(file);
        if (!st.isFile() || st.size > MAX_TOKEN + 2) return null;
        return fs.readFileSync(file, 'utf8').trim();
    } catch {
        return null;
    }
}

export interface PhoneTrust { root: RootRecord | null; cert: TeacherCheck | null; role: SchoolRole }

// Read on each request, no cache: the files are the one owner of the trust. A root file that is
// there but not a valid root gives a student: a file of the school always takes rights away.
export function readTrust(dir: string = TRUST_DIR, now: number = Math.floor(Date.now() / 1000)): PhoneTrust {
    const rootText = readToken(path.join(dir, ROOT_FILE));
    if (rootText === null) return { root: null, cert: null, role: 'none' };
    const root = readRoot(rootText);
    if (!root) return { root: null, cert: null, role: 'student' };
    const certText = readToken(path.join(dir, CERT_FILE));
    const cert = certText === null ? null : checkTeacher(root, certText, null, now);
    return { root, cert, role: deriveRole(root, cert) };
}

export function readRole(dir: string = TRUST_DIR): SchoolRole {
    return readTrust(dir).role;
}
