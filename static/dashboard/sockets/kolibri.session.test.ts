// sockets/kolibri.session.test.ts
//
// Tests de los helpers PUROS del camino de Kolibri, en la línea de
// maps.socket.test.ts y rolling-log.test.ts: sin red, sin SQLite, sin Kolibri.
// Cubren justamente las trampas que hacen fallar el primer intento — nombres de
// cookie no estándar, rotación del token CSRF, percentage 0-1 vs 0-100, y el
// payload que Kolibri rechaza si se manda 'peer': null.
//
// Ejecutar:  npm test   (añade este fichero al script "test" de package.json)
//
// Importa SOLO de kolibri.session.ts y kolibri.map.ts. Ninguno de los dos arrastra
// jobs.ts, así que no se necesita el binario nativo de better-sqlite3 — el CI
// instala con `npm ci --ignore-scripts` y no lo compila.
/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';

import { cookieValue, mergeCookies } from './kolibri.session';
import {
    mapPhase, mapPercent, normalizeUuid, buildTaskPayload, overallPercent, sampleSpeed,
} from './kolibri.map';

// ─── cookieValue ─────────────────────────────────────────────────────────────

test('cookieValue encuentra la cookie CSRF de Kolibri, que no se llama csrftoken', () => {
    const setCookies = [
        'kolibri_csrftoken=abc123; expires=Fri, 01 Jan 2027 00:00:00 GMT; Path=/; SameSite=Lax',
        'kolibri=sess789; expires=Fri, 01 Jan 2027 00:00:00 GMT; HttpOnly; Path=/',
    ];
    assert.equal(cookieValue(setCookies, 'kolibri_csrftoken'), 'abc123');
    assert.equal(cookieValue(setCookies, 'kolibri'), 'sess789');
    // Los nombres de Django estándar NO existen en Kolibri: si alguien los busca,
    // debe obtener null en lugar de un falso positivo.
    assert.equal(cookieValue(setCookies, 'csrftoken'), null);
    assert.equal(cookieValue(setCookies, 'sessionid'), null);
});

test('cookieValue tolera cabeceras raras', () => {
    assert.equal(cookieValue([], 'kolibri'), null);
    assert.equal(cookieValue(['sin-signo-igual'], 'kolibri'), null);
    // Un valor con '=' dentro (base64) debe conservarse completo.
    assert.equal(cookieValue(['kolibri=a=b=c; Path=/'], 'kolibri'), 'a=b=c');
});

// ─── mergeCookies ────────────────────────────────────────────────────────────

test('mergeCookies aplica la rotación del token CSRF tras el login', () => {
    // Django rota el token al autenticar: el valor nuevo debe ganar, o el primer
    // POST posterior fallaría con 403.
    const before = 'kolibri_csrftoken=viejo';
    const afterLogin = ['kolibri_csrftoken=nuevo; Path=/', 'kolibri=sesion; Path=/'];
    const merged = mergeCookies(before, afterLogin);
    assert.match(merged, /kolibri_csrftoken=nuevo/);
    assert.doesNotMatch(merged, /viejo/);
    assert.match(merged, /kolibri=sesion/);
});

test('mergeCookies conserva cookies previas no reemplazadas', () => {
    const merged = mergeCookies('otra=conservada; kolibri_csrftoken=v1', ['kolibri_csrftoken=v2']);
    assert.match(merged, /otra=conservada/);
    assert.match(merged, /kolibri_csrftoken=v2/);
});

test('mergeCookies parte de vacío sin generar basura', () => {
    const merged = mergeCookies('', ['kolibri=s; HttpOnly']);
    assert.equal(merged, 'kolibri=s');
});

// ─── mapPhase ────────────────────────────────────────────────────────────────

test('mapPhase cubre los nueve estados de Kolibri', () => {
    assert.equal(mapPhase('PENDING'), 'queued');
    assert.equal(mapPhase('SCHEDULED'), 'queued');
    assert.equal(mapPhase('QUEUED'), 'queued');
    assert.equal(mapPhase('SELECTED'), 'queued');
    assert.equal(mapPhase('RUNNING'), 'downloading');
    assert.equal(mapPhase('COMPLETED'), 'done');
    assert.equal(mapPhase('FAILED'), 'error');
    assert.equal(mapPhase('CANCELING'), 'canceled');
    assert.equal(mapPhase('CANCELED'), 'canceled');
});

test('mapPhase degrada un estado desconocido sin romper', () => {
    assert.equal(mapPhase('ALGO_NUEVO'), 'processing');
});

// ─── mapPercent ──────────────────────────────────────────────────────────────

test('mapPercent convierte el float 0-1 de Kolibri a 0-100 entero', () => {
    // Es la confusión más fácil de cometer: percentage NO es 0-100.
    assert.equal(mapPercent(0), 0);
    assert.equal(mapPercent(0.5), 50);
    assert.equal(mapPercent(0.333), 33);
    assert.equal(mapPercent(1), 100);
});

test('mapPercent devuelve -1 (indeterminado) cuando no hay dato', () => {
    assert.equal(mapPercent(null), -1);
    assert.equal(mapPercent(undefined), -1);
    assert.equal(mapPercent(NaN), -1);
});

test('mapPercent acota valores fuera de rango', () => {
    assert.equal(mapPercent(1.5), 100);
    assert.equal(mapPercent(-0.2), 0);
});

// ─── normalizeUuid ───────────────────────────────────────────────────────────

test('normalizeUuid acepta hex de 32 y también con guiones', () => {
    const hex = '95a52b386f2c485cb97dd60901674a98';
    assert.equal(normalizeUuid(hex), hex);
    assert.equal(normalizeUuid('95A52B386F2C485CB97DD60901674A98'), hex);
    assert.equal(normalizeUuid('95a52b38-6f2c-485c-b97d-d60901674a98'), hex);
    assert.equal(normalizeUuid(`  ${hex}  `), hex);
});

test('normalizeUuid rechaza tokens: hay que resolverlos antes de encolar', () => {
    // Un token proquint no es un channel_id. Pasarlo tal cual acabaría en un 404
    // del descargador sin mensaje claro.
    assert.equal(normalizeUuid('bisan-sukod'), null);
    assert.equal(normalizeUuid('bisansukod'), null);
    assert.equal(normalizeUuid(''), null);
    assert.equal(normalizeUuid('zz'.repeat(16)), null);
    assert.equal(normalizeUuid(undefined), null);
    assert.equal(normalizeUuid(42), null);
});

// ─── buildTaskPayload ────────────────────────────────────────────────────────

const CH = '95a52b386f2c485cb97dd60901674a98';

test('buildTaskPayload usa remoteimport y siempre manda channel_name', () => {
    const p = buildTaskPayload({ channelId: CH, channelName: 'Khan Academy' });
    assert.equal(p.type, 'kolibri.core.content.tasks.remoteimport');
    assert.equal(p.channel_id, CH);
    // Obligatorio: sin él, Kolibri responde 400 aunque solo sea decorativo.
    assert.equal(p.channel_name, 'Khan Academy');
    assert.equal(p.fail_on_error, true);
});

test('buildTaskPayload NUNCA incluye peer', () => {
    // 'peer' es PrimaryKeyRelatedField sin allow_null: mandar null da 400
    // "This field may not be null". Hay que omitir la clave.
    const p = buildTaskPayload({ channelId: CH, channelName: 'x' });
    assert.equal('peer' in p, false);
});

test('buildTaskPayload omite node_ids cuando no hay selección', () => {
    // Distinción crítica de Kolibri: node_ids=[] significa CERO NODOS, mientras que
    // ausente significa TODO EL CANAL. Mandar una lista vacía descargaría nada con
    // éxito aparente.
    const p = buildTaskPayload({ channelId: CH, channelName: 'x', nodeIds: [] });
    assert.equal('node_ids' in p, false);

    const q = buildTaskPayload({ channelId: CH, channelName: 'x', nodeIds: undefined });
    assert.equal('node_ids' in q, false);
});

test('buildTaskPayload incluye la selección cuando existe', () => {
    const nodes = ['aaaa1111bbbb2222cccc3333dddd4444'];
    const p = buildTaskPayload({ channelId: CH, channelName: 'x', nodeIds: nodes });
    assert.deepEqual(p.node_ids, nodes);
});

test('buildTaskPayload solo manda all_thumbnails si se pide', () => {
    const sin = buildTaskPayload({ channelId: CH, channelName: 'x' });
    assert.equal('all_thumbnails' in sin, false);

    const con = buildTaskPayload({ channelId: CH, channelName: 'x', allThumbnails: true });
    assert.equal(con.all_thumbnails, true);
});

test('buildTaskPayload omite exclude_node_ids vacío', () => {
    const p = buildTaskPayload({ channelId: CH, channelName: 'x', excludeNodeIds: [] });
    assert.equal('exclude_node_ids' in p, false);
});

// ─── overallPercent ──────────────────────────────────────────────────────────

test('overallPercent no toca el valor cuando hay un solo canal', () => {
    assert.equal(overallPercent(1, 1, 42), 42);
    assert.equal(overallPercent(1, 1, -1), -1);
});

test('overallPercent reparte la barra en franjas por canal', () => {
    // Con 3 canales, el primero al 0% es 0 global y al 100% es 33 global; así la
    // barra sube una sola vez en lugar de reiniciarse tres veces.
    assert.equal(overallPercent(1, 3, 0), 0);
    assert.equal(overallPercent(1, 3, 100), 33);
    assert.equal(overallPercent(2, 3, 0), 33);
    assert.equal(overallPercent(3, 3, 50), 83);
});

test('overallPercent se acota a 99: el 100 lo pone el final del runner', () => {
    assert.equal(overallPercent(3, 3, 100), 99);
});

test('overallPercent propaga el indeterminado', () => {
    assert.equal(overallPercent(2, 3, -1), -1);
});

// ─── sampleSpeed ─────────────────────────────────────────────────────────────

test('sampleSpeed calcula bytes por segundo entre dos muestras', () => {
    // 1 MiB en 2 s → ~524 288 B/s
    assert.equal(sampleSpeed(0, 1048576, 1000, 3000), 524288);
});

test('sampleSpeed devuelve null sin avance, para no pisar la velocidad anterior', () => {
    // Devolver 0 haría parpadear la UI a "0 B/s" en cada poll sin progreso.
    assert.equal(sampleSpeed(1000, 1000, 0, 2000), null);
    assert.equal(sampleSpeed(1000, 500, 0, 2000), null);
});

test('sampleSpeed no divide por cero con dos muestras simultáneas', () => {
    const v = sampleSpeed(0, 100, 5000, 5000);
    assert.ok(v !== null && Number.isFinite(v) && v > 0);
});
