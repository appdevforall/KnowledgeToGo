# ADFA-5146 — Busy-flag expiry + naming the holder (design)

State-spine finding 2, the release blocker. Design agreed 15 Aug (brainstorming). No code
until this is approved.

## Problem

`EnvironmentLock.isBusyNow()` reads four in-memory sources, three of them judged by
`hasSession() && !isComplete()`. A service killed before it reaches a terminal state — the
Android phantom-process killer does exactly this — leaves that pair true for the life of the
process. `isHeld()` then reports the environment busy and every deep operation refuses with one
message, "An install is already running", while nothing is running. The only exit is to
force-stop the app, which nobody does — a hidden exit, not a hang.

## The mechanism (verified against the code, not the ticket)

`EnvironmentLock.isHeld(ctx)` is an OR of three facts:

- `isBusyNow()` — the four in-memory sources (the stuck-prone part).
- the durable install guard (`.install_in_progress`).
- `ownerHeld` — a file + session token that self-heals on the next read.

`isBusyNow()` is reached by consumers only through `isHeld()`. `InstallJobs.isBusy()` is a
dead delegate (no callers). Six deep-op gates call `isHeld()` and refuse with
`k2go_install_busy`:

- `BackupJobFragment:152`, `CloneFragment:453`, `DashboardRebuild:57`,
  `MapsConfirmFragment:95`, `ModuleDetailFragment:158`, `ModuleHubFragment:136`.

`LibraryHomeFragment:290` shows the same string but guards on `ownerHeld` (self-healing), so it
is **not** part of this family. `LibraryActivity`/`InstallService` use `InstallState.isHeld()`,
a different fact (the durable install-run state).

## The four sources are not symmetric

- **ZIM / Books / Kolibri** are content downloads that run on the live server and are **exempt
  from the lock** — they hold no install guard and no owner marker. Their only contribution to
  `isHeld()` is `isBusyNow()`. So for them the in-memory flag is the *only* stuck fact, and
  expiring it is the complete fix.
- **The module queue** is different: `InstallService` takes the **durable install guard**
  (`InstallGuard.begin`) for the whole runrole. A killed module install is dominated by that
  durable marker, whose lifecycle is teardown / re-derivation from disk / the damaged-system
  diagnosis (ADFA-5147) — not `isBusyNow()`. Ansible is also the authority on its own terminal
  (exit code / PLAY RECAP), and a long runrole (calibreweb, kolibri) is legitimately silent for
  minutes, so output cadence is not a safe liveness signal for it.

**Decision (Option 2):** ADFA-5146 expires the three content-download sources. It does **not**
put a heartbeat on `ModuleQueueRepository`; the module case belongs to the install guard's
lifecycle (ADFA-5147-adjacent). See "Known limit" below.

## Approach — last-progress heartbeat (not a start timer)

A start timestamp plus a fixed cap would condemn a legitimately long download — the same trap
ADFA-5155 avoided for the receive exit. The safe signal is **last progress**, not elapsed time.

Each content source gains an in-memory `volatile long lastProgressAtMs`:

- **seeded** when the session starts (so a new session is never stale on arrival), and
- **refreshed on every poll tick** — not only when the percentage changes. Freshness means "the
  poll ran", not "the % moved", so a live-but-stalled download (server working, % flat) stays
  fresh and keeps blocking; only a dead poll loop goes cold.

`isBusyNow()` becomes the OR of `source.isActiveNow()`, where:

```
isActiveNow() = hasSession() && !isComplete() && Freshness.fresh(lastProgressAtMs, STALE_MS)
```

`Freshness.fresh(lastAt, threshold)` is a pure helper (one definition of the compare).
`STALE_MS ≈ 30_000` — one shared constant; the three sources share the ~1s poll cadence.

Per source:

| Source | New field | Refreshed at | Seed |
|---|---|---|---|
| `ZimDownloadService` | `sLastProgressAt` | where it already updates `sPercent`/`sSpeed` (each poll) | on session start |
| `BooksDownloadService` | `sLastProgressAt` | each `POLL_MS` loop turn | on session start |
| `KolibriSeedRepository` | `lastProgressAtMs` | in `itemProgress()` | at `startedAtMs` |

`isBusyNow()`'s signature is unchanged, so the six gates benefit without being touched for the
expiry.

## Naming the holder

`EnvironmentLock.currentHolder(ctx)` returns `{CLONE, BACKUP, RESTORE, INSTALL, DOWNLOAD, NONE}`,
evaluated in the same order and from the same sources as `isHeld()` — no third definition of
"unfinished work":

1. `ownerHeld` → its `Owner` (CLONE / BACKUP / RESTORE),
2. the durable install guard → INSTALL,
3. `isBusyNow()` (post-expiry, so only live sessions count) → DOWNLOAD.

A single UI helper maps the holder to a localized string. The six gates become: `isHeld()` →
`currentHolder()` → the matching message. One definition of "who holds" (in `EnvironmentLock`),
one of "which string" (the UI helper).

**Strings:** five complete per-holder sentences — `k2go_busy_clone`, `k2go_busy_backup`,
`k2go_busy_restore`, `k2go_busy_install`, `k2go_busy_download` — not a `"%1$s is in progress"`
template, which breaks on gender/agreement across the 33 locales. Shipped in all 33 locales
(MissingTranslation is a hard lint error). The old `k2go_install_busy` is retired from these six
sites.

Because `currentHolder` is post-expiry, the message never says "a download is in progress" when
there is no live download — message and unblock are consistent by construction.

## Cleanup

Remove the dead `InstallJobs.isBusy()` delegate (no callers) — one less place that knows
`isBusyNow`.

## Known limit (documented, not omitted)

Within a single session, a module runrole killed without a relaunch leaves both
`ModuleQueueRepository` and the durable guard stuck; ADFA-5146 does not clear it. Cross-session
is covered by the guard + ADFA-5147 (damaged diagnosis on the next launch). The belt-and-braces
fix is `Process.isAlive()` on the `InstallService` runrole — a separate piece, not this ticket.

## Out of scope / follow-ups

- **Durability across process death** — the durable per-session record — remains ADFA-4897.
- **Module-install detail** — Ansible has no timestamps or per-task timing in our current log;
  `ANSIBLE_LOG_PATH` (tail its mtime / last lines) or `profile_tasks` would give real detail and
  a process-liveness signal for the module queue. Its own ticket.
- The `ServiceReadyGate` shared poll utility (index / 5155 / 5158) is tracked separately.
- **Unified download stall policy (rootfs ↔ content).** The rootfs download already has a
  developed liveness policy inside `Aria2Manager` (owns the aria2c process, parses bytes/s: a
  ~10s stall watchdog, three retries, then a held Retry/Cancel that falls to recovery after
  ~60s). Content downloads run on the in-server REST engine (POST + poll), so that mechanism
  can't be reused as-is — but the high-level concern is identical, and `Freshness` here is the
  substrate-agnostic version of it. Converging both onto one liveness policy (stall threshold +
  retry + held window, each implemented over its own substrate) is a follow-up, sibling to the
  `ServiceReadyGate` unification. Not this ticket.

## Acceptance

- A content-download service killed mid-session stops blocking deep operations once its heartbeat
  is older than `STALE_MS`, without the app being closed.
- A download genuinely in flight (poll alive, even at a flat %) still blocks.
- A finished session never blocks.
- The refusal message names the operation that actually holds the environment.

## Testing

`Freshness.fresh` is pure → direct JVM unit tests. Each source's `isActiveNow()` is tested by
injecting `lastProgressAtMs` (live / dead / terminal). `currentHolder` priority order is
unit-tested against the three facts.

## References

`env/EnvironmentLock.java` (isBusyNow / isHeld / currentOwner / Owner) ·
`ZimDownloadService` · `BooksDownloadService` · `kolibri/presentation/KolibriSeedRepository` ·
the six gates above · `install/presentation/ModuleQueueRepository` + `InstallService`
(InstallGuard) · state-spine finding 2 · ADFA-5147, ADFA-4897, ADFA-5155 · ADFA-1028.

## Implementation plan

Branch `fix/ADFA-5146-busy-flag-expiry`. Cohesive enough for one PR; if a split is preferred,
cut it at expiry (1–4, 7) then message + cleanup (5–6).

1. **`env/Freshness.java`** — pure `static boolean fresh(long lastAtMs, long thresholdMs)`
   (`lastAtMs > 0 && now - lastAtMs <= thresholdMs`) + JVM unit test. Define `STALE_MS = 30_000`
   here (or on `EnvironmentLock`), one shared constant.
2. **Heartbeat in the three content sources.** Add `volatile long lastProgressAt`, seed on
   session start, refresh on **every poll tick** (not only on % change), and expose
   `isActiveNow() = hasSession() && !isComplete() && Freshness.fresh(lastProgressAt, STALE_MS)`.
   - `ZimDownloadService` (`sLastProgressAt`, bump at the status poll).
   - `BooksDownloadService` (`sLastProgressAt`, bump each `POLL_MS` turn).
   - `KolibriSeedRepository` (`lastProgressAtMs`, seed at `startedAtMs`, bump in `itemProgress`;
     verify it is called each job poll, else add a per-poll bump).
3. **Rewire `isBusyNow()`** to OR the three `isActiveNow()`; leave `ModuleQueueRepository.isRunning()`
   unchanged (Option 2).
4. **`EnvironmentLock.currentHolder(ctx)`** → `{CLONE, BACKUP, RESTORE, INSTALL, DOWNLOAD, NONE}`,
   priority `ownerHeld → install guard → isBusyNow`; unit-test the order.
5. **Message.** Five strings `k2go_busy_{clone,backup,restore,install,download}` (base + 33
   locales) + a UI helper `holder → stringRes`; replace `k2go_install_busy` at the six gates.
6. **Cleanup.** Remove the dead `InstallJobs.isBusy()`.
7. **Tests.** `Freshness`; each source's `isActiveNow()` (inject live / dead / terminal
   timestamps); `currentHolder` priority.
8. **Verify.** Build + lint (MissingTranslation on the five new strings × 33). Device test: kill a
   content-download service mid-session → a deep op unblocks after ~`STALE_MS` and its refusal
   (when a real holder exists) names the right operation; a live download still blocks; a finished
   one never does.
