# ADR-5343c — KNOWN damage from a force-cancelled restore (delta to ADR-5343)

**Status:** Implemented under K2GO-384 (restore Cancel slice); maintainer sign-off + device verification pending.
**Date:** 2026-09-04.
**Deciders:** the maintainer.
**Ticket:** K2GO-384 (standardize backup & restore: bar / % / ETA + **Cancel**), Epic ADFA-1028. Extends
**ADR-5343** (`controller/docs/ADR-5343-server-lifecycle-reconciler.md`) and its Phase-5 delta **ADR-5343b**
(`ADR-5343b-installguard-token-and-recovery-residue.md`, the InstallGuard session token / three-state marker).
Sibling to **ADR-5343a** (flap recovery). Everything in ADR-5343 / 5343b stands.

> Why this lives in K2GO-384 and not its own ticket: restore is a **data-touching** operation, so the lifecycle
> of the artifact it damages is part of the same deliverable. The reduction gate (ADR-5343 §8) binds: **no new
> source of truth, no new "who may act" special-case, no compensating flag.** This delta adds one *reading* on
> the existing marker and one *lever* on an existing owner — the counts do not go up.

---

## 1. The gap

The restore Cancel slice added an acknowledged **force-cancel during the destructive extract**: the user checks
"I understand this may leave my system damaged", we kill `tar -x` mid-write, and the rootfs is now half
overwritten. The destructive marker (`InstallGuard.begin`, planted at the verify→extract boundary) was left
**LIVE** — the same launch's token, never `end()`ed on the failed path.

A LIVE marker is the wrong fact for an *abandoned* write, and it produced three bad outcomes, all observed on
device:

1. **A fresh restore was blocked** with *"An install is in progress. Please wait for it to finish"*
   (`k2go_busy_install`). Source: `EnvironmentLock.currentHolder → Holder.INSTALL` because
   `InstallGuard.isLive` is true (`EnvironmentLock.java:216`). But nothing is installing — the op is over.
2. **No in-session recovery.** The deep-op terminal fell into the retry/bifurcation screen, not the damaged
   dialog. The in-session "damaged" path (`LibraryActivity` install observer, `:323-338`) only fires for the
   *install* repository, not a deep-op restore.
3. Recovery only arrived on a **relaunch** (token mismatch → INTERRUPTED → `LibraryActivity.recovering`), and a
   LIVE marker in-session held the box down as `Holder.INSTALL` (desired=DOWN), so the reconciler never reached
   the try-boot that the interrupted-install verdict needs — a deadlock until the process was restarted.

The user's decision: a force-cancel should present as **damaged** and route to the existing recovery, and must
**not** leave a blocking "install in progress" marker.

## 2. The distinction — inferred vs known damage

ADR-5343b/ADFA-5330 deliberately made an **INTERRUPTED** marker (a dead launch's token) fall through to
`rootfsPresent` in `SystemStateEvaluator.isSystemInstalled`, so the reconciler **tries** to boot the base: a
killed *module* install usually left a fine rootfs, and *whether it boots* is what separates a fine base from a
damaged one. Booting is the diagnostic.

A force-cancelled restore is different in one decisive way: **the damage is known, not inferred.** We tore the
rootfs ourselves, mid-extract. There is nothing to learn from booting it — trying would only flap the reconciler
(`desired=UP` every tick) on a base that cannot come up, which is precisely the "half-cooked" failure mode this
project avoids. So known damage must keep the box **down** and go straight to recovery.

That is exactly the case `ServerReconcile.desired`'s own invariant note anticipated:

> *"If a health signal that is NOT marker-derived is ever added (e.g. a structural rootfs check), this invariant
> must be revisited — desired would then have a reason to gate on health again."*

We honor it **without** re-adding a health gate to `desired` (see §4).

## 3. Decision — a fourth reading on the same marker

`InstallGuard` gains a sentinel token `"DAMAGED"` (never a per-launch UUID) and two members: `markDamaged(ctx)`
(overwrite the marker with the sentinel) and `isDamaged(ctx)` (token == sentinel). One marker, one new *reading* —
no second file, no new flag.

| Reading | Marker token | Meaning | `isLive` | `isInterrupted` | `isDamaged` | `isSystemInstalled` |
|---|---|---|---|---|---|---|
| ABSENT | (none) | nothing in progress | — | — | — | `rootfsPresent` |
| LIVE | this launch's UUID | an install runs now, this process | ✓ | — | — | **false** |
| INTERRUPTED | another launch's UUID | inferred damage (maybe fine) | — | ✓ | — | `rootfsPresent` (try-boot) |
| **DAMAGED** | `"DAMAGED"` sentinel | **known** damage (torn on purpose) | — | ✓ | ✓ | **false** (never boot) |

The design collapses to a single insight: **DAMAGED reads `isInterrupted` too**, so the entire recovery/verdict
path (`LibraryActivity.recovering :235`, `evaluateRecovery :917`, `SystemFactsReader.verdict :108`,
`SetupProgressActivity :186`) owns it with **zero changes**. The known-vs-inferred difference surfaces in exactly
**one** reader — `SystemStateEvaluator.isSystemInstalled`, which forces `false` for DAMAGED as it already does
for LIVE — and nowhere else. That single lever keeps `desired=DOWN` (no flap) while `isLive=false` lifts the
`k2go_busy_install` gate so a fresh restore is allowed.

The producer is one call site: `DeepOpService`'s extract `onError`. **Known damage is owned by "the extract
began and did not complete", not by the cancel button** — so the guard is `InstallGuard.isLive` (the marker was
planted at `onExtractStarting`, i.e. the rootfs was being written and is now torn), which covers *both* an
acknowledged force-cancel *and* a real mid-write failure (disk full, tar crash). Both leave a torn rootfs, so
both must drop out of the "install running" state and route to recovery. The `forced` flag only picks the
user-facing *message* (the "damaged, next launch recovers" line vs. the raw diagnostic).

### 3.1 Two cancel intents must not alias (the safe-zone / feeder split)

The restore has a **safe zone** (copy + verify + the verify→extract boundary, where an abort touches nothing)
and a **destructive zone** (the extract feeder, past the point of no return). These are two different cancel
intents and must be **two separate tokens**, or a "system unchanged" confirm can reach the feeder and tear the
rootfs across the boundary window:

- `cancelBeforeExtract` — a confirmed abort, read **only** in the safe zone (copy loop, verify listing, boundary
  check). Never read by the feeder.
- `forceExtractCancel` — an acknowledged destructive kill, read **only** by the extract feeder.

Both service→extractor tokens are passed explicitly to `TarExtractor.startExtraction`. And the service is the
**authority** on which zone it is in: `ACTION_CANCEL_CONFIRM` sets `cancelBeforeExtract` only while
`currentCancelKind == CANCELLABLE`, and `ACTION_FORCE_CANCEL` sets `forceExtractCancel` only while
`DESTRUCTIVE` — so a UI action that races a phase change (the fragment's `cancelKind` is a lagging copy) is
ignored by the service rather than misapplied. A confirm that arrives after the boundary simply lets the extract
finish; the system is never torn by a dialog that told the user it was safe.

### 3.2 A safe-zone cancel is a terminal of its own (CANCELLED)

A user cancel in the safe zone is **not a failure**. `DeepOpState` gains a `CANCELLED` phase (mirroring
`InstallState.Phase.CANCELLED`); the service posts it from a dedicated `finishCancelled()` terminal (release the
lock, re-enable desired, never touch InstallGuard — nothing was planted). The screen returns to the bifurcation
by branching on that phase, so the decision lives on the op's state and **survives a config change** — it is no
longer a fragment-local flag that a recreation would drop.

## 4. Why the reduction gate still holds

- **No new source of truth.** The one marker file remains the single durable fact. `isDamaged` is a *reading* of
  it, like `isLive`/`isInterrupted`.
- **No new "who may act" special-case.** Recovery ownership is unchanged: `LibraryActivity` still owns it, still
  keyed on `isInterrupted`.
- **The `desired` invariant is intact.** `desired` still does **not** read `healthy`. Known damage is expressed
  as `installed=false` — the argument `desired` already takes — via the same `isSystemInstalled` lever a LIVE
  install uses. We did not give `desired` a new reason to gate on health; we told the existing `installed` fact
  the truth (a rootfs we tore is not an installed system).
- **Prefer removing over adding:** the change *removes* a false state (a LIVE marker over a finished op) and
  replaces it with the honest one, rather than adding a flag to compensate for the false one.

## 5. Lifecycle (who sets it, who clears it, what if the process dies)

- **Sets DAMAGED:** `DeepOpService` extract `onError`, whenever `InstallGuard.isLive` (the destructive marker was
  planted at the boundary, so the rootfs was being written and is now torn) — covering both an acknowledged
  force-cancel and a real mid-write failure. A cancel *before* any write never planted a marker (`onCancelled`
  path) and never reaches here.
- **Clears it:** identical to INTERRUPTED. The recovery route's reinstall calls `InstallGuard.begin` (overwrites
  the sentinel with a live token, then `end()` on success); a base that unexpectedly boots clears it via the
  server-alive observer (`LibraryActivity:284`); an OK verdict clears it (`:936`). No new clearer.
- **Process dies mid-cancel:** if `markDamaged` already wrote, the sentinel persists → next launch reads
  DAMAGED (still `isInterrupted`) → recovery. If it died *before* `markDamaged`, the marker is still LIVE with a
  now-dead token → next launch reads INTERRUPTED → recovery. Either way recovery fires; there is no deadlock and
  no orphaned blocking state.

## 6. What changed

- `InstallGuard.java` — `DAMAGED_TOKEN` sentinel; `markDamaged(ctx)`; `isDamaged(ctx)`; class + `isInterrupted`
  javadoc note the fourth reading.
- `SystemStateEvaluator.isSystemInstalled` — force `false` on `isDamaged` (the single divergence).
- `deepop/DeepOpService.java` — extract `onError` marks DAMAGED whenever `isLive` (extract began and failed);
  a **separate** `forceExtractCancel` token for the feeder (de-aliased from `cancelBeforeExtract`, §3.1);
  `ACTION_CANCEL_CONFIRM` / `ACTION_FORCE_CANCEL` guarded on the service's own `currentCancelKind`; a `passRunning`
  guard so the re-callable verify+extract runs one pass at a time; a `finishCancelled()` terminal.
- `deepop/DeepOpState.java` + `DeepOpProgressRepository.java` — a `CANCELLED` phase + `cancelled()`/`postCancelled()`
  (§3.2), so a safe-zone cancel is a terminal that survives a config change.
- `TarExtractor.java` — `startExtraction` takes the third `forceCancelDuringExtract` token; only the feeder reads
  it (safe-zone tokens can never reach the destructive write).
- `redesign/BackupJobFragment.java` — the safe-zone cancel dialog is non-cancelable (closes the pause-hang
  lifecycle gap); the terminal branches on `CANCELLED` (the fragment-local `cancelling` flag is removed).
- `env/domain/ServerReconcile.java` — the invariant note records that this signal was added without breaking it.
- Terminal string `k2go_br_restore_damaged` = *"Restore cancelled. Your system will start in recovery on the next
  launch."* (states the consequence, not an accusation of damage).

## 7. Device verification (the real gate)

On the test device (arm64 + a 32-bit build for ABI coverage), force-cancel a restore during the extract pass and
confirm, without a relaunch:

1. The terminal shows the *damaged* message (not retry/bifurcation).
2. A fresh restore is **no longer blocked** by "An install is in progress".
3. The box does **not** flap (no repeated `pdsm start` on the torn rootfs in the reconciler log; `desired=DOWN`).
4. Returning to the library **or** relaunching lands on the damaged-recovery dialog; Recover → reinstall
   repairs the system.
5. Repeat with a force-cancel that races the very first extract write (boundary), and with a normal (non-forced)
   extract error, to confirm the non-forced path is unchanged.
