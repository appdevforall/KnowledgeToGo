# ADR-5343a - Flap auto-recovery: correcting the Phase-2 actuation (delta to ADR-5343)

**Status:** Approved (2026-08-29) - D1 + D2 approved for implementation (including the two deviations, §9); D3 deferred to Phase 4 (recorded, not pulled forward).
**Date:** 2026-08-29
**Deciders:** Luis (sign-off required).
**Ticket:** ADFA-5343 (Task under Epic ADFA-1028). Revises **ADR-5343** (`controller/docs/ADR-5343-server-lifecycle-reconciler.md`); resolves the open bug **ADFA-5336** whose Phase-2-v1 implementation regressed.
**Scope of this delta:** it revises exactly three things in ADR-5343 - the �2.6 grace (concretizes it), the �5 collapse row for 5336, and the �7 Phase-2 migration row. Everything else in ADR-5343 stands.

> Method note. Produced read-only against Phase-2 commit `1f5cb0f6`, with the mechanism re-confirmed on device `a026a310` (OnePlus7T). Every structural claim cites `File.java:line`. The reduction gate (ADR-5343 �8 / `CLAUDE.local.md`) still binds: this delta must not add a state, flag, source of truth, or "who may act" special-case.

---

## 1. What Phase-2 device verification found

ADR-5343's reasoning layer verified correct on every flow (one liveness source, the holder-execution-class `desired` predicate, monitoractuator split). But the **key** flow - flap auto-recovery (ADFA-5336) - **regressed**: Phase-2 actuation turns a *self-healing* service flap into an *unrecoverable* loop.

**A/B evidence (same box, same induction `kill <dash-node pid>`, `/k2go-api`=502 while nginx still serves `/home`):**

| Build | Result |
|-------|--------|
| `ACTUATES=true` (Phase 2 v1) | Reconciler re-drives correctly, but the box **never returns to UP** - it loops `KILL_AND_RELAUNCH` every ~24 s (proots 16255164371649616550.), `actual=STARTING` for 70 s+, endpoints degrade to `000`. |
| `ACTUATES=false` (rollback, = pre-Phase-2 behavior) | **pdsm respawns dash-node in ~3 s, same proot; box fine.** No relaunch, no loop. |

So Phase-2 actuation is **worse than log-only** for a mid-life flap. The reconciler's *reasoning* is not at fault; the *boot mechanism it delegates to* is.

## 2. Mechanism - device-confirmed (this delta hinges on it; it is not different from the finding)

Re-confirmed on `a026a310` by freezing the reconciler (background the app  `ServerController.onPause` stops the poll, `ServerController.java:122-127`) and issuing `kill -9 <proot>` - exactly what `killOrphan` does (`android.os.Process.killProcess`, `env/EnvironmentProcess.java:192`):

```
# healthy: nginx master is already reparented to init
18572     1 nginx: master process nginx        # PPID=1
18526 18467 libproot.so                         # proot, child of the app
netstat: 0.0.0.0:8085 LISTEN 18572/nginx: master process nginx

# after kill -9 18526 (the proot):
18572     1 nginx: master process nginx        # SURVIVES, still PPID=1
netstat: 0.0.0.0:8085 LISTEN 18572/nginx: master process nginx   # STILL owns :8085
home:000  api:000                               # orphan holds the port but no longer serves
```

**Two independent defects:**

- **D1 - the escalation clock is proot-age, not service-downtime.** `EnvironmentEnsure.decide(...)` escalates to `KILL_AND_RELAUNCH` when `envAlive && !servicesAlive && envAgeMs >= bootGraceMs` (`env/domain/EnvironmentEnsure.java:66-69`), with `bootGraceMs = BOOT_GRACE_MS = 20_000` (`ServerController.java:41`). On a **mature** proot `envAgeMs` is already � 20 s, so the *first* ensure-up tick after any transient service death escalates immediately (device: `KILL_AND_RELAUNCH . age 134229ms` at ~t+2 s) - **before** pdsm's ~3 s respawn. The grace guards the *initial* boot (the ADFA-5103 3.5 s double-boot) but gives a mid-life flap **no** window at all.
- **D2 - `killOrphan` does not reclaim the orphaned services.** It SIGKILLs only the proot pid (`env/EnvironmentProcess.java:183-199`). nginx (and node) are **reparented to PID 1** and survive, still holding `:8085` (netstat above). Every relaunched proot's `pdsm start` then cannot rebind  services never answer  infinite loop. This is a correctness bug **independent of D1**: even a legitimately-stuck orphan cannot be recovered by the current relaunch.
- **D3 (secondary, orthogonal) - a second un-gated boot owner.** `LibraryActivity`'s launch auto-start calls `handleServerLaunchClick` gated on `(systemInstalled && !alive)`, **not** on `desired` (`redesign/LibraryActivity.java:414-425`, boot at `:422`). Device: after a user turn-off (`userWantsOn=false`, `desired=DOWN`) a relaunch re-booted the box and flipped `userWantsOn=true`. It did **not** cause the flap loop; it is the two-owners tension ADR-5343 �4 already names.

ADR-5343 **anticipated D1's shape** (�2.6 "progress-aware grace, not a fixed 20 s"; �6 risk "killing a healthy-but-slow boot . the exact 5336/flap regression, in reverse") but Phase-2 v1 shipped with the fixed `envAgeMs` grace still in force via unchanged `EnvironmentEnsure`. ADR-5343 **did not anticipate D2** - �5's 5336 row assumed the relaunch works.

## 3. Decision (approved fix direction - encode this, do not redesign)

**Fix D1 - one clock: service-downtime, held in the single `ServerLiveness` source (concretizes ADR-5343 �2.6).**
The escalation grace is measured from **how long `servicesAnswering` has been false while `processPresent` stays true**, not from proot age. `ServerLiveness` already carries `processPresent` / `servicesAnswering` / `observedAtMs` (`env/domain/ServerLiveness.java:63-65`); it gains **one derived field**, `servicesDownSinceMs`, threaded across consecutive snapshots by the single owner (which already holds `lastLiveness`, `ServerLifecycleReconciler.java:68`):

```
servicesDownSinceMs(prev, now, probes) =
    servicesAnswering             0
  | processPresent && prev>0      prev            (still down since prev)
  | processPresent                now             (just went down)
  | else                          0               (proot gone  DOWN  LAUNCH, not KILL)
```

`EnvironmentEnsure.decide` then escalates on **`servicesDownMs >= serviceDownGraceMs`** instead of `envAgeMs >= bootGraceMs`. One clock subsumes both cases it must cover:
- **Initial boot:** services have been down since the proot started  the grace still protects the 3.5 s double-boot (ADFA-5103).
- **Mid-life flap:** the clock resets when the service drops  WAIT lets pdsm respawn (~3 s); escalate only if it stays down past `serviceDownGraceMs` (~pdsm respawn + margin, a small constant to tune on device).

This **replaces** the fixed `BOOT_GRACE_MS` guess and **drops `envAgeMs` from the decision** - a strict reduction, and it realizes �2.6 without parsing the pdsm service-line stream.

**Fix D2 - `killOrphan` must reclaim the orphaned services so a legitimate relaunch can rebind.**
A correctness fix to the one existing actuator path, adding no new state. The requirement: after `killOrphan`, nothing of ours holds `:8085`. Candidate mechanisms (implementation-time, device-verified, not decided here): launch the proot in its own **process group** and signal the group; set `PR_SET_PDEATHSIG` on the service tree; or have `killOrphan` additionally terminate the box's service processes it can identify as ours. Whichever lands must be proven by the re-run (flow 2 rebinds `:8085` and reaches UP).

**Fix D3 - record now, gate in Phase 4 (do not pull forward).**
`LibraryActivity:422` is exactly the scaffolding ADR-5343 �7 Phase 4 deletes ("replace the toggle; delete the . re-boot loops"). Gating it on `desired` now would be an out-of-phase change touching a legacy god-class flow that the flap fix does not require - against `CLAUDE.local.md`'s "one phase at a time." **Decision: defer to Phase 4; record the device-observed behavior here so Phase 4 addresses it deliberately** (it is the last second-owner of "boot the box"). Tracked, not patched.

## 4. Reduction re-check (the hard gate)

| Fix | States / flags / sources | Verdict |
|-----|--------------------------|---------|
| D1 | Removes fixed `BOOT_GRACE_MS` (a guess flag ADR-5343 �8 already counts for removal) and drops `envAgeMs` from `decide`; adds **one derived field** to the **existing** single `ServerLiveness` source - no new source, no external flag. | **Reduces / neutral** |
| D2 | Correctness fix to the one actuator (`killOrphan`); no new state. | **Neutral** |
| D3 | Deferred; nothing added now; slated for deletion in Phase 4. | **Neutral now, reduces later** |

No new source of truth, no new "who may act" special-case, no compensating flag. Gate satisfied.

## 5. Revised collapse-table row for ADFA-5336 (supersedes ADR-5343 �5's 5336 row)

> **ADFA-5336** - post-install / mid-life server **flap**  stuck (v1: **unrecoverable relaunch loop**). **Subsumed by the reconciler's `UPSTARTING` re-drive (ADR-5343 �2.1), corrected by:** (D1) the re-drive WAITs on a **service-downtime** grace so a transient dash-node death lets pdsm self-heal (~3 s), escalating to relaunch only past that grace - the escalation clock is service-downtime, not proot-age; (D2) `killOrphan` reclaims the orphaned services (the reparented nginx holding `:8085`) so a legitimate relaunch can rebind. Both are required: without D1 the reconciler preempts pdsm; without D2 its own relaunch cannot recover. Device-confirmed that the rollback (`ACTUATES=false`) self-heals in ~3 s, isolating the defect to these two.

## 6. Revised migration note for ADR-5343 �7 (Phase 2)

Phase 2 is **re-opened** to include D1 + D2 before actuation is considered done (the ADR-5343 �7 rollback lever - `ACTUATES=false` - stays the escape hatch and is already device-proven to be safe/self-healing). No later phase is pulled forward. First gate unchanged: `:app:testDebugUnitTest` + `:app:lintDebug` green, with the pure decision (`EnvironmentEnsure` + the new `ServerLiveness.servicesDownSinceMs` reducer) JVM-tested off device.

## 7. Post-approval re-verification (device-only, `a026a310`)

- **Flow 2 (flap):** kill dash-node on a mature proot  box **auto-recovers to `actual=UP` with no manual toggle**; log shows WAIT during the service-down grace, and if it escalates, the relaunch **rebinds `:8085`** (netstat shows the new proot's nginx, not an orphan). Must pass.
- **Flow 3 (timeout):** a real module-batch hand-off with `/k2go-api` kept down >45 s  `SetupProgressActivity` "taking longer" + Finish appears, reconciler keeps re-driving, Finish lands on a Home it keeps driven - no dead Home.
- Re-run the rest of the Phase-2 matrix to confirm no regression (flows 1, 4, 5, 6).

## 8. For approval

1. Approve D1 (service-downtime grace in `ServerLiveness`, dropping `envAgeMs` from `decide`) and D2 (`killOrphan` reclaims orphaned services)?
2. Approve **deferring D3** (LibraryActivity:422 desired-gating) to Phase 4, recorded here?
3. Land this as **ADR-5343a** (this file), or fold it into ADR-5343 as a revision section? (No production code until this is signed off.)

**Resolution (2026-08-29):** D1 + D2 approved for implementation, including the two deviations recorded in §9. D3 deferred to Phase 4 (recorded, not pulled forward). Landed as ADR-5343a (this file).

---

## 9. Known compensator: the host-side nginx reap (D2) — retire when the guest kills its own services

D2's `EnvironmentProcess.reapEnvironmentHttpFront()` (`env/EnvironmentProcess.java:225-253`) is a **compensator**, accepted knowingly so the flap fix can land now. It is not the clean end-state.

**Root cause it papers over (guest-side).** The box's HTTP front daemonises inside the proot: nginx `setsid()`s and reparents to init, so it **survives the proot's death** and keeps `:8085` (device evidence, §2). The clean end-state is **guest-side — the environment's services should die with the proot** (e.g. the runrole/`pdsm` teardown or a `PR_SET_PDEATHSIG`-style parent-death signal on the service tree, so no service outlives the container). With that, a relaunched proot's `pdsm start` rebinds with nothing to reclaim, and D2's host-side reap becomes unnecessary.

**The two deviations this compensator carries (to retire together with it):**
1. **Host-side reclamation of a guest concern.** The app reaches into `/proc` and SIGKILLs the box's own service processes — work that belongs inside the guest. It is only *safe* here because those processes share the app's uid and SELinux domain (device-verified, §2), which is a property we should not lean on long-term.
2. **Imprecise identity.** It matches `cmdline.contains("nginx")` — a broad substring, host-side, unlike the precise rootfs-tail `EnvironmentProcessMatcher` used to find the proot — and it reaps **nginx only** (the listener holding `:8085`), not the full guest service tree. `/proc/net/tcp` → pid is unavailable (blocked since API 29), so socket-scoped reaping is not an option from the app; the guest-side fix is what removes the need for any of this.

**Disposition.** Keep D2 as-is now (required: without it a legitimate relaunch cannot rebind `:8085`, §5). **Retire it when the guest-side service-lifetime fix lands** — at which point `reapEnvironmentHttpFront()` and the `ENV_HTTP_PORT` constant come out and `killOrphan` returns to signalling the proot alone. Tracked here; not a Phase-4 blocker, but the natural companion to the rootfs/runrole work that owns guest teardown.

---

## 10. Corrected root cause (device testing) and the in-proot recovery direction

Post-D1/D2 device testing surfaced that a **wedged content service does not recover after an environment relaunch** (Kiwix "Unavailable"; the network-dashboard tiles stuck on "connecting"), and traced it to a single root cause broader than §9's nginx case.

**Root cause — an orphan off proot loses proot's syscall emulation.** proot traces the box's processes and emulates syscalls the host kernel does not serve. When a service is reparented to init by a relaunch (it `setsid`s away and outlives the proot), it runs **without proot's emulation**: its `epoll_wait` hits the bare host kernel and returns **ENOSYS (38)**, so it malfunctions. This one defect is behind three device-observed symptoms: kiwix-serve **hangs** (`:3000` accepting but never serving; curl → 000), nginx **crashes** (`epoll_wait() failed (38)`), and php-fpm **busy-loops logging the error** (~1.3 GB/min → filled `/data`).

The `epoll_wait` failures reported "kernel 6.17.0" — that is **proot's *spoofed* kernel version** (proot presents an invented version to escape the phone kernel's restrictions), not the device's real kernel (~4.x). The kernel version is a **red herring**; the defect is the loss of proot tracing on orphaning, not any kernel release.

**Direction (decided) — recover in place, in one proot, via the dashboard REST core; do not relaunch proots to fix a wedged service.** Recovery of a content service that is wedged **while the proot is alive** (dash-node / `k2go-api` still answering) must be an **in-proot `pdsm restart <svc>`** issued through the dashboard REST core (`static/dashboard` — dash-node, which already execs per-service in-proot and already calls `pdsm restart dash-node`, `routes.ts:283`). Because the service is never detached from the proot, the orphan → ENOSYS class **cannot arise**. This **supersedes** "extend the host-side reap to more services" (which would grow a compensator that chases the whole service tree): the app must not hunt or kill the box's services at all.

**Layering (one owner per fact).**
- *Android app / reconciler* owns box up/down (the proot and dash-node liveness). It does **not** manage or reap individual box services.
- *Dashboard REST core (in-proot)* owns its own service tree. **Auto-heal is server-side** in dash-node — an in-proot watcher HEAD-probes the content services and restarts a present-but-wedged one via `pdsm restart <svc>` in the **same** proot (works with no browser open, which "the box heals itself" requires). The restart endpoint is **loopback-only** (behind `/k2go-api`'s `allow 127.0.0.1; deny all`), so captive-portal clients on the hotspot can *detect* a down tile but **cannot** trigger a restart — the served page only reflects status. The manual **Retry** backstop is the on-box Android module card (loopback, owned by the merged **ADFA-4842**), out of scope for the dashboard change; the endpoint is ready for it. Probe classification splits **absent** (404 → not installed, left alone) from **down** (5xx / timeout / refused → heal), so a box without a service is never restart-looped.
- *pdsm* is the per-service mechanism (already complete: `enable/start/stop/restart`).

**Consequence for D2.** The host-side reap (§9) shrinks to the narrow **genuine proot-death** case only (nginx orphaning while holding `:8085`); the common **wedged-service-on-a-live-proot** case moves to the in-proot REST restart and never orphans. D2 is still retired with the guest-side service-lifetime fix (§9).

**Scope split.**
- *Here (this effort):* `static/dashboard` — a per-service `pdsm restart <svc>` for content-service recovery, auto-healed on a detected-down service, Retry as backstop. **The watcher wires `kiwix` only today** (the device-confirmed wedge); the other supported services are added to `WATCHED` **one at a time as each is device-verified** — the restart endpoint already accepts the full supported set.

  **Supported services (source of truth — do not invent).** The authoritative list is
  `pdsm_installed_services` in `iiab/iiab` `roles/proot_services/defaults/main.yml`. As of today:
  **`nginx`, `php-fpm`, `mariadb`, `kolibri`, `kiwix`, `calibre-web`** (only `nginx` enabled by
  default). This list **lives upstream in `iiab/iiab` and will grow** — keep an eye on that role
  rather than hard-coding a fixed set; the restart capability should accept a service name from the
  supported set, not a baked-in enum. **`dash-node` is the exception:** it is the k2go-side service
  (it lives in this repo, `static/dashboard`), not in `iiab/iiab`.
- *Separate, upstream `iiab/iiab` (via `tools/upstream-patches`):* a php-fpm guard so it cannot busy-loop-log on `epoll_wait` ENOSYS and fill the disk — defense-in-depth, and far less likely once services stop orphaning. Its own ticket.
- *Non-issue:* the "kernel 6.17" — proot's spoofed version; no action, recorded so no one chases it again.

**Device-verified status (dash-node 1.2.10, `a026a310`).** *All three confirmed.* (1) Clean-kill
auto-heal — kiwix-serve killed → watcher probes `down` → `pdsm restart kiwix` → `/kiwix/` back to 200
in ~17 s, zero manual action. (2) Loopback-only restart — LAN POST to the restart endpoint is refused
while `/kiwix/` still serves the LAN, so a captive-portal client sees the tile but cannot trigger a
restart. (3) **Orphan-reclaim** — a controlled env-relaunch orphaned kiwix off proot (`:3000` wedged,
`/kiwix/`=000); the watcher fired and `pdsm restart kiwix` **reclaimed the orphaned instance** → 200 in
~40 s, no manual action. So `pdsm restart` recovers a wedged/orphaned service, not just a cleanly
killed one.

**Live residual (elevated by test 3) — php-fpm orphan disk-fill.** The same relaunch orphans
**php-fpm**, which is **not** in the heal watcher's `WATCHED` set: orphaned, it busy-loop-logs
`epoll_wait` ENOSYS (~1.3 GB/min) and fills the disk. Test 3 stayed safe only because php-fpm was
**manually stopped first**; in production nothing pre-stops it, so a real env-relaunch is still a
disk-fill risk. **Primary fix: extend the heal watcher to php-fpm** — add it to `WATCHED` with a
php-backed probe, device-verified; the same in-proot pattern both auto-heals php-fpm and removes the
disk-eater at the source (a fresh php-fpm under the new proot does not busy-loop). The upstream
php-fpm log guard becomes defense-in-depth, not the primary fix. Then generalise `WATCHED` to the
other daemonised services (kolibri, mariadb, calibre-web), one at a time, device-verified. Not a
blocker for Phase 3, but the disk-fill severity makes it the next dashboard follow-up.

