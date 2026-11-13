// sockets/privacy.ts -- K2GO-559
//
// Per-service switches for the box services that contact an outside server on their own.
// Each switch is the service's OWN setting in its own config file (no traffic interception),
// so the service config stays the one source of truth: we read it every time, we never cache it.
// New bakes ship each one off (tools/upstream-patches 0007, 0008); this also reaches boxes
// installed before that change.
import fs from 'fs';

export type PrivacyServiceId = 'kolibri' | 'forgejo';

export interface PrivacySwitch {
    /** Also the pdsm service name (services.ts). */
    service: PrivacyServiceId;
    file: string;
    section: string;
    key: string;
    /** true: the key turns the outbound contact OFF (Kolibri DISABLE_PING). */
    inverted: boolean;
}

export interface PrivacyState {
    service: PrivacyServiceId;
    /** The config file exists. A service that is not installed has nothing to switch. */
    installed: boolean;
    /** The service may contact its outside server. */
    outbound: boolean;
}

export const PRIVACY_SWITCHES: readonly PrivacySwitch[] = [
    // Kolibri statistics ping to telemetry.learningequality.org (default on).
    // https://kolibri.readthedocs.io/en/latest/manage/options_ini.html
    {
        service: 'kolibri',
        file: process.env.K2GO_KOLIBRI_OPTIONS_INI || '/library/kolibri/options.ini',
        section: 'Deployment', key: 'DISABLE_PING', inverted: true,
    },
    // Forgejo release check (default on). https://forgejo.org/docs/latest/admin/config-cheat-sheet/
    {
        service: 'forgejo',
        file: process.env.K2GO_FORGEJO_APP_INI || '/etc/forgejo/app.ini',
        section: 'cron.update_checker', key: 'ENABLED', inverted: false,
    },
];

export function findSwitch(service: string): PrivacySwitch | undefined {
    return PRIVACY_SWITCHES.find((s) => s.service === service);
}

const SECTION_RE = /^\s*\[([^\]]+)\]\s*$/;
const KEY_RE = /^(\s*)([^=;#\s][^=]*?)\s*=\s*(.*?)\s*$/;

/** The section each line belongs to ('' before the first header). Names compare case-insensitively. */
function sectionOfLines(lines: string[]): string[] {
    let cur = '';
    return lines.map((l) => {
        const m = SECTION_RE.exec(l);
        if (m) cur = m[1].trim().toLowerCase();
        return m ? '' : cur;
    });
}

/** The value of `key` in `[section]`, or undefined. A repeated key: the last one wins. */
export function readIniOption(text: string, section: string, key: string): string | undefined {
    const lines = text.split(/\r?\n/);
    const owner = sectionOfLines(lines);
    const sec = section.toLowerCase();
    const k = key.toLowerCase();
    let value: string | undefined;
    lines.forEach((l, i) => {
        if (owner[i] !== sec) return;
        const m = KEY_RE.exec(l);
        if (m && m[2].toLowerCase() === k) value = m[3].replace(/^(["'])(.*)\1$/, '$2');
    });
    return value;
}

/** `text` with `key = value` set in `[section]`. Every other line stays as it is. A missing key
 *  goes after the last line of the section; a missing section goes at the end of the file. */
export function setIniOption(text: string, section: string, key: string, value: string): string {
    const eol = text.includes('\r\n') ? '\r\n' : '\n';
    const lines = text.split(/\r?\n/);
    const owner = sectionOfLines(lines);
    const sec = section.toLowerCase();
    const k = key.toLowerCase();
    let found = false;
    let lastOfSection = -1;
    lines.forEach((l, i) => {
        const header = SECTION_RE.exec(l);
        if (header) {
            if (header[1].trim().toLowerCase() === sec) lastOfSection = i;
            return;
        }
        if (owner[i] !== sec) return;
        if (l.trim() !== '') lastOfSection = i;
        const m = KEY_RE.exec(l);
        if (m && m[2].toLowerCase() === k) {
            lines[i] = `${m[1]}${m[2]} = ${value}`;
            found = true;
        }
    });
    if (found) return lines.join(eol);
    if (lastOfSection >= 0) {
        lines.splice(lastOfSection + 1, 0, `${key} = ${value}`);
        return lines.join(eol);
    }
    const body = text === '' || text.endsWith('\n') ? text : text + eol;
    return `${body}${body === '' ? '' : eol}[${section}]${eol}${key} = ${value}${eol}`;
}

/** INI booleans as Kolibri (configobj) and Forgejo (go-ini) read them; undefined when unclear. */
export function parseIniBool(v: string | undefined): boolean | undefined {
    if (v === undefined) return undefined;
    const s = v.trim().toLowerCase();
    if (['true', 'yes', 'on', '1', 't', 'y'].includes(s)) return true;
    if (['false', 'no', 'off', '0', 'f', 'n'].includes(s)) return false;
    return undefined;
}

/** Outbound state from the config text. A missing or unclear key means the service default: on. */
export function outboundFromText(sw: PrivacySwitch, text: string): boolean {
    const b = parseIniBool(readIniOption(text, sw.section, sw.key));
    if (b === undefined) return true;
    return sw.inverted ? !b : b;
}

/** The value to write so the service allows (or blocks) its outbound contact. */
export function valueFor(sw: PrivacySwitch, outbound: boolean): string {
    const on = sw.inverted ? !outbound : outbound;
    // Each file keeps the spelling its own tool writes (Ansible ini_file "True"; Forgejo "false").
    return sw.inverted ? (on ? 'True' : 'False') : (on ? 'true' : 'false');
}

export function readState(sw: PrivacySwitch): PrivacyState {
    let text: string;
    try {
        text = fs.readFileSync(sw.file, 'utf8');
    } catch {
        return { service: sw.service, installed: false, outbound: false };
    }
    return { service: sw.service, installed: true, outbound: outboundFromText(sw, text) };
}

/** Same mode and owner as the file it replaces; write + fsync + rename, so a crash leaves the old file. */
function writeAtomic(file: string, data: string): void {
    const st = fs.statSync(file);
    const tmp = file + '.k2go-tmp';
    const fd = fs.openSync(tmp, 'w', st.mode & 0o7777);
    try {
        fs.writeSync(fd, data);
        fs.fsyncSync(fd);
    } finally {
        fs.closeSync(fd);
    }
    fs.chmodSync(tmp, st.mode & 0o7777);   // openSync applies the umask
    try { fs.chownSync(tmp, st.uid, st.gid); } catch { /* not root: the file keeps our uid */ }
    fs.renameSync(tmp, file);
}

/** Set the switch. Returns the new state and whether the file changed (the caller restarts the
 *  service only then). Throws when the service is not installed or the file cannot be written. */
export function applyOutbound(sw: PrivacySwitch, outbound: boolean): { state: PrivacyState; changed: boolean } {
    const text = fs.readFileSync(sw.file, 'utf8');
    if (outboundFromText(sw, text) === outbound) {
        return { state: { service: sw.service, installed: true, outbound }, changed: false };
    }
    writeAtomic(sw.file, setIniOption(text, sw.section, sw.key, valueFor(sw, outbound)));
    return { state: readState(sw), changed: true };
}
