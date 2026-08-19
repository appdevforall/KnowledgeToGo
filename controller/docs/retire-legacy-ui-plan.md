# Retire the legacy tabbed UI — removal plan

ADFA-5192 (under Epic ADFA-1028). Design / removal plan. 2026-08-19.

## Why

The app's live UI is the redesign: the launcher is `SplashActivity`, which routes
**only** to `redesign.LibraryActivity` (`SplashActivity.java:67`). `MainActivity` has
no launcher and no intent-filter (`AndroidManifest.xml`) — nothing opens its tabbed UI
as a screen. The legacy Status / Use / Install / Send pager
(`DashboardFragment`, `UsageFragment`, `DeployFragment`, `SyncFragment`) and the five
presentation controllers `DeployFragment` carved out are dead UI: reachable by no one.

`MainActivity` survives for one live reason only — it hosts the Debian **terminal** —
plus it is the declared home of two terminal `EXTRA_*` constants. This plan removes the
dead legacy UI and re-homes the terminal so `MainActivity` can go with it.

This is **pure dead-code removal**, not a behaviour change. The redesign install /
backup / content flows do not touch any of the deleted classes.

## Verified state (the facts this plan rests on)

- **Launcher.** `SplashActivity` (`AndroidManifest.xml`, `<activity>` with
  `LAUNCHER`) → `redesign.LibraryActivity` only (`SplashActivity.java:67`).
  `MainActivity` has no `LAUNCHER` / intent-filter.
- **Only two launches of `MainActivity`, both terminal-only.** Both pass
  `EXTRA_TERMINAL_ONLY = true`:
  - `TerminalSessionService.java:98-101`
  - `redesign/SettingsSubFragment.java:210-213`
  There is **no leak**. (An earlier roadmap note assumed a `TerminalSessionService`
  launch reached the full tabbed UI by omitting the terminal-only extra — verified
  false; both launches set it.)
- **The four fragments are legacy-only.** Their only constructor site is
  `MainPagerAdapter`. No redesign class references them.
- **The five controllers + five Hosts are legacy-only.** `InstallController`/`InstallHost`,
  `PlannerController`/`PlannerHost`, `ResetDeleteController`/`ResetDeleteHost`,
  `AdbShareController`/`AdbShareHost`, `BackupController`/`BackupHost` — all hosted by
  `DeployFragment`; no redesign reference.
- **The redesign reads no `MainActivity` instance state.** The only cross-boundary
  coupling is the two terminal `EXTRA_*` constants + `MainActivity.class` (for the
  terminal launch). `iiab_queue_prefs` / `is_module_state_trusted` is co-owned by
  `InstallService` (no migration needed). `WatchdogService` already points at
  `LibraryActivity`, not `MainActivity`.
- **The domain/service/repository layer stays.** The redesign install/backup path uses
  `InstallService`, `InstallProgressRepository`, `ModuleQueueRepository`, `InstallState`,
  `DownloadStateViewModel`, `BackupEngine`. None of these are legacy UI. **Keep them.**

## The 5144 connection

The companion install leg — `InstallService.startCompanionData()` (`:685`) →
`downloadAndIndexKiwix()` (`:707`) — fires only when `companion = true`
(`InstallService.java:318`, from `EXTRA_COMPANION`). That extra is set in exactly one
place: the legacy `InstallController.java:164`. This is the leg ADFA-5144 was opened to
fix (a failed ZIM download/index there routed to `finishSuccess()`, i.e. a false
SUCCESS).

Because the modern wizard already banks content honestly through the live path
(`addZimViaLiveChannel`, whose `onError` calls `fail(...)`), the dishonest leg is
reachable **only** through the legacy `InstallController`. Deleting the legacy UI
removes the only caller, so `startCompanionData` / `downloadAndIndexKiwix` /
`EXTRA_COMPANION` become dead code and are removed here. **ADFA-5144 is resolved by this
retirement** — there is no live dishonest terminal left to fix.

## Blockers

- **B1 — `MainActivity` builds the tabs unconditionally.** `MainActivity.onCreate`
  inflates `R.layout.main` (`:296`) and builds `MainPagerAdapter` (`:290`) even in
  terminal-only mode (the tabs are just hidden behind a black sheet at `:605-608`). So
  the fragments + controllers are still instantiated while `MainActivity` lives — they
  **cannot** be deleted until `MainActivity` stops referencing them. The terminal
  re-home and the deletion therefore ship **together** (see Phases).
- **B2 — the terminal is `MainActivity`'s only live use.** It must be re-homed before
  `MainActivity` can be deleted, without regressing how the terminal opens today
  (from redesign Settings and from the terminal notification).

## Plan

Two phases; ship them together (B1) or as a tightly-coupled pair.

### Phase B — re-home the terminal to a thin `TerminalActivity`

Extract the terminal into its own activity so nothing depends on `MainActivity`:

- New `TerminalActivity` implementing the terminal `Host` (only `addToLog` +
  `vibrateDevice` are used by the terminal path).
- Move into it: the terminal bottom-sheet + extra-keys subtree of `R.layout.main`, the
  `System.loadLibrary("termux")` block, and the two `EXTRA_*` terminal constants.
- Repoint the two callers at `TerminalActivity`:
  `TerminalSessionService.java:98-101` and `redesign/SettingsSubFragment.java:210-213`.

### Phase A — delete the legacy UI

- `MainPagerAdapter`; `DashboardFragment`, `UsageFragment`, `SyncFragment`,
  `DeployFragment`.
- The five controllers + five Hosts (listed above).
- `MainActivity` + its manifest `<activity>` entry (`AndroidManifest.xml`) +
  `R.layout.main`.
- The orphaned `tab_*` strings and any layouts left unreferenced after the fragments go.
- The dead companion install leg: `startCompanionData`, `downloadAndIndexKiwix`,
  `EXTRA_COMPANION` (and any now-unreferenced helpers they alone used).

**Keep:** `InstallService` and the install/backup domain + service + repository layer.

## Acceptance

- The terminal opens from redesign Settings and from the terminal notification exactly
  as before, hosted outside `MainActivity`.
- `MainActivity`, `MainPagerAdapter`, the four fragments, the five controllers + Hosts,
  the companion install leg, `R.layout.main` and the `tab_*` strings are gone.
- The build is green (no dangling references, no `MissingTranslation` from removed
  strings).
- No behaviour change to the redesign install / backup / content flows.

## Out of scope

- Any redesign refactor. This is deletion + one terminal re-home, nothing more.
- The install/backup domain layer (kept).
