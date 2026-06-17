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
- **Legacy seam:** `InstallationPlanner.resolveOsSizeGb()` routes the OS size
  through the use case (live-then-fallback) instead of the old hardcoded
  `OS_*_GB` constants. Migrating `DeployFragment`'s projection UI to consume
  `RootfsViewModel` directly is the next strangler step for this area.

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
- **Connectivity gating:** live network calls on the projection path can stall
  up to the timeout when offline; prefer an explicit connectivity check before
  the live attempt as this path is migrated.
