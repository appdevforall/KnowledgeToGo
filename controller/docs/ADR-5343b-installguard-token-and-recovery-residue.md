# ADR-5343b — Phase 5: InstallGuard session token + delete the recovery residue (delta to ADR-5343)

**Status:** Approved for implementation (2026-08-31) — Fork 1 = **1A** (with the caller audit below gating the
code), Fork 2 = **out of scope** (no wedged-detection), Fork 3 = **shared process-launch identity**, Fork 4 =
**recorded in the device matrix**. Two sub-phases (5a token+collapse, 5b route-recovery+delete-residue), each
landed as a diff + commit message; Luis commits and runs device verification.
**Date:** 2026-08-31.
**Deciders:** Luis (sign-off required before any code).
**Ticket:** ADFA-5343 (Task under Epic ADFA-1028). Revises **ADR-5343** (`controller/docs/ADR-5343-server-lifecycle-reconciler.md`,
§7 Phase 5 + §5 the ADFA-5330 row); resolves the open bug **ADFA-5330** (ungraceful kill → stale marker → false
"Recover" / `DAMAGED_REINSTALL`). Sibling to **ADR-5343a** (flap recovery). Everything else in ADR-5343 stands.

> Method note. Produced read-only against `main` (Phases 0–4 merged). Every structural claim cites `File.java:line`.
> The reduction gate (ADR-5343 §8 / `CLAUDE.local.md`) binds: no new source of truth, no new "who may act"
> special-case, no compensating flag — Phase 5 must make the counts go **down**.

---

## 1. The bug (ADFA-5330), stated as a missing fact

`InstallGuard`'s durable marker (`.install_in_progress`, `InstallGuard.java:25`) is a **bare `File.exists()`**
(`InstallGuard.java:34`). It carries no identity, so after an ungraceful kill mid-install the next process
launch cannot tell **"a live install owns this"** from **"a dead process left this."**

The consequence is not just a mislabelled screen — it is a **boot deadlock that manufactures a false verdict**:
after a kill + reboot the durable marker is present, so

- `SystemStateEvaluator.isSystemInstalled → false` (`SystemStateEvaluator.java:49`), and
- `EnvironmentLock.currentHolder → Holder.INSTALL` (`EnvironmentLock.java:213`) → `desired = DOWN`
  (`ServerReconcile.desired`, `ServerLifecycleReconciler.java:150-151`).

So the reconciler **will not even try to boot the base**. The base therefore never answers, and
`LibraryActivity.evaluateRecovery()` reads a one-shot cached `alive` (`LibraryActivity.java:907`) inside a fixed
`GATE_SAFETY_MS` window → `InterruptedInstallDetector.DAMAGED_REINSTALL` (`:909`, `InterruptedInstallDetector.java:71`)
— **on a perfectly fine rootfs whose only sin was an interrupted *module* install.** A genuinely interrupted
*initial* install looks identical on the marker; only *whether the base actually boots* separates them, and today
nothing lets it try.

The missing fact is **process identity on the marker**. `EnvironmentLock` already solved exactly this for its
coordination lock (`SESSION` UUID per launch, stale marker self-heals, `EnvironmentLock.java:87-91,153-160`). We
reuse that primitive — but with one deliberate difference (§3), because `InstallGuard` also carries a *damage*
job that `EnvironmentLock` explicitly does not (`EnvironmentLock.java:22-30`).

---

## 2. Decision (piece 1) — a session token on the existing marker; three states, one file

`InstallGuard.begin()` writes **this process-launch's identity** into the marker. One marker, one new fact, no
second file. The marker now answers three states:

| State | Meaning | Test |
|-------|---------|------|
| **ABSENT** | no install | file missing |
| **LIVE** | an install is running *now, in this process* | present ∧ token == this launch |
| **ORPHANED** | the planter died; an install was interrupted | present ∧ token != this launch |

**Fork 3 (approved): one process-launch identity, shared.** Extract the per-launch UUID that `EnvironmentLock`
holds privately (`EnvironmentLock.java:91`) into a single tiny source (e.g. `env/ProcessSession.java`, pure, one
`static final String ID`) and have **both** `EnvironmentLock` and `InstallGuard` read it. One process has one
identity; two UUIDs answering "which launch am I" would be the duplicate truth the gate forbids.

**`begin()` must overwrite, not skip-if-exists.** Today it is `if (!f.exists()) createNewFile()`
(`InstallGuard.java:41`). A fresh install starting in a live process over an ORPHANED marker must **adopt** it
(rewrite with this launch's token). So `begin()` writes the token unconditionally; re-planting with the same token
is a no-op in effect (`SetupLibraryActivity.java:339` stays correct). All five planters already funnel through
`begin()` (`InstallService.java:317,345`; `CloneFragment.java:1340`; `DeepOpService.java:119`;
`SetupLibraryActivity.java:339`), so the token is centralized in one writer.

### 2.1 The collapse — `InterruptedInstallDetector.evaluate`: 5 signals → 2

Today the verdict takes five booleans and three of them (`installerRunning`, `moduleQueueRunning`,
`deepOpHoldsLock`, `InterruptedInstallDetector.java:62-66`) exist **only** to answer "is the marker's planter still
alive/legit" — and they reset to `false` on the very kill we care about (in-memory repos). The token answers that
from the marker itself:

```
evaluate(interrupted, serverReachable):        // interrupted == ORPHANED
    !interrupted           -> OK                // absent, or LIVE (work in progress ≠ damage)
    interrupted && reachable -> OK              // base boots -> stale marker over a fine system; caller clears
    interrupted && !reachable -> DAMAGED_REINSTALL
```

Three callers migrate: `SystemFactsReader.java:79`, `SystemVerdict.java:64`, `LibraryActivity.java:909`. The
scattered "restate the running-checks per caller" hazard the detector's own doc warns about
(`InterruptedInstallDetector.java:41-54`) is removed, not relocated.

### 2.2 The lifecycle — where InstallGuard must **differ** from EnvironmentLock (the delicate part)

`EnvironmentLock.ownerHeld()` self-heals a stale marker by **deleting** it (`EnvironmentLock.java:157-158`) —
correct for a *coordination* lock, because a killed op means no op is running, so the lock must not stay held.
**`InstallGuard` must NOT delete on staleness.** A killed *initial* install left a half-baked rootfs; deleting the
marker on read would silently declare it healthy and boot onto damage. So:

- The **coordination readers** (which today read `inProgress`) read **LIVE-only** and **do not delete** on ORPHANED
  — they stop *blocking* on a dead-process marker, but the marker stays so the verdict path can still see it was
  orphaned.
- ORPHANED is resolved by **outcome, not by deletion**: the reconciler is allowed to try `desired=UP`; base boots
  → `end()` clears it (the existing alive-observer already does this, `LibraryActivity.java:281`); base never boots
  → the DAMAGED verdict (§4) fires and `end()`/recover clears it.

Clean finish `end()` and reboot-adoption `begin()` are unchanged in spirit. **No self-heal-delete.**

---

## 3. Fork 1 audit (1A) — REQUIRED reading before 5a code

Fork 1 = 1A: an ORPHANED marker stops forcing `isSystemInstalled=false` / `Holder.INSTALL`; the verdict becomes
**"tried-and-failed."** This section is the gate the approval attached: (a) what `isInstalled` reads besides the
marker, (b) every executable `isSystemInstalled` caller, (c) the ORPHANED-outcome-unknown window — **including a
critical finding that 1A alone is insufficient.**

### 3.1 What `isInstalled` reads besides the marker — the initial-install safety net

`isSystemInstalled = !inProgress && rootfsPresent` (`SystemStateEvaluator.java:47-53`). Under 1A this becomes
`!isLive && rootfsPresent`. **The only behavior delta is: ORPHANED marker + rootfs present → now `true` (was
`false`).** The `rootfsPresent` conjunct is the safety net for the initial-install case:

| Kill point | rootfs on disk? | `isSystemInstalled` (1A) | Result |
|------------|-----------------|--------------------------|--------|
| initial install, **before** rootfs dir exists | no | **false** (unchanged) | → wizard; no boot attempt. Correct. |
| initial install, **after** rootfs dir (half-baked) | yes | **true** (delta) | reconciler tries → `pdsm start` fails → verdict DAMAGED. Correct. |
| module install over a healthy base | yes | **true** (delta) | reconciler tries → base boots → OK, marker cleared. **This is the fix.** |

A boot attempt on a half-baked rootfs is a `pdsm start` (a read of the tree), not an install write — harmless if it
fails. Flagged for device proof in §6 (Fork 4).

### 3.2 The eight executable callers (the other ~12 hits are comments/docs)

| # | Site | Uses `isSystemInstalled` for | ORPHANED delta (false→true) | Verdict |
|---|------|------------------------------|-----------------------------|---------|
| 1 | `SystemFactsReader.java:78` | `installed` → `desired` + display verdict | **the intended fix** — but see §3.3 (needs the `healthy` companion or it deadlocks) | **consequential** |
| 2 | `ModuleHubFragment.java:177` | which installed modules to list | reads modules off a possibly-incomplete rootfs, behind the recovery gate | low-risk display |
| 3 | `LibraryHomeFragment.java:470` | home header `systemInstalled` (also reads the shared `verdict` at `:474`) | header could read "installed" during recovery; gate is over Home | low-risk display |
| 4 | `LibraryActivity.java:216` | `systemInstalled` for the **else** power-on branch (`:411`) + gate timeout (`:425`) | **not reached during ORPHANED**: `recovering=true` routes to the `:377` branch, not the else | no impact |
| 5 | `LibraryActivity.java:1022` | `canStartServer` (gates Home Retry, `LibraryHomeFragment.java:149`) | Retry allowed during recovery → `setUserWantsOn(true)`+reconcile = same as recovery does | harmless/consistent |
| 6 | `CloneFragment.java:383` | `!installed` → force receive-only entry | fork offered instead of forced-receive; Send still blocked on no *usable* system | low-risk UX edge |
| 7 | `CloneFragment.java:407,1451` | `systemPresent` → Send empty-state | Send empty-state suppressed during a killed install | low-risk UX edge |
| 8 | `InstallService.java:344` | *(comment only)* | — | none |

**Conclusion:** exactly **one** consequential site (#1, the desired predicate). The six display/UX sites see
ORPHANED-only cosmetic changes, all behind the recovery gate/dialog, none unsafe. #6/#7 arguably *should* branch on
the shared `SystemFactsReader.verdict` / `isUsable()` rather than raw `isSystemInstalled` (that is the ADFA-5312
migration) — **noted, not pulled into Phase 5.**

### 3.3 The ORPHANED-outcome-unknown window — the critical finding: **1A alone deadlocks**

`desired = installed && healthy && userWantsOn && holder!=STOPPED` (`ServerReconcile.java:62-63`). Making
`installed=true` for ORPHANED is necessary but **not sufficient**, because `healthy` also gates `desired`, and
`healthy` comes from the very detector we are changing:

- ORPHANED → `interrupted=true`; on the early ticks the server is not up yet → `evaluate(interrupted=true,
  reachable=false)` → `DAMAGED` → `healthy=false` (`SystemFactsReader.java:79`).
- ⇒ `desired = true && **false** && … = DOWN` → the reconciler still won't boot. **The deadlock just moves from
  `installed` to `healthy`, and the false Recover survives.**

This is exactly the "tried-and-failed" semantics the approval named: **DAMAGED must be declared only *after* trying,
never *before*.** So the window resolution is:

> **The `healthy` fed to `desired` must treat an ORPHANED marker optimistically (as not-yet-damaged), so the
> reconciler is permitted to boot and *the try can happen*.** The `DAMAGED` verdict is a **separate, terminal
> readout** produced by the outcome — `evaluateRecovery` after the reconciler has had its retried chance, and the
> display `SystemVerdict`, which already holds `READY` until the first real server observation
> (`serverStateKnown`, `SystemVerdict.java:63`) so no false DAMAGED flashes mid-window.

Concretely, `SystemFactsReader.read` computes the `healthy` it feeds to `desired` from the **LIVE** state only (an
in-flight install in this process is not "damaged"), and treats ORPHANED as **healthy-optimistic** — the
"installed-but-damaged, don't run against it" state is the *outcome* (never boots), not the marker. The
display/recovery verdict keeps using the full `evaluate(interrupted, reachable)` rule, which is now *fair* because
the reconciler is genuinely retrying during the window.

**Residual to watch (device, §6):** with `healthy` optimistic, a genuinely damaged initial install leaves
`desired=UP`, so the reconciler retries `pdsm start` every tick forever while `evaluateRecovery` shows DAMAGED. To
stop the loop cleanly, **5b's `evaluateRecovery`, on declaring DAMAGED, sets `setUserWantsOn(false)`** (reusing the
existing intent — not a new state) so `desired→DOWN` and the retry ends; the recover/reinstall route then owns the
fix. This is the one net-new line of behavior beyond deletions, and it is subtraction-shaped (it *stops* work).

---

## 4. Decision (piece 2) — route recovery through `desired`, delete the residue

Piece 1 is what makes this clean: once an ORPHANED marker no longer forces `installed=false` / `Holder.INSTALL` /
`healthy=false`, `desired` can drive the base up on its own during recovery, so the recovery path's **own** boot is
redundant.

- **`LibraryActivity.java:377-395`** (the `recovering` branch): replace the `:392`
  `serverController.handleServerLaunchClick(...)` — the last boot that runs on ServerController's *own* `PRootEngine`,
  a parallel actuator — with `ServerLifecycleReconciler.setUserWantsOn(this, true)` (+ `requestReconcileNow()`), the
  same set-desired power-on gesture Phase 4 uses everywhere else. The alive-observer (`:276-283`) already clears the
  marker and lifts the gate on success; `evaluateRecovery` (`:903`) keeps the verdict but now reads a
  reconciler-retried outcome, and on DAMAGED sets `userWantsOn=false` (§3.3).
- **Delete** (all confirmed otherwise-dead once the toggle goes): `ServerController.handleServerLaunchClick` +
  its exclusive `timeoutRunnable`/`timeoutHandler` (`ServerController.java:89-90,324-370`); the `Host`
  `getTargetServerState`/`setTargetServerState` methods (interface `ServerController.java:51-52` + all three impls
  `LibraryActivity.java:1126-1127`, `SetupProgressActivity.java:1390-1391`, `SetupLibraryActivity.java:666-667`);
  `LibraryActivity.targetServerState` (`:68`) and its now-always-true `targetServerState == null` term in
  `canStartServer` (`:1007`).

**Explicitly NOT deleted (correction to the Phase-5 candidate list):** `doLaunchEnvironment`, `ensuring`,
`startEnvironment`, `stopEnvironment` are **still live** via `SetupProgressActivity.java:381,1238` (the STOPPED-proot
dashboard-rebuild `<1.2.0` bridge + its rollback) and `CloneFragment.java:1372`. Those belong to the
SetupProgressActivity rebuild-scaffolding collapse recorded in **ADR-5343a §11** (a later phase), not Phase-5
recovery residue.

---

## 5. Reduction scorecard (the acceptance gate)

| Metric | Before | After | Evidence |
|--------|-------:|------:|----------|
| Sources for "is an install live" | **4** (durable marker + 3 in-memory repos that must agree) | **1** (token on the marker) | `InterruptedInstallDetector.java:62-66`; the three repo reads |
| Signals into `InterruptedInstallDetector.evaluate` | **5** | **2** | `InterruptedInstallDetector.java:62` |
| Process-launch identity sources | **1** private to EnvironmentLock, 0 for the marker | **1 shared** (both read it) | `EnvironmentLock.java:91` |
| Un-routed boot-owners (boot the box outside `desired`) | **1** (`LibraryActivity:392` via ServerController's engine) | **0** | §4 |
| Deleted members | — | `handleServerLaunchClick` + 2 timeout fields; `get/setTargetServerState` ×4 sites; `targetServerState` field + its `canStartServer` term | §4 |
| Net-new behavior lines | — | **1** (DAMAGED → `userWantsOn=false`, subtraction-shaped) | §3.3 |

Every row goes down or holds; the one addition stops work rather than adding state. Gate satisfied.

---

## 6. Device verification (device-only — the real gate; Luis runs on `a026a310`)

Kills use `run-as org.iiab.controller` (plain `adb shell kill` is denied); launch the explicit main activity.

| Scenario | Expected | Guards |
|----------|----------|--------|
| Kill mid-**module** install (healthy base) → reboot | base boots via `desired`; marker cleared; **no false Recover** | ADFA-5330 core |
| Kill mid-**initial** install, rootfs dir half-baked → reboot | reconciler tries; `pdsm start` fails; **DAMAGED still caught**; retry loop stops after DAMAGED (`userWantsOn=false`) | §3.1, §3.3 (Fork 4: half-baked boot is a safe read) |
| Kill mid-**initial** install, no rootfs dir yet → reboot | `isSystemInstalled=false` → wizard, no boot attempt | §3.1 |
| Normal install → success | boots once via `desired`; no regression | Phase 4 |
| Recovery path regression (rootfs present, base fine but slow) | boots on retry; no premature DAMAGED; gate lifts | §3.3 window |
| ORPHANED window UI (mid-recovery, server not up yet) | screens show READY/starting, **no DAMAGED flash** | `SystemVerdict.java:63` `serverStateKnown` guard |
| Turn-off / normal boot / clone / backup / restore | unchanged | §4 (residue deletion is behavior-neutral for these) |

---

## 7. Sub-phasing (each = diff + proposed commit message; Luis commits + device-verifies)

- **5a — token + collapse.** `env/ProcessSession` (shared identity); `InstallGuard` token + tri-state
  (`isLive`/`isInterrupted`, no self-heal-delete); `InterruptedInstallDetector.evaluate` 5→2; migrate the coordination
  readers to LIVE-only (`SystemStateEvaluator`, `EnvironmentLock.currentHolder`) and the verdict/`healthy` path per
  §3.3. Pure/JVM-testable off device (`SESSION` mismatch discriminator, the 2-arg verdict, the optimistic-window
  `healthy`). **First gate:** `:app:testDebugUnitTest` + `:app:lintDebug` green.
- **5b — route recovery + delete residue.** Rework the `recovering` branch to set-desired; `evaluateRecovery`
  reads the retried outcome and sets `userWantsOn=false` on DAMAGED; delete `handleServerLaunchClick` +
  `targetServerState` plumbing (NOT `doLaunchEnvironment`/`ensuring` — ADR-5343a §11). **First gate:** compile +
  lint + the existing recovery unit tests.

**No production code until Luis approves this note and the §3.3 window resolution.** Stop at each gate (note →
5a diff → device-verify → 5b diff → device-verify).
