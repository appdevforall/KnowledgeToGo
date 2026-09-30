# ADR-434: Decompose SetupProgressActivity into layered slices

- Status: Accepted
- Date: 2026-09-30
- Owner ticket: K2GO-434
- Relates: ADR-5343 (server-lifecycle reconciler), ADR-5061 (REST vs proot operation model)
- Reference slice: rootfs (org.appdevforall.k2go.rootfs)

## Context

`SetupProgressActivity` ("Finishing setup") is a god class of about 1600 lines. It is the screen
shown after an install while the box provisions content. Over many tickets it has absorbed six
separate concerns:

1. Provisioning orchestration: the `readyPoll` loop plus `orchestrateStep()` and `nothingToStart()`
   run a serialized pipeline (proot stages exclusive first, then the REST streams ZIM, Books,
   Kolibri, and the Forgejo seed). It drains six provisioners in a fixed order and tracks start
   timeouts. It holds about fifteen boolean latch fields.
2. Run-session membership: `mapsInSession` / `moduleInSession` / `forgejoSeedInSession` /
   `rebuildInSession` latch "what work belongs to this run" from six sources (ModuleQueueRepository,
   the provisioners, ModuleBatch, ForgejoInstallPrefs, InstallProgressRepository, the wishlists).
3. Run verdict: the `allComplete` / `success` / `failure` / `failedTotal` block reads five or more
   stream states plus "server up" plus the queue phase. It is a rule, computed inline in `render()`.
4. Server-lifecycle coupling: the class implements `ServerController.Host`, owns a ServerController,
   sets desired=UP in `onModuleBatchTerminal()`, and reads ServerStateRepository in
   `serverObservedUp()`. This overlaps the reconciler (ADR-5343). It is where the Forgejo seed work
   hit a duplicate-truth risk on the question "is the server up".
5. Dashboard-rebuild sub-mode: a separate operation (`rebuildInSession` / `renderRebuild` plus a
   rebuild branch in `readyPoll` plus its own environment boot) shares this screen.
6. UI and detail navigation: `render()` plus hand-built row views, the status line, the redirect
   countdown, and `openDetail` / `backToIndex` / `configureDetailBar` (the fragment host and the
   per-detail Retry/Cancel/Back bar).

This size and coupling cause two concrete problems. First, the class is a merge hotspot: most
install and content features must touch it. Second, it is where duplicate-state bugs appear, because
a new "is X up / running / installed" fact is easy to add here as one more latch. The Forgejo seed
work (K2GO-417/422/423) is the recent example: the seed consumed "is the server up" and "is forgejo
up", and the safe fix was to keep those facts with their owners (the reconciler and the box service
healer), not to add a flag here.

## Decision

Break `SetupProgressActivity` up incrementally (strangler-fig) into the layered structure the
project already uses (Presentation depends on Domain; Domain depends on nothing; Data implements
Domain). No big-bang rewrite, no behavior change per step, and one migrator on this class at a time.

Target shape:

- Domain (pure JVM, unit-tested):
  - `RunVerdict`: given a snapshot of every stream state plus the server-up flag plus the queue
    phase, answer working / success / failure / failed-count.
  - `RunScope`: the "what work is in this run" latch rule, fed the durable sources each tick.
- Presentation:
  - `SetupProgressViewModel` (hand-wired factory, no DI framework): owns the orchestration loop and
    the RunScope, and publishes ONE observable `SetupUiState` (status line, rows, controls, verdict).
  - `SetupProgressActivity`: renders `SetupUiState` and forwards user actions (Finish, Cancel, Run
    in background, Retry). It no longer computes the verdict, drives the pipeline, or holds the latch
    fields.
- Data and other owners: unchanged. The provisioners, the services, and the repositories keep their
  jobs.

### The single-owner rule for "is it up" (the load-bearing guard)

The server lifecycle stays owned by the reconciler (ADR-5343). The ViewModel OBSERVES
ServerStateRepository for "is the box up"; it never keeps its own copy of that fact. When a module
batch ends and the box must come back, the ViewModel records the intent through the reconciler API
(desired=UP), not by poking Preferences and ServerController inline. Content-service health ("is
forgejo up", "is kiwix up") is owned by the box (the dash-node service healer), not by this screen.

This is the direct lesson of the Forgejo lifecycle work: when a fact about "is it up" is missing, the
fix is to reach the fact's owner, never to add a flag to this screen. Every slice below must preserve
this: no new "up / running / installed" boolean is introduced in the presentation layer.

## Slice roadmap

Each slice is about one PR, ordered by safety and value. Slices 1 and 2 are pure domain (no behavior
change, unit-tested) and set the pattern; slice 3 is the presentation pivot; slice 4 pays the
server-lifecycle duplicate-truth debt and is anchored to the upcoming server-lifecycle work; slices 5
and 6 carve off orthogonal concerns; slice 7 is an optional deeper simplification.

1. domain `RunVerdict`: extract the completion and success/failure rule from a `RunSnapshot`.
   Safest, zero behavior change, high test value.
2. domain `RunScope`: extract the `*InSession` latch rule. Removes about six fields and four methods
   from the Activity.
3. presentation `SetupProgressViewModel`: host the orchestration loop and RunScope; publish one
   `SetupUiState`. The Activity thins to render plus action-forward.
4. server-lifecycle guard: the ViewModel observes ServerStateRepository (one source);
   `onModuleBatchTerminal` routes desired=UP through the reconciler API. Removes the direct
   ServerController ownership on the module path.
5. split the dashboard-rebuild sub-mode into its own controller (or screen).
6. extract the detail host and the per-detail action bar into a `SetupDetailHost`.
7. optional, last: unify the provisioner pipeline (one ordered stage set, not N fixed branches in
   `orchestrateStep`).

## Consequences

Positive: the verdict and the run-scope rules become unit-testable on a plain JVM; the Activity stops
being a merge hotspot for install and content work; a new content type or a server-lifecycle change
no longer risks adding a duplicate "up" fact; each slice is small and reviewable.

Cost: more classes and one more wiring factory; the ViewModel introduction (slice 3) is the
higher-risk step because it moves the poll loop and the FragmentManager coupling. The slices land
over several PRs, so the class is in a mixed state between them; the one-migrator rule and small PRs
keep that window short.

## Alternatives considered

- Rewrite the screen at once: rejected. It is a merge hotspot and a live install path; a big-bang
  change is high risk and against the strangler policy.
- Leave it and only refactor by feature: partially kept. The refactor-by-feature default still
  holds, and the slices are anchored to the upcoming server-lifecycle work; this ADR only sequences
  the decomposition so the slices do not collide and so the single-owner rule is explicit.
- Introduce a DI framework (Hilt/Dagger) for the wiring: out of scope. Wiring is by hand per feature,
  as elsewhere; a DI framework is a separate ADR.
