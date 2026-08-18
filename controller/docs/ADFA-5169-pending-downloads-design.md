# ADFA-5169 — Pending downloads: see and cancel queued content orders

State-spine finding 6 (DEAD CONTROL). Design doc. 2026-08-17.

## Problem

A content order (ZIM, Books, Courses) is *banked* when it has been placed but not
yet drained — the drain is deferred while the system is not ready to run it. In that
state two things fail today:

- It is invisible. The Home header shows "See progress" only while something is
  actually running (`PendingContent.anyRunning()`, sessions only). The per-card
  action sheet's `isScheduled()` reads only the maps and module wishlists, never the
  three content ones.
- It cannot be cancelled. The only cancel in the app is the module sheet's, and it
  clears only the maps and module wishlists.

So a queued ZIM/Books/Courses order sits with no way to see it and no way to back
out of it.

## What a banked order actually is

All banked orders are legitimate user requests. The real axis is not "banked vs
legitimate" but "can the current system still drain it?":

- Healthy system: the Home pump drains on the next poll (~3s), so the order is
  transient — or it waits legitimately behind another live download (streams
  serialize). This waiting window is where visibility and manual cancel earn their
  keep.
- No / damaged system: the order can't drain; the real problem is the system
  (recover / install), the order is secondary.
- System replaced or lost: the order is orphaned and is already purged in bulk by
  `PendingWork.clearAll` on setup / replacement / invalidation.

Consequence for this design: never auto-cancel a legitimate order, and never purge
by age. Cancel is a manual "I changed my mind." Auto-purge stays limited to the
system-gone case, which already exists.

## Scope (decided in brainstorming)

In scope:

- A **Settings row**, always visible ("Pending downloads"), showing a count when
  there is something queued and "No pending downloads" when empty. It never pushes;
  the user goes when they want.
- A minimal **Pending downloads** screen that lists each queued order **by item**
  (this Wikipedia collection, this book, this course channel) with its size and its
  own Cancel. Manual only.
- Cancel removes that one order; the rest stay.
- If a download is actually running, a link to the live index
  (`SetupProgressActivity`). The pending screen itself stays about what is queued.
- Only the three live content types: **ZIM, Books, Courses**.

Out of scope (explicit):

- Maps and module orders — they already have a cancel path; leaving them avoids a
  second place that cancels the same thing.
- Controls for *running* tasks (retry / pause / resume / cancel) — that is the
  download-contract family (ADFA-4894 / 4896 / 4899, and the surface built with
  ADFA-5119).
- The durable task registry and history — the larger, deliberately deferred design
  this is a first slice of.
- Any change to auto-purge.

## North star (not built here)

The eventual model is two surfaces: an **index** = the live, self-cleaning view of
what is happening now (never shows anything stuck); and a **registry** = the durable
record of everything the index ever showed, plus the pending orders that need a
decision. This screen is the seed of the registry's "pending decision" bucket. The
invariant that keeps the two from blurring: nothing stuck ever lives in the index;
the moment a task can't proceed it belongs to the pending surface. Building the full
registry (a first-class Task entity, durable across process death — overlapping
ADFA-4897 / 4874) is a separate effort.

## Design (layered, its own feature package)

`org.iiab.controller.pending`, following the reference slice pattern.

### domain/ (pure JVM, no Android)

- `PendingOrder` — an entity: `ContentType type`, `String id`, `String name`,
  `long bytes`. One queued order.
- `PendingOrdersRepository` — port: `List<PendingOrder> list()`,
  `void cancel(PendingOrder order)`.

### data/

- `PendingOrdersRepositoryImpl` — reads the three content wishlists (ZIM, Books,
  Courses) into `PendingOrder`s and cancels via per-item removal. It is the single
  place that maps a type to its wishlist for listing and cancel, sitting alongside
  the knowledge `PendingContent` / `ContentType` already own — no second copy.
- One new wishlist method: `ZimWishlist.remove(id)`. Books (`remove(id)`) and Kolibri
  (`remove(channelId)`) already have per-item removal; ZIM has only `clear` today.

### presentation/

- `PendingOrdersViewModel` + `PendingOrdersUiState` + `PendingOrdersViewModelFactory`,
  wired by hand (no DI).
- The screen (`PendingOrdersFragment` or Activity), Material 3: semantic colour
  tokens, type scale, 4dp grid, `MaterialAlertDialog` / snackbar via
  `SnackbarDuration`. It observes the ViewModel; it does not read or format the
  wishlists itself.
- The Settings row — an additive edit to `SettingsFragment` / `SettingsUi`, the one
  touch to a shared hotspot, kept minimal. The link to the live index reuses
  `SetupProgressActivity`.

### Data flow

Open → ViewModel loads `list()` from the wishlists → render, or the empty state →
Cancel on a row → `cancel(order)` removes that item → reload → the row disappears,
with a snackbar. No automatic action anywhere.

## Testing

- Domain: `PendingOrder` and any pure rule (ordering / grouping) get JVM unit tests.
- Data: the wishlist read / per-item cancel is Android (SharedPreferences), verified
  on device.
- New user-facing strings ship in all 33 locales in the same PR (a missing
  translation fails the build).

## Validation (and why the "natural" repro does not exist)

The durably-banked state is not reachable through normal UX, which shapes how this is
validated. Confirmed in code:

- Live mode (Get More, with a system): `SetupLibraryActivity.startZimDownload()` /
  `startBooksDownload()` drain immediately (`…Provisioner.drain`), so content never
  stays banked there — it starts at once (or moves into the download service).
- Wizard mode (pre-install): `zimWizardConfirm()` / `booksWizardConfirm()` bank the
  selection without draining, but the wizard has no route to Settings, and completing
  the install lets the Home pump drain the banked content.
- A systemless device opens the wizard, not the tabs, so Settings is unreachable there.

So the states that keep an order banked (no system, or the in-server REST engine down
while nginx still answers) are incompatible with reaching the Settings screen that
shows it. The only durable case — rootfs present but the REST engine down, so the Home
pump's `apiReady()` gate never opens — requires stopping just that engine inside the
container and is not a user path.

Validation is therefore:

1. Automated, deterministic — an instrumented test (`PendingOrdersRepositoryImplTest`,
   src/androidTest) seeds the three wishlists with real SharedPreferences and asserts
   the banked mechanism: the orders are listed grouped by type with the right names,
   the empty state, and per-item cancel (cancelling one removes only that order —
   including a ZIM cancel leaving the other ZIM, which exercises the new
   `ZimWishlist.remove`). Run with `./gradlew :app:connectedDebugAndroidTest`.
2. The UI end to end — the list, per-item cancel, nothing cancelled automatically, and
   the Settings count refreshing on entry and on return — via a debug seed that writes
   the same banked state (`DebugSeedPendingReceiver`, src/debug); stay off Home so the
   pump does not drain it. Same kind of stand-in as the ADFA-5146 stale flag.
3. That real banking occurs through the UI is confirmed by selecting content in the
   wizard and inspecting the wishlist:
   `adb shell run-as org.iiab.controller cat shared_prefs/k2go_zim_wishlist.xml`.
4. The "See progress" link is validated with a real running download; the maps/module
   cancel paths are checked directly for regressions.

Not left pending: a continuous natural flow to a durably-banked order reached from
Settings does not exist by construction; it is covered by (1) + (2).

## Seams / reuse

- Reuse `ContentType` + `PendingContent`; no duplicated wishlist knowledge.
- One new low-level method: `ZimWishlist.remove(id)`.
- Settings row is additive.
- The live-index link reuses `SetupProgressActivity`.

## Acceptance

- A queued content order is visible when nothing is running.
- A queued ZIM, Books or Courses order can be cancelled from the UI, and cancelling
  leaves the rest untouched and removes nothing it should not.
- Maps and module orders keep their current behavior.
- Nothing is cancelled or purged automatically by this feature.
