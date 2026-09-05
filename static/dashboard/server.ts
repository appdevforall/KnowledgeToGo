import express from 'express';
import http from 'http';
import helmet from 'helmet';

// ADFA-4838: durable REST job engine. Importing the runner modules registers them with
// the shared JobManager; the router (routes.ts) exposes them over the REST API.
import { jobs } from './sockets/jobs';
import './sockets/kiwix.exec';
import './sockets/maps.exec';
import './sockets/books.exec';
import './sockets/kolibri.exec';
import { apiRouter } from './routes';
import { startServiceHeal } from './sockets/service-heal';
import { startLogRotation } from './sockets/log-rotate';

const app = express();
const server = http.createServer(app);

// Security headers. No TLS on the box; nginx fronts us on loopback.
app.use(helmet({
    hsts: false,
    contentSecurityPolicy: false,
}));

// ADFA-4933: this process is now a headless REST core. The legacy web dashboard UI
// (EJS views + socket.io) was retired — the app talks to us over REST only. Internally
// the router stays mounted at /api; nginx exposes it on the box under /k2go-api.
app.use(express.json());
app.use('/api', apiRouter);

// ADFA-5011: PORT is env-overridable so a staged build can be smoke-tested on a temp port
// (e.g. 4010) before it is swapped live on 4000 — see tools/rebuild-dashboard.sh.
const PORT = Number(process.env.PORT) || 4000;
// ADFA-4839/4933: bind to loopback only. nginx (localhost) proxies /k2go-api to us;
// there is no reason to expose :4000 on the device's network interfaces.
server.listen(PORT, '127.0.0.1', () => {
    console.log(`===========================================`);
    console.log(`K2Go REST core active on port ${PORT}`);
    console.log(`===========================================`);
    // ADFA-4838: resume any content jobs that were mid-flight before a restart.
    try { jobs.reconcileOnBoot(); } catch (e) { console.error('[jobs] reconcile failed', e); }
    // ADFA-5343 (ADR-5343a §10): the box heals its own content-service tree in-proot.
    try { startServiceHeal(); } catch (e) { console.error('[service-heal] start failed', e); }
    // K2GO-386 (ADR-386 §5): proot has no cron — dash-node triggers logrotate on a timer so the
    // K2Go-owned rotation config actually runs (no rotation at boot; first pass at +10 min).
    try { startLogRotation(); } catch (e) { console.error('[log-rotate] start failed', e); }
});

// ==========================================
// Graceful Shutdown
// ==========================================
const gracefulShutdown = (signal: string) => {
    console.log(`\n[System] Received ${signal}. Starting graceful shutdown...`);

    server.close(() => {
        console.log('[System] HTTP server closed. No longer accepting connections.');
        console.log('[System] Cleanup complete. Exiting safely.');
        process.exit(0);
    });

    setTimeout(() => {
        console.error('[System] Could not close connections in time. Forcing shutdown.');
        process.exit(1);
    }, 5000);
};

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));
