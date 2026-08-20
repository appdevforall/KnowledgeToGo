# ADR-5062 — The Books card stays a single, presence-routed card

Status: Accepted (ADFA-5062; derived work, hangs off Epic ADFA-1028). **Scope: a decision not to split
the Books card. No code change.** Sibling to `ADR-5062-maps-two-operations.md`; closes the Books front
of ADFA-5062.

## Context

ADFA-5062's front #2 was framed as: one "Books" card means both *install Calibre-Web* (proot / STOPPED)
and *add books* (REST / LIVE), so it should be "split into two operations."

Recon of the current code changes that framing. The Books card **does not re-derive the execution
class**. It routes by **presence**:

- `LibraryHomeFragment.applyState` colours the card from `PlatformEvidence` / `serverAnswering()` /
  `systemInstalled` (GREEN / AMBER / RED / GRAY, plus "adding" / "stopped") — a status axis.
- `onCardClick` / `openSheet` pick the action from that presence: installed/responding → open the
  portal (add & read books, LIVE); absent → open `ModuleActionSheet` (install, STOPPED).

The **class of each operation already lives in the model**: `ContentType.BOOKS = LIVE` for adding
books, and `ModuleActionSheet` builds `Operation.appInstall(...)` = STOPPED for the install — and that
sheet is already model-driven. So the two operations are already distinct in the model; the card's
presence-routing is a legitimate, card-local fact (is the platform up?), a different axis from the
execution class.

This is the same shape as Maps (see the sibling ADR): there is no wrong class-derivation here to
retire, unlike SetupProgress, the Home endpoint alias, or DashboardRebuild.

## Decision

Keep the single, presence-routed Books card. Do **not** split it into two visible cards, and do **not**
restate the operations' class at the card.

## Consequences

- No code change; no UX change.
- The two Books operations stay single-sourced — `ContentType.BOOKS` (LIVE) and the sheet's
  `Operation.appInstall(...)` (STOPPED) — and the card consumes **presence** only.
- The Books front of ADFA-5062 is closed as a documented decision.
- If a future need makes the two operations worth surfacing separately, revisit it as a product/UX
  decision (Option C below), not as a class-derivation fix.

## Options considered

- **A — Accept and document (chosen).** The single card is honest: the ops are already distinct in the
  model, and routing by presence is legitimate. Zero duplication.
- **B — Declare the two Operations at the card (rejected).** Building `Operation.content("books")` /
  `Operation.appInstall("calibreweb")` in `onCardClick` would echo a class the model already owns
  (`ContentType.BOOKS`, the sheet's `appInstall`), with nothing at the card consuming them — a second
  place that knows the same thing, the exact smell the review process rejects.
- **C — Visible split into two cards/actions (deferred).** A real product/UX change; risks confusing a
  zero-expertise user with two "Books" entries, and nothing in the model is misclassified to justify it.

## References

- `ADR-5061-rest-vs-proot-operation-model.md` — the operation model.
- `ADR-5062-maps-two-operations.md` — the sibling "already modeled, document the boundary" decision.
- `redesign/LibraryHomeFragment.java` — `onCardClick` / `openSheet` / `applyState` (presence routing).
- `redesign/ModuleActionSheet.java` — the model-driven Install (STOPPED) vs Open (LIVE).
- `system/domain/ContentType.java` — `BOOKS` (LIVE, add-books content).
