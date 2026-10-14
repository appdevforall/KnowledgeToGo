# ADR: contract for long-running module operations

Status: Accepted (K2GO-463)

## Context

The app gains a module, then another, then another. Each new module needs the
same machinery: a long-running operation (install, update, download, seed,
rebuild, clone) that shows progress, survives the app going to the background,
lets the user return to the right screen, and sometimes lets the user cancel.

Historically that machinery accreted into a few central classes. `InstallService`
(about 2.1k lines) mixes proot, networking, persistence, JSON and threading.
`SetupProgressActivity` (about 1.6k lines) fuses the progress UI with provisioning
orchestration. Every new module added another branch to one of these, so they
grew without bound. This is the God-class failure mode the project fights.

The same absence of a single owner produced a family of identifier bugs:

- K2GO-458: four foreground services shared notification id 7; two shared id 8.
- K2GO-461: every return PendingIntent used request code 0, so a tap opened the
  wrong screen.
- K2GO-462: a notification channel was never created, so its notifications never
  showed.

Three tickets on one concern is the signal this ADR answers: stop patching, state
the structure.

## Decision

A long-running operation is a small, self-contained unit that COMPOSES shared
pieces. It is never a new branch inside a central class.

A new operation is built from these parts:

- Its own foreground service, or a subclass of `ContentDownloadServiceBase` for a
  content download. The service owns only its own operation.
- A notification id taken from `NotificationIds`. The build fails
  (`NotificationIdsTest`) if two ids match.
- A PendingIntent request code taken from `NotificationRequestCodes`:
  `Return.*` for the tap-to-return intent (these must be unique, enforced by
  `NotificationRequestCodesTest`), `ServiceAction.*` for a notification action
  that drives the service.
- Its own notification channel, created before the first `notify()`.
- Progress as a dependency-free domain type (the `RebuildProgress` /
  `ExtractProgress` pattern), unit-tested on a plain JVM.
- Return routing through `OpReturnNavigator`, the single owner of "which screen
  does this operation open".
- Retry through `DownloadRetryPolicy` where a transfer can fail.

Rule: adding a module means adding its own operation unit from these parts and
registering its id. It does not mean adding a case to `InstallService` or
`SetupProgressActivity`. Those two shrink over time by peeling slices out to this
shape (K2GO-436, K2GO-449).

## Consequences

- Identifier collisions become impossible to merge: a duplicate id or return code
  fails a JVM test at build time, not a manual smoke test on a device.
- A document guides; code enforces. The registry and the base class already force
  the path (you take an id from one place; you extend a base that gives you
  progress and cancel). As each central class is decomposed, more of this contract
  becomes an abstract base that a new operation MUST implement to exist. The move
  is incremental, by the refactor-by-feature rule, not a big-bang rewrite.
- Request codes keep two families on purpose. A return code opens a shared
  Activity and must be globally unique. A service-action code targets its own
  service component, so the same value on two services does not collide; it is not
  forced unique, and the holder says why.
- This ADR closes the K2GO-458 / K2GO-461 / K2GO-462 family. The first landed
  piece is the identifier registry (K2GO-463).
