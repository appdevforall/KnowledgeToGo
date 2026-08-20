# ADR-5062 — Maps is two operations: the STOPPED layer install and the LIVE, user-driven FQR fetch

Status: Accepted (ADFA-5062; derived work, hangs off Epic ADFA-1028). **Scope: a modeling boundary
plus a doc note on `FqrController`. No behaviour change.** Sits under ADR-5061 (the operation model)
and closes the "Maps" front of ADFA-5062.

## Context

ADR-5061 gave every operation an execution class — `LIVE` (box up, REST) or `STOPPED` (box down,
proot). "Maps" appears under one name but is **two different operations**:

1. **Install the maps layers** — an Ansible `runrole` under proot. `STOPPED`. This is the
   wizard / Get-more path, modeled today as `ContentType.MAPS` and driven by `MapsProvisioner` +
   `MapsConfirmFragment`.
2. **Fetch a specific region (FQR)** — the in-server REST engine downloads that region's tiles on an
   already-installed maps system. `LIVE`. Implemented by `FqrController` + `MapsRegionClient` on the
   `/maps/` portal page, entirely outside the model.

Because only #1 is modeled, `ContentType.MAPS = STOPPED` reads as "all maps is stopped" — which is
false for the region fetch. `Operation.java` already names this ("one 'Maps' name covering both a
`runrole` install and an FQR region download").

The two are not the same *kind* of thing, and that is the deciding fact:

- `ContentType` members (ZIM, Books, Courses, the maps layer install) are **banked and drained**:
  the wizard selects them, they are stored as wishlist orders, and the Home pump downloads them
  automatically in the background.
- The **FQR fetch requires a user directly**: it only exists when someone opens the map, draws a
  region, sees the size estimate, consents, and starts it. It is an on-demand action, not a queued
  order that drains on its own.

## Decision

- The FQR region fetch is modeled as an **`Operation`, not a `ContentType`** — conceptually
  `Operation.content("maps")` (`CONTENT`, `LIVE`). `ContentType` is the set of *banked/provisioned*
  content the wizard selects and the pump drains; the FQR fetch is a *direct, user-initiated* action
  and does not belong in that enum.
- **`ContentType.MAPS` keeps its meaning and class unchanged**: the wizard's maps-layer install
  (`STOPPED`). It describes that install, not "all maps".
- `FqrController` records this in its class doc, tying its live path to the model's vocabulary, so the
  "one name, two operations" ambiguity is resolved by the two being **distinct operations** even
  though only one is a `ContentType`.

No code path changes. `FqrController` keeps its self-contained live flow and Material 3 overlay; it
never derived a class, so there is nothing to re-route — only to declare.

## Options considered

- **A — FQR = an `Operation`, not a `ContentType` (chosen).** Lowest ripple; honest; matches the
  banked/drained-vs-user-driven distinction. The region fetch is not a wizard content type, so it
  stays out of the enum and is expressed as a `CONTENT`/`LIVE` operation.
- **B — Flip `ContentType.MAPS` to `LIVE` (region content) and model the layer install as an
  `APP_INSTALL` module.** The "Kolibri shape" (COURSES = LIVE content; the app install is a separate
  module) and the purest long-term model. Rejected for now: high ripple across `MapsProvisioner`,
  `MapsConfirmFragment`, the SetupProgress maps row and `ProgressVisual`, for little payoff today.
- **C — Two `ContentType` entries (`MAPS_INSTALL` STOPPED + `MAPS_REGION` LIVE).** Bloats the wizard
  enum with a concept that is not a wizard selection. Rejected.

## Consequences

- No behaviour change; no visible or runtime difference.
- "Is maps live?" now has a precise, per-operation answer: the layer install is `STOPPED`
  (`ContentType.MAPS`); the region fetch is `LIVE` (`Operation.content("maps")`). Code must not ask
  `ContentType` about the region fetch — it is not a content type.
- `ContentType.MAPS = STOPPED` stops being a contradiction: it is the wizard install, not "all maps".
- Closes the Maps front of ADFA-5062. The remaining 5062 item is the Books-card split, which is a
  product/UX decision, not a modeling one.

## References

- ADR-5061 (`ADR-5061-rest-vs-proot-operation-model.md`) — the operation model this rests on; it
  already lists "Maps FQR regions" among LIVE content.
- `system/domain/Operation.java` — the "one Maps name, two operations" note and the `content(...)`
  factory.
- `system/domain/ContentType.java` — `MAPS` (the STOPPED layer install).
- `redesign/FqrController.java` + `redesign/MapsRegionClient.java` — the LIVE, user-driven fetch.
- `redesign/MapsProvisioner.java` + `redesign/MapsConfirmFragment.java` — the STOPPED install path.
