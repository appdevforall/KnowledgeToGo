# Environment Lock, Backup & Restore — Design

**Epic:** ADFA-1028 (Knowledge2Go)
**Tickets:** ADFA-4951 (EnvironmentLock, foundational) → blocks → ADFA-4952 (Backup & restore, one-step)
**Builds on:** ADFA-4842 (module install: stop the server before runroles, `ServerController.startEnvironment()` to boot after)

This document records the decisions and the *why* so the mechanism is not broken lightly during later work. Repo docs are in English (per project convention).

---

## 1. The problem

Several operations touch the Debian **rootfs and its services exclusively** — they cannot run at the same time or the rootfs is read/written by two writers → inconsistency / corruption. Today each guards itself independently, and only by asking **"is the server alive?"**:

- Module install — stops the server itself and runs `./runrole` in a proot (ADFA-4842).
- Backup / restore (old, `BackupController`) — *refuse if the server is alive*.
- Clone / share — `ShareController.startShareFlow()` refuses if the server is alive; **but the redesign `CloneFragment` has no gate at all and clones LIVE today** (verified: no `isServerAlive`/stop anywhere in it). Send reads the rootfs while services write it (inconsistent snapshot); receive overwrites the running rootfs (corruption).

**Why "is the server alive?" is not enough:** an operation that *stops* the server makes `alive = false`. Another operation then reads `alive = false` as "free to go" and starts — colliding with the first over the same rootfs. We need a **positive** signal ("an environment operation is in progress"), not the *absence* of the server.

---

## 2. The primitive: one app-wide `EnvironmentLock`

A single coordination lock that every deep-environment operation acquires before touching the rootfs/services and releases on a terminal state (success / failure / cancel).

- **Owner + since:** records which job holds it — `INSTALL | MODULE | BACKUP | RESTORE | CLONE` — and when it started.
- **Session-scoped coordination** (not damage recovery): the owner marker carries a token unique to the process launch. A marker left by a process that was later killed reads as *stale* and self-heals (clears) — after a kill no op is actually running, so the lock must never stay held forever. Recovering from *damage* left by an interrupted **write** op is a separate concern owned by the durable `InstallGuard` + its recovery. A write op sets **both** (EnvironmentLock for coordination, InstallGuard for damage); read-only ops (backup, clone-send) set only the lock.
- **`isHeld()` / `owner()`:** every deep-env op checks this **first** and refuses to start while it is held.
- Generalizes and replaces the fragmented `InstallGuard` (durable "install in progress") + `InstallJobs.isBusy()`.

The lock **owner governs the server** (stop-before / start-after), reusing the ADFA-4842 mechanism (`ServerController.startEnvironment()` — an unconditional boot, never the start/stop toggle). The server being down is a *consequence* of the lock, not the coordination signal.

---

## 3. Rules (best practices — do not break lightly)

1. **Single writer.** At most one owner at a time; all other deep-env ops refuse to *start* while the lock is held. (Planning/queueing to run in series is a future enhancement, not v1.)
2. **Ask the lock, not the server.** Ops check `EnvironmentLock.isHeld()`, never just `ServerStateRepository.alive`.
3. **The owner governs the server.** Stop-before / start-after via `startEnvironment()`; the app never relies on the alive heuristic to decide safety.
4. **Confirm at the action, not on entry.** Opening a screen is read-only and safe. Only pressing **Start** warns ("this stops the server and blocks other operations") and then acquires the lock + stops the server.
5. **The screen is the gate for hard ops** (see §4): while a write op runs, its screen traps the user (Back → background; reopening returns to it), like the module install index (`SetupProgressActivity`).
6. **Stale-marker self-heal + damage recovery are separate.** The coordination lock is session-scoped: a marker from a dead process is stale and cleared automatically (the lock never stays held after a kill). Damage from an interrupted **write** op (a half-applied restore/runrole) is recovered via the durable `InstallGuard` + its existing recovery — so write ops set **both**.
7. **REST content download is exempt.** ZIM/Books downloads run *on the live server* (in-process, no proot, no rootfs takeover) — they do **not** touch the lock and are never blocked by it.

---

## 4. Rigidity by read vs write (soft vs hard gate)

Not every op is equally rigid. It depends on whether it **reads** or **writes** the rootfs:

| Op | Touches rootfs | Server | Abort mid-run? | Gate |
|----|----------------|--------|----------------|------|
| Module install (runrole) | writes | stop → runroles → start | No — leaves a half-built system | **Hard** (screen-gate until done/recovery) |
| Restore | writes (overwrites) | stop → extract → start | No — damaged rootfs | **Hard** |
| Clone **receive** | writes (overwrites) | stop → pull → start | No — damaged rootfs | **Hard** |
| Backup | reads | stop → tar\|gzip → start | **Yes** — discard the partial, no local damage | **Soft** |
| Clone **send** | reads | stop → serve → start | **Yes** — resumable (rsync) | **Soft** |
| Content download (ZIM/Books) | live server, in-process | none | Yes | **None** (exempt) |

- **Soft gate (read-only: backup, clone-send):** rigid *while transferring*, but the user may stop/cancel with just a warning ("you'll stop the transfer"); on stop the server **auto-restarts** with a loading animation. No local damage — a partial backup is discarded; a partial clone-send resumes later. rsync makes clone **resilient/resumable**.
- **Hard gate (write: restore, clone-receive, module install):** cannot be abandoned mid-run without leaving a damaged rootfs, so the screen is a barrier until the op completes (or recovery runs). The server (re)starts only when done.

---

## 5. "Pretty wait" UX (do not turn this into walls)

The goal is coordination that *feels like waiting*, not a wall of "can't do that":

- When a deep-env op is attempted while the lock is held, show a clear, friendly state — *"K2Go is busy: <clone / backup / installation> in progress"* — with a way to reach the running op, **not** a dead error dialog.
- v1: inform + wait. Future: queue the requested op to run automatically when the lock frees (run-in-series, never in parallel).

---

## 6. Backup & Restore — one step each (ADFA-4952)

The old flow was two steps per direction (backup→internal, then export; import→internal, then restore) plus an internal-backup list. Simplify:

- **Back up → straight to an external file (SAF), streamed.** `tar | gzip` piped directly to the SAF `OutputStream` — **no internal temp copy**. A multi-GB rootfs must never need 2–3× space (50 GB stays 50, not 150). Server down for a **consistent snapshot**. Stamp the identity manifest (`origin=device-backup`, no checksum — the phone is not a builder).
- **Restore → straight from an external file (SAF).** **Validate before touching the rootfs** (`RootfsArchiveValidator`: right arch / valid rootfs / manifest; reject wrong-arch/corrupt/not-rootfs). Then extract over the rootfs (`TarExtractor`). Destructive; a mid-restore crash → damaged rootfs → existing recovery. Ends by booting the restored system (`startEnvironment()`).

Backup and restore are **the same thing in opposite directions** (rootfs ↔ archive) — much of the logic is symmetric and reusable.

**UI intro (step "zero"):** Settings → Advanced → **Backup & restore**, mirroring the **Clone** screen — title + description + "what do you want to do?" with **two cards** (Back up / Restore), icon + bold title + description. Replaces the current `SettingsUi.preview` placeholder.

**Corruption safeguards:**
- Backup consistency comes from the server being down (no writer during `tar`).
- A truncated/corrupt backup is caught **at restore time** by `RootfsArchiveValidator` (→ CORRUPT), so we don't need to police the partial external file (SAF makes deleting it awkward anyway).
- Restore validates *before* any destructive write; the durable lock + damaged-install recovery cover a mid-restore kill.
- The foreground `WatchdogService` holds CPU/Wi-Fi locks so Android's phantom-process killer doesn't kill the operation.

---

## 7. Reuse map (>50% already exists)

**Reuse as-is / re-host:**
- `BackupController` — `tar|gzip` backup pipe + identity-manifest stamping (add streaming to the SAF `OutputStream`).
- `RootfsArchiveValidator` + `RootfsManifest` — import/restore validation (arch/rootfs/manifest).
- `TarExtractor` — restore extraction (already used by install/reset).
- `ProcessRunner` — the shell pipe.
- `ServerController.startEnvironment()` + the stop-before pattern (ADFA-4842) — server lifecycle for all deep-env jobs.
- `SetupProgressActivity` — the screen-gate pattern for hard ops.
- `BackupNameResolver` — default export filename / collision (minor).

**To build:**
- `EnvironmentLock` (ADFA-4951): the durable, owner-aware lock; migrate module install, install/reset, and `CloneFragment` onto it.
- Fix `CloneFragment` to stop the server for a consistent rootfs (it clones live today), with a graceful stop + auto-restart.
- Backup/restore redesign UI (ADFA-4952), streaming backup, one-step restore.

---

## 8. Future / hardening (explicitly out of v1)

- **Resumable backup.** A `tar` stream is not resumable. True resume would need chunking + per-chunk hashing + verify, or a different archive format. Clone (rsync) already resumes; backup does not — for now a failed backup is simply re-run (restore-time validation rejects a truncated one).
- **Atomic-swap restore.** Extract to a staging rootfs and swap, so a mid-restore failure never leaves the live rootfs half-written (costs 2× space during restore). v1 does validate-then-extract-in-place + rely on recovery.
- **Job queue.** Plan several deep-env ops and run them automatically in series when the lock frees (never in parallel).
