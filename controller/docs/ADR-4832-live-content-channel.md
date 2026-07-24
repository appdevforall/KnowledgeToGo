# ADR — Content management on the live system (app ↔ in-server channel)

Status: Accepted. Phase 1 (socket.io bridge) shipped (ADFA-4832). Phase 2 (REST + durable jobs, full scope) accepted and in progress — see "Phase 2 (accepted) — design of record" below. Tickets: ADFA-4832 (epic thread), plus the Phase 2 tickets listed in that section.

## Context

- The IIAB server runs inside a **single long-lived proot**, co-located in the Android app process — it is **not** a remote 24/7 server. Keeping work alive across Android's lifecycle (backgrounding, low memory, battery/Doze) is the job of a **foreground service**, not of any network connection. (This is the lesson from the v1 design, where the app was a front-end to a separate Termux app that Android backgrounded and killed.)
- `PRootEngine` has no mutual exclusion. Adding content on a running system spawned a **second proot** (`iiab-make-kiwix-lib`) over the same rootfs, which collided with the live server (shared `/tmp`, `/dev/shm`, kiwix index, ports, service restarts, and a global `killall -9 proot`) and broke Kiwix.
- proot **cannot be entered after start** (ptrace sandbox, no nsenter/attach). Commands on the live system must be initiated by a process **already inside** the running proot. That process exists: the in-server **Node dashboard** (Express + socket.io on localhost:8085 → :4000, started by `pdsm`) already downloads + indexes ZIM/maps/books in-process.

## Decision

1. Content ops on the live system go through an **in-server control channel**, never app-side proot.
2. **Phase 1 (bridge):** the app becomes a client of the **existing dashboard socket.io** channel for ZIM add; the **foreground `InstallService` owns the connection** so UI/config changes don't drop it and kill the job. This fixes existing installs via an APK update (server code is provisioned, not APK-shipped). Scope: ZIM only.
3. **Phase 2 (target):** a proper **REST start + poll** (or SSE) contract with **durable, resumable jobs** that live in the server independent of any connection. This is the Android-appropriate model: short disposable localhost calls; the job is owned by the foreground service / proot and survives UI churn and even a socket/service restart. Ships via provisioning.
4. **Do NOT build a separate command server (cmdsrv).** It would replicate the dashboard's orchestration over the same Debian tools (`aria2c`, `iiab-make-kiwix-lib`, `tile-extract.py`, calibre-web) with no functional gain. The "Node is fragile" concern is really the `ts-node` dev-mode packaging → addressed in Phase 2 by compiling to JS + `pdsm` supervision/health, not by a parallel server. Keep cmdsrv as a last resort only if the dashboard proves too coupled after hardening.

## Why socket.io for Phase 1 but REST for Phase 2

- **socket.io** holds ONE long-lived connection for the whole job and, as currently built, ties job lifetime to the socket (`disconnect` kills it) — fragile on Android. Used in Phase 1 only because it is already deployed; mitigated by the foreground service owning the socket.
- **REST start+poll** uses short, independent, localhost calls; the long-running job runs detached in the proot owned by the foreground service — there is no long-lived connection to lose. Preferred end state.
- Real-time is not a reliability requirement here: localhost polling every ~1s is effectively free and "live enough".

## Consequences / caveats (Phase 1)

- Progress arrives as raw terminal text; we reuse the aria2 progress regex to extract `%`. Indexing is shown as indeterminate.
- No explicit done/error event: success is inferred from "Indexing complete"/`refresh_kiwix_catalog`; failure from an error line or an `isRunning:false` not followed by a refresh.
- The app-side IPv4/IPv6 network profiler is not used for the ZIM download (the server's `aria2c` is a plain spawn). Acceptable now; port in Phase 2 if needed.
- Maps/books on a live system still need the same migration (follow-up). Phase 1 skips the maps proot on the live path to avoid re-introducing a collision.
- If the app process is fully killed, the job dies (server kills on disconnect). Phase 2 durable jobs remove this.

## Phase 2 (accepted) — design of record

Scope decision: **full Phase 2 in one rootfs rebuild** — kiwix + maps + books on the REST engine, IPv4/aria2 parity, and the dashboard packaging (build + supervision). Iteration decision: a **dev "push static/" path** copies the updated dashboard into the installed rootfs on-device and restarts `dash-node`, so we don't pay the ~2h rebuild per test; the full rebuild happens once at the end.

### Server (in-server dashboard)

1. **Job manager, module-scoped (not per-socket).** The download+index job is owned by the dashboard process, not by any connection. A client `disconnect` no longer kills anything (Phase 1 killed on disconnect). This is the core durability change.
2. **Durable journal via `better-sqlite3`** (already a dependency). Table `jobs(id, type, target, phase, percent, speed, error, created, updated)`. On dashboard boot, reconcile: incomplete download jobs resume (aria2 `--continue` already resumes partials), indexing re-runs if it hadn't finished.
3. **Progress parsed server-side into structured fields.** aria2/index stdout is parsed on the server into `{phase: downloading|indexing|done|error, percent, speedBytesPerSec, error}` and stored on the job. Clients read structured JSON — no more client-side terminal-scraping.
4. **REST contract (localhost:8085 → :4000), short calls:**
   - `POST /api/{kiwix|maps|books}/download {ids:[...]}` → `{jobId}`
   - `GET  /api/{...}/jobs/:id` → the structured job row
   - `POST /api/{...}/jobs/:id/cancel`
   - `GET  /api/{...}/catalog`, `DELETE /api/{...}/item/:id`
   The socket.io handlers stay only as a thin compat shell (or are removed) — the job manager is the single source of truth.
5. **aria2 IPv4 profiler parity.** Port the app's IPv4-preference behavior to the server aria2 spawn (closes a divergence noted below).
6. **Packaging.** Compile TS→JS (drop `ts-node` dev mode), run under `pdsm` supervision with a health check; `dash-node` restarts cleanly on failure. This — not a separate cmdsrv — is the answer to "Node is fragile".

### Android

7. **REST content client replaces the socket.io `LiveContentClient`.** Short localhost calls: `POST` to start, **poll `GET` every ~1s** for structured progress, `POST cancel`. Owned by the foreground `InstallService`. There is no long-lived connection to lose; the job survives UI/config churn and even a dashboard/socket restart (durable job + `--continue`). Wire into `InstallService.downloadAndIndexKiwix` and the maps/books paths.

### Tickets

- Dashboard: durable REST job engine + endpoints + sqlite + aria2 IPv4 parity (kiwix/maps/books).
- Dashboard packaging: TS→JS build + pdsm supervision/health + dev push-static path.
- Android: REST content client (foreground poll) replacing socket.io Phase 1.

## References

`PRootEngine.java`, `InstallService.downloadAndIndexKiwix`, `ServerController`, `static/dashboard/server.ts` + `sockets/kiwix.socket.ts`, `LiveContentClient.java`. Ticket ADFA-4832.
