/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { applyLogLessOnce, forgejoWithoutRouterLog, nginxWithoutClientIds } from './log-less';

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
