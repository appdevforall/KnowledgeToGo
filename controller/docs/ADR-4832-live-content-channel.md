# ADR — Content management on the live system (app ↔ in-server channel)

Status: Accepted. Phase 1 (socket.io bridge) in progress; Phase 2 (REST + durable jobs) planned. Ticket: ADFA-4832.

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

## References

`PRootEngine.java`, `InstallService.downloadAndIndexKiwix`, `ServerController`, `static/dashboard/server.ts` + `sockets/kiwix.socket.ts`, `LiveContentClient.java`. Ticket ADFA-4832.
