# CLAUDE.md — Project guardrails for iiab-android

## Language

All **code, comments and documentation** are written in **English**, to
standardize across contributors and users worldwide.

Conversations / interactions with a contributor may happen in their **native
language** (e.g. Spanish). Reason in the contributor's language if helpful, but
every committed artifact ships in English.

---

## Architecture: layered design is mandatory for new code

The app started as a POC and has **no layering** today (UI, business and data
logic are mixed in large fragments/activities). We are migrating to a layered
(Clean Architecture) design: **Presentation → Domain ← Data**.

Golden rule — dependency direction always points **inward**:

- **Presentation** (Fragments/Activities, `ViewModel`, view state, formatting)
  depends on Domain.
- **Domain** (entities, use cases, repository **interfaces**) depends on
  **nothing**. No `android.*`, no `androidx.*`, no `java.net`, no HTTP, no JSON
  framework. It must be unit-testable on a plain JVM.
- **Data** (repository **implementations**, remote/local data sources, DTOs,
  catalogs, caches) depends on Domain and implements its interfaces.

Hard requirements for any new or migrated code:

1. **Do not** put Android or networking dependencies in the Domain layer.
2. New features live in their own feature package, split by layer
   (`<feature>/domain`, `<feature>/data`, `<feature>/presentation`).
3. Business rules (validation, fallbacks, "what counts as valid") belong in
   **use cases**, not in the UI or the data source.
4. The UI observes state from a `ViewModel`; it does not fetch or format data
   itself.
5. No DI framework is required yet — wire dependencies by hand (constructors /
   a small factory). Introducing Hilt/Dagger is a separate, explicit decision
   (write an ADR first).

---

## Strangler-fig migration policy (do not stop feature work)

We do **not** freeze development for a big-bang rewrite. We strangle the legacy
incrementally:

- **New code** is written in the layered architecture from day one.
- **Legacy code** is migrated **when we touch it** — the boy-scout rule: leave
  the code better than you found it. Each fix/feature that touches a god class
  should peel a small, well-defined slice into the new structure.
- Prefer **small seams**: connect new layered code to the legacy through one or
  two narrow call sites rather than rewriting a whole screen at once.
- A specific module **may** be frozen briefly while it is being migrated (to
  avoid changes landing on top of a half-done migration). This is different from
  a global freeze.
- Before migrating old code, confirm it is in scope for the current task. No
  unsolicited mass refactors.
- **Refactor-by-feature is the default:** every time we add or change a feature,
  we land it in the layered structure *and* peel the legacy code it touches into
  that structure in the same change. Feature work and refactoring advance together,
  in step with the phases in `controller/docs/TECH_DEBT_PLAN.md` — never as a
  separate "we'll clean it up later" task.

---

## Working in parallel (coordinating features on one repo)

We often have more than one feature in flight at once on this single repo. To keep
two layered migrations from colliding, follow these rules:

- **One feature = one package = one branch/PR.** Each feature owns
  `org.iiab.controller.<feature>/{domain,data,presentation}`. Code inside a feature
  package is private to that feature, so two features in different packages almost
  never produce merge conflicts. Keep new code there, not in shared classes.
- **Know the conflict hotspots** — the files many features must touch: the legacy
  god classes (`MainActivity`, `DeployFragment`), `InstallationPlanner`, anything
  in `util/`, `build.gradle`, `AndroidManifest.xml`, and `res/values/strings.xml`.
  Edits to these must be **additive and minimal**: add a new overload/method/string
  rather than changing an existing signature; don't reformat or reorder surrounding
  code; keep the diff as small as the change allows.
- **One migrator per legacy class at a time.** Two branches must not both carve up
  the same god class in parallel. If feature B needs a class that feature A is
  actively migrating, B coordinates with A or waits — use the brief module freeze
  from the strangler policy, and record who is migrating what in the Design map
  below (it doubles as the coordination ledger).
- **Shared contracts land first.** If two features need a common domain type or
  port, define and merge that small interface on its own first, then both features
  build against it. Don't duplicate it on two branches.
- **Per-feature resource files** to avoid `strings.xml` collisions: a feature may
  add its own `res/values/strings_<feature>.xml` (Android merges all `<resources>`
  files) instead of everyone editing the one shared `strings.xml`. Append, never
  reorder existing keys.
- **Wire dependencies by hand, per feature.** Each feature has its own
  `…ViewModelFactory` / small factory. There is no shared DI graph for everyone to
  edit (introducing Hilt/Dagger is a separate ADR), so composition roots don't
  become a contention point.
- **Integrate often.** Keep branches short-lived; rebase on `main` frequently and
  merge small. `main` moves under you — small, frequent integration keeps conflicts
  tiny. Don't sit on a long-lived branch that touches a hotspot.
- **No new shared mutable static state.** It couples features logically even when
  the files don't conflict; prefer state encapsulated in a `ViewModel`.

---

## Design map (keep this updated as we advance)

> Update this section whenever a slice is migrated, a layer is added, or a god
> class shrinks. It is the living picture of how far the layering has spread.

**Reference slice (DONE) — rootfs size (`org.iiab.controller.rootfs`)**
First feature built across all three layers; use it as the copy-paste template.

- `domain/` — `Rootfs` (entity), `RootfsTier`, `RootfsAbi`,
  `RootfsRepository` (port), `GetRootfsSizeUseCase` (validation + fallback rule).
  Pure JVM, unit-tested (`src/test/.../rootfs/domain`, `.../util`).
- `data/` — `RootfsRemoteDataSource` (HTTP read of the `latest_*.meta4`
  `<size>`, in-memory cache, returns `-1` on failure), `RootfsCatalog` (URL
  building + hardcoded fallback bytes + ABI detection), `RootfsRepositoryImpl`.
- `presentation/` — `RootfsViewModel` + `RootfsUiState` + `RootfsViewModelFactory`.
- `util/ByteFormatter` — shared, pure byte→human formatting.
- **Presentation wired (DONE):** `DeployFragment`'s projection UI now consumes
  `RootfsViewModel` directly — it observes the use-case result (live-then-fallback)
  and feeds the resolved OS size into the projection. `InstallationPlanner`
  exposes a `calculateProjectedSize(..., osSizeGb, ...)` overload for this UI path,
  so OS-size resolution lives in the presentation layer rather than the planner.
- **Legacy seam (retained):** `InstallationPlanner.resolveOsSizeGb()` still backs
  the older `calculateProjectedSize(...)` overload used by the non-UI install flow
  (which only needs the resolved companion-data filename). Removing it once the
  install flow stops depending on it is a future strangler step.

**Slice (DONE) — device architecture (`org.iiab.controller.deviceinfo`)**
Carved out while fixing the dashboard "device architecture" field (it showed the
app's ABI, so a 32-bit app on a 64-bit phone reported 32-bit).

- `domain/` — `DeviceAbiProvider` (port) + `GetDeviceArchUseCase` (rule:
  prefer the device's primary 64-bit ABI, else 32-bit, else generic). Pure JVM,
  unit-tested (`GetDeviceArchUseCaseTest`).
- `data/` — `BuildDeviceAbiProvider` reads `Build.SUPPORTED_*_ABIS` (device-level,
  not the app process), so it reports the real hardware arch.
- **Legacy seam:** `DashboardFragment` resolves the device-panel arch through this
  use case; `getTermuxArch()` (app/content ABI) is unchanged for modules, termux
  and debian arch.

**Legacy (NOT yet layered)** — most of `org.iiab.controller` is still flat:
god classes `MainActivity` and `DeployFragment` (~2.7k LOC), shared mutable
state on public/static fields, hand-rolled `HttpURLConnection` calls duplicated
across classes, inline size formatting.

See `ROOTFS_SIZE_PILOT_ANALYSIS.md` (repo root) for the detailed change map and
live-size data behind the reference slice.

---

## Tech-debt watch list (controller) — opportunistic targets

Evident debt noticed while building the pilot. Chip away at these **only when
you are already in the file** (boy-scout), and record progress in the design map:

- **God classes:** `DeployFragment` (~2.7k LOC) and `MainActivity` mix UI, IO,
  process control and networking. Extract cohesive slices into feature packages.
- **Shared mutable state:** public/`static` fields used as cross-class state
  (e.g. download flags). Prefer encapsulated state in a `ViewModel`.
- **Duplicated networking:** `HttpURLConnection` is reimplemented in
  `InstallationPlanner`, `DeployFragment`, `MainActivity`. Consolidate behind
  data sources / a small HTTP helper as features migrate.
- **Inline formatting:** byte/size strings are formatted ad hoc in several
  places. Route them through `util/ByteFormatter`.
- **Thin tests:** only pure static logic is covered. Every migrated slice must
  add JVM unit tests for its domain/use-case layer (no emulator needed).
- **Connectivity gating (DONE for the rootfs path):** `DeployFragment` now keeps a
  `hasInternet` flag (from `checkInternetAccess()`); when offline it skips the live
  fetch (use-case `attemptLive=false`) to avoid the timeout stall, disables the
  install button ("No connection"), and shows an "Estimated sizes (offline)"
  caption. Apply the same pattern to other live-network paths as they migrate.
