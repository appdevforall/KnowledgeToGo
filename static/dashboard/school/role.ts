// K2Go School: the role that the school gives this phone (book chapter 08, "Good" level).
// The app keeps the role file in its no_backup folder and binds the folder here
// (SchoolRoleFile.GUEST_DIR in the app). The app never writes it: the school sets it with the
// terminal command k2go-role. Same rule as SchoolRole.java in the app: keep them equal.
import fs from 'fs';

export type SchoolRole = 'none' | 'teacher' | 'student';

export const ROLE_FILE = '/run/k2go/school/role';
const MAX_BYTES = 64;   // the app reads the same number of bytes

// null: there is no file. Any text other than "teacher" is a student: a file that the school
// made always takes rights away.
export function parseRole(text: string | null): SchoolRole {
    if (text === null) return 'none';
    return text.trim().toLowerCase() === 'teacher' ? 'teacher' : 'student';
}

// Read on each request, no cache: the file is the one owner of the role. No file (or no bind,
// for example a box started by hand) is no role, as before roles.
export function readRole(file: string = ROLE_FILE): SchoolRole {
    let fd: number;
    try {
        fd = fs.openSync(file, 'r');
    } catch (e) {
        return (e as NodeJS.ErrnoException).code === 'ENOENT' ? 'none' : 'student';
    }
    try {
        const buf = Buffer.alloc(MAX_BYTES);
        const n = fs.readSync(fd, buf, 0, MAX_BYTES, 0);
        return parseRole(buf.toString('latin1', 0, n));
    } catch {
        return 'student';
    } finally {
        fs.closeSync(fd);
    }
}
