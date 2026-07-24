import express from 'express';
import http from 'http';
import { Server, Socket } from 'socket.io';
import path from 'path';
import helmet from 'helmet';

// Import our modules (event controllers)
import { handleMapsEvents } from './sockets/maps.socket';
import { handleKiwixEvents } from './sockets/kiwix.socket';
import { handleHomeEvents } from './sockets/home.socket';
import { handleBooksEvents } from './sockets/books.socket';

// ADFA-4838: durable REST job engine. Importing the runner modules registers them with
// the shared JobManager; the router exposes them over /api.
import { jobs } from './sockets/jobs';
import './sockets/kiwix.exec';
import './sockets/maps.exec';
import './sockets/books.exec';
import { apiRouter } from './routes';

const app = express();
const server = http.createServer(app);
const io = new Server(server);

// 🛡️ SECURITY SHIELD (HELMET)
// ==========================================
app.use(helmet({
    // No https, no SSL, no HSTS.
    hsts: false,
    // Check websockets works without restrictions
    contentSecurityPolicy: false
}));

// EJS views and static files configuration
app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'views'));
app.use(express.static(path.join(__dirname, 'public')));

// ADFA-4838: JSON body parsing + the REST content API.
app.use(express.json());
app.use('/api', apiRouter);

// Main route
app.get('/', (req, res) => {
    res.render('index');
});

// Main Socket Connection Handler
io.on('connection', (socket: Socket) => {
    console.log(`A client has connected (ID: ${socket.id}).`);
    
    // Connect the wires to the modules
    handleMapsEvents(socket);
    handleKiwixEvents(socket);
    handleHomeEvents(socket);
    handleBooksEvents(socket);
    
    socket.on('disconnect', () => {
        console.log(`Client disconnected (ID: ${socket.id}).`);
    });
});

const PORT = 4000;
server.listen(PORT, () => {
    console.log(`===========================================`);
    console.log(`K2Go Dashboard active on port ${PORT}`);
    console.log(`===========================================`);
    // ADFA-4838: resume any content jobs that were mid-flight before a restart.
    try { jobs.reconcileOnBoot(); } catch (e) { console.error('[jobs] reconcile failed', e); }
});

// ==========================================
// Graceful Shutdown
// ==========================================

// Function to handle the shutdown process
const gracefulShutdown = (signal: string) => {
    console.log(`\n[System] Received ${signal}. Starting graceful shutdown...`);

    // Stop accepting new connections
    server.close(() => {
        console.log('[System] HTTP server closed. No longer accepting connections.');

        // TODO: Add cleanly stop aria2c or python scripts if they are running

        console.log('[System] Cleanup complete. Exiting safely.');
        process.exit(0);
    });

    // If the server takes too long to close (e.g., stuck websockets), force it after 5 seconds
    setTimeout(() => {
        console.error('[System] Could not close connections in time. Forcing shutdown.');
        process.exit(1);
    }, 5000);
};

// Listen for PDSM pkill
process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));

// Listen for CTRL+C in the terminal
process.on('SIGINT', () => gracefulShutdown('SIGINT'));
