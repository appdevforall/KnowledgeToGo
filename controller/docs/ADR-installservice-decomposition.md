# ADR: Decompose InstallService into layered slices

Status: Accepted
Ticket: K2GO-436
Date: 2026-09-30

## Context

`install/presentation/InstallService.java` is about 2070 lines and is the largest class in the
controller. It is a foreground `Service` that orchestrates the whole install, and it fuses many
concerns in one file:

- Service lifecycle and action dispatch (START, CANCEL, PAUSE, RESUME, USER_PRESENT, USER_ABSENT,
  START_MODULES, REBUILD_DASHBOARD).
- The rootfs install pipeline: download, extract, bootstrap, finish.
- The reset pipeline.
- The module queue (runrole installs), with queue persistence and retry.
- The module stall watchdog (K2GO-393).
- The download retry and backoff state machine (ADFA-5119): soft-fail, held window, resume.
- The maps install sub-flow.
- The dashboard rebuild.
- Cancel and abandon, with the abandoned-install marker.
- The notification and foreground UI.
- Hardware locks (wake lock, wifi lock).
- Persistence through SharedPreferences (installed tier, module queue, marker).
- Threading: many `volatile` fields, a main-looper handler, and an IO executor.

The class is a conflict hotspot and holds process control that runs as root inside proot. It is the
riskiest class in the app to change: a bad edit can brick an install.

Good news: the strangler is already partly underway. The Service leans on an `install.domain` package
of pure, unit-tested rules: `MapsRunroleCommand`, `RunroleProgress`, `AnsibleRunOutcome`,
`AbandonedInstall.Work`, `download.domain.Aria2Exit.Kind`, `env.Freshness`, and `deploy.domain.ModuleName`.
This ADR extends that pattern rather than starting a new structure.

This continues the god-class work from ADR-434 (SetupProgressActivity, done) and relates to ADR-5343
(the server-lifecycle reconciler, the single owner of "is the server up").

## Decision

Strangle InstallService incrementally, pure-domain-first, one slice per PR, behavior-preserving, with
JVM unit tests for each domain rule. Target layering: pure rules in `install.domain`, IO and persistence
in `install.data`, and the Service as a thin orchestrator in `install.presentation`.

Because this class controls processes as root, the order is conservative:

1. First extract pure decision rules that do not touch the process-control path.
2. Then extract the adapters that are safe to move (notification, persistence).
3. Then move the module-queue orchestration behind a narrow Host, like `SetupProgressController`.
4. Leave the rootfs and reset pipelines (proot, root, filesystem) for last, once the safer slices have
   shrunk the Service and the seams are proven on-device.

Each slice keeps the user-facing behavior identical and is verified by build plus the domain tests, and
the process-touching slices are device-smoked on real hardware before merge.

### Load-bearing guards (what must not regress)

- **One owner for process control and the abandoned-install marker.** The marker has a lifecycle (set
  at start, cleared on a clean finish, read by recovery in LibraryActivity per ADR-5343b). No slice may
  add a second place that writes it or a parallel "is an install running" flag. If a fact is needed in
  two places, read the one owner, do not copy it.
- **No new cross-invocation static mutable state.** The existing `sRetryMaps*` statics (maps config
  carried across a retry through the static `retryModules(...)` entry) are a smell this work should
  reduce, not copy. Prefer a retry-intent value object passed through the Intent.
- **A kill stays a single, generation-guarded action.** The hard-stall kill (K2GO-393) is one-shot per
  module run, superseded by a generation counter. An extracted stall policy may decide, but the kill
  itself stays in the Service and keeps the generation guard.

## Slice roadmap

Risk-ordered. Each is about one PR. The roadmap is a plan, not a contract: later slices get confirmed
against the code when their turn comes.

1. **domain/DownloadRetryPolicy (pure).** The soft-fail decision: given the stop kind, the attempts so
   far, and the attempt budget, return RETRY with a delay, HOLD (the user window), or FAIL through to
   recovery. Folds in `continuableAfter` and the attempt and delay indexing. `softFail()` becomes "ask
   the policy, then do the IO" (notify, post to the repository, schedule the retry). Reference-slice
   shape; safest and highest testability win.
2. **data/InstallStateStore.** One data source for the SharedPreferences reads and writes (installed
   tier, module queue, abandoned-install marker). `persist*` and `forget*` call it. Removes raw prefs
   from the Service.
3. **presentation/InstallNotifications.** The channel, the notification builder, the service-action
   PendingIntents, and the update call into a small helper. Low risk, trims about 120 lines.
4. **(optional) domain/ModuleStallPolicy.** A thin decision over `env.Freshness` for the soft and hard
   stall plus the generation supersession. Low value, because `Freshness` already carries the core; do
   only if it clarifies `startModuleStallWatch`.
5. **presentation/ModuleQueueController.** Move the module queue (runModuleQueue, installNextModule,
   revert, finish, retry) and the stall watch behind a narrow Host, as `SetupProgressController` did for
   the setup loop. Bigger and riskier; device-smoked.
6. **Rootfs and reset pipelines.** Last. proot, root, and filesystem control. Extract only after the
   safer slices have shrunk the Service and proven the seams.

## Consequences

- The install decision logic becomes unit-testable on a plain JVM, where today it has no tests.
- The Service shrinks toward an orchestrator that dispatches actions and wires the pieces.
- Merge conflicts on this hotspot get smaller, because new work lands in the feature packages.
- The risky process-control code is touched last and least, under device verification.

## Alternatives considered

- **Big-bang rewrite.** Rejected: too risky for root process control, and it stops feature work. The
  strangler keeps each step small and reversible.
- **Start with the pipeline (the biggest concern).** Rejected: the pipeline is the most dangerous code;
  starting there maximizes the chance of bricking an install. Pure rules first.
- **A DI framework to untangle wiring.** Out of scope; hand-wire per the project rule. Introducing
  Hilt/Dagger would be its own ADR.
