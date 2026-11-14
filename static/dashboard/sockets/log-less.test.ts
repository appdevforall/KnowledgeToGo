/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'fs';
import os from 'os';
import path from 'path';
import {
    applyLogLessOnce, forgejoWithoutRouterLog, nginxWithoutClientIds, purgeOldLogsOnce, rotatedBefore,
    LogLessStep, ServiceLog,
} from './log-less';

const STOCK_NGINX = [
    'http {',
    '    log_format awstats',
    `    '$remote_addr - $remote_user [$time_local] "$request" '`,
    `    '$status $body_bytes_sent "$http_referer" '`,
    `    '"$http_user_agent" "http_x_forwarded_for"';`,
    '',
    '    access_log /var/log/nginx/access.log awstats;',
    `    log_format scripts '$request > $document_root$fastcgi_script_name $fastcgi_path_info';`,
    '}',
    '',
].join('\n');

test('nginx: the stock awstats format loses the client IP and User-Agent, same fields', () => {
    const out = nginxWithoutClientIds(STOCK_NGINX)!;
    assert.ok(out.includes(`'- - $remote_user [$time_local] "$request" '`));
    assert.ok(out.includes(`'"-" "http_x_forwarded_for"';`));
    assert.ok(!out.includes('$remote_addr') && !out.includes('$http_user_agent'));
    assert.ok(out.includes(`log_format scripts '$request >`));   // other formats stay
    assert.equal(nginxWithoutClientIds(out), undefined);           // second run: nothing to do
});

test('nginx: a changed or missing awstats format is left alone', () => {
    assert.equal(nginxWithoutClientIds('http {\n  access_log off;\n}\n'), undefined);
    assert.equal(nginxWithoutClientIds(`log_format awstats '$remote_user "$request"';`), undefined);
});

test('forgejo: the router log is turned off in [log]; a key the admin set is kept', () => {
    const t = '[mailer]\nENABLED = false\n\n[log]\nROOT_PATH = /var/log/forgejo\nMODE = console\nLEVEL = Info\n';
    const out = forgejoWithoutRouterLog(t)!;
    assert.ok(/\[log\][^[]*LOGGER_ROUTER_MODE = \s*\n/.test(out));
    assert.equal(forgejoWithoutRouterLog(out), undefined);
    assert.equal(forgejoWithoutRouterLog('[log]\nDISABLE_ROUTER_LOG = false\n'), undefined);
    assert.equal(forgejoWithoutRouterLog('[log]\nlogger.router.MODE = console\n'), undefined);
});

function box(files: { nginx?: string; forgejo?: string; calibre?: boolean }) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'logless-'));
    const t = {
        nginxConf: path.join(dir, 'nginx.conf'),
        forgejoIni: path.join(dir, 'app.ini'),
        calibreAppDb: path.join(dir, 'app.db'),
    };
    if (files.nginx !== undefined) fs.writeFileSync(t.nginxConf, files.nginx);
    if (files.forgejo !== undefined) fs.writeFileSync(t.forgejoIni, files.forgejo);
    if (files.calibre) fs.writeFileSync(t.calibreAppDb, '');
    return { t, marker: path.join(dir, 'log-less.done') };
}

test('once: first run changes every installed service, then writes the marker; later runs do nothing', () => {
    const b = box({ nginx: STOCK_NGINX, forgejo: '[log]\nMODE = console\n', calibre: true });
    const calls: string[] = [];
    const calibre = (f: string) => { calls.push(f); return true; };
    assert.deepEqual(applyLogLessOnce(b.t, b.marker, calibre), { ran: true, changed: ['nginx', 'forgejo', 'calibre-web'] });
    assert.deepEqual(calls, [b.t.calibreAppDb]);
    assert.ok(!fs.readFileSync(b.t.nginxConf, 'utf8').includes('$remote_addr'));
    assert.equal(fs.existsSync(b.marker), true);
    assert.deepEqual(applyLogLessOnce(b.t, b.marker, calibre), { ran: false, changed: [] });
    assert.equal(calls.length, 1);
});

test('once: services that are not installed are skipped', () => {
    const b = box({});
    assert.deepEqual(applyLogLessOnce(b.t, b.marker, () => { throw new Error('not called'); }), { ran: true, changed: [] });
    assert.equal(fs.existsSync(b.t.forgejoIni), false);
});

test('once: a failed step leaves no marker, so the next start tries again', () => {
    const b = box({ nginx: STOCK_NGINX, calibre: true });
    assert.throws(() => applyLogLessOnce(b.t, b.marker, () => { throw new Error('database is locked'); }));
    assert.equal(fs.existsSync(b.marker), false);
    assert.deepEqual(applyLogLessOnce(b.t, b.marker, () => false), { ran: true, changed: [] });   // nginx already done
});

test('once: the marker records which services changed (input of the purge)', () => {
    const b = box({ nginx: STOCK_NGINX });
    applyLogLessOnce(b.t, b.marker, () => false);
    assert.deepEqual(JSON.parse(fs.readFileSync(b.marker, 'utf8')).changed, ['nginx']);
});

// --- Purge of the logs written before the change ---------------------------------------------------

function purgeBox(changed: LogLessStep[]) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'logpurge-'));
    const marker = path.join(dir, 'log-less.done');
    const old = Date.now() / 1000 - 3600;                 // one hour before the change
    const logs = {} as Record<LogLessStep, ServiceLog>;
    for (const svc of ['nginx', 'forgejo', 'calibre-web'] as LogLessStep[]) {
        const log = path.join(dir, `${svc}.log`);
        fs.writeFileSync(log, '192.168.1.20 - - "GET /" "Mozilla/5.0 (Linux; moto g)"\n');
        for (const r of [`${log}.1`, `${log}.2.gz`]) { fs.writeFileSync(r, 'old'); fs.utimesSync(r, old, old); }
        logs[svc] = { log, process: svc };
    }
    fs.writeFileSync(path.join(dir, 'nginx.log.bak'), 'not a rotated copy');
    fs.utimesSync(path.join(dir, 'nginx.log.bak'), old, old);
    fs.writeFileSync(marker, JSON.stringify({ at: 'x', changed }) + '\n');
    return { dir, marker, purgeMarker: path.join(dir, 'log-less-purge.done'), logs };
}

test('rotatedBefore: only <log>.N and <log>.N.gz older than the change', () => {
    const b = purgeBox([]);
    const got = rotatedBefore(b.logs.nginx.log, Date.now()).map((f) => path.basename(f)).sort();
    assert.deepEqual(got, ['nginx.log.1', 'nginx.log.2.gz']);
    assert.deepEqual(rotatedBefore(b.logs.nginx.log, Date.now() - 2 * 3600_000), []);
});

test('purge: a changed service on the new config loses its old logs; others are not touched', () => {
    const b = purgeBox(['nginx', 'calibre-web']);
    const started = Date.now() + 1000;                    // restarted after the change
    const r = purgeOldLogsOnce(b.marker, b.purgeMarker, b.logs, () => started);
    assert.deepEqual(r, { ran: true, purged: ['nginx', 'calibre-web'], waiting: [] });
    assert.equal(fs.statSync(b.logs.nginx.log).size, 0);
    assert.equal(fs.existsSync(`${b.logs.nginx.log}.1`), false);
    assert.equal(fs.existsSync(`${b.logs.nginx.log}.2.gz`), false);
    assert.equal(fs.existsSync(path.join(b.dir, 'nginx.log.bak')), true);
    assert.ok(fs.statSync(b.logs.forgejo.log).size > 0);      // not changed: no purge
    assert.equal(fs.existsSync(`${b.logs.forgejo.log}.1`), true);
    assert.equal(fs.existsSync(b.purgeMarker), true);
    assert.equal(purgeOldLogsOnce(b.marker, b.purgeMarker, b.logs, () => started).ran, false);
});

test('purge: a service still on the old config waits; the next start purges it', () => {
    const b = purgeBox(['nginx', 'forgejo']);
    const startOf = (p: string) => (p === 'forgejo' ? Date.now() - 3600_000 : null);   // nginx not running
    assert.deepEqual(purgeOldLogsOnce(b.marker, b.purgeMarker, b.logs, startOf),
        { ran: true, purged: ['nginx'], waiting: ['forgejo'] });
    assert.ok(fs.statSync(b.logs.forgejo.log).size > 0);
    assert.equal(fs.existsSync(b.purgeMarker), false);
    assert.deepEqual(purgeOldLogsOnce(b.marker, b.purgeMarker, b.logs, () => Date.now() + 1000),
        { ran: true, purged: ['nginx', 'forgejo'], waiting: [] });
    assert.equal(fs.statSync(b.logs.forgejo.log).size, 0);
    assert.equal(fs.existsSync(b.purgeMarker), true);
});

test('purge: a copy rotated after the change but before the restart is removed too', () => {
    const b = purgeBox(['nginx']);
    const rotatedLater = `${b.logs.nginx.log}.3`;
    fs.writeFileSync(rotatedLater, '10.0.0.7 - - "GET /" "Mozilla/5.0"');   // mtime now: after the marker
    const r = purgeOldLogsOnce(b.marker, b.purgeMarker, b.logs, () => Date.now() + 1000);
    assert.deepEqual(r.purged, ['nginx']);
    assert.equal(fs.existsSync(rotatedLater), false);
});

test('purge: nothing before the log-less step, nothing to do when it changed nothing', () => {
    const b = purgeBox([]);
    assert.equal(purgeOldLogsOnce(path.join(b.dir, 'missing'), b.purgeMarker, b.logs, () => null).ran, false);
    assert.deepEqual(purgeOldLogsOnce(b.marker, b.purgeMarker, b.logs, () => null), { ran: true, purged: [], waiting: [] });
    assert.ok(fs.statSync(b.logs.nginx.log).size > 0);
});
