# ADR-5061 — Making the LIVE-REST vs STOPPED-proot operation class explicit

Status: Proposed (ADFA-5061; derived work, hangs off Epic ADFA-1028). **Scope: model + guidance, no
big refactor.** Define the model and the UX contract, apply it first to the dashboard card and to
Kolibri (the in-flight work), and migrate the rest of the UI incrementally under follow-up tickets:
**ADFA-5062** (retire the remaining per-surface derivations) and **ADFA-5063** (reversibility).
`operation-model-roadmap.svg`, beside this file, tracks how far each has got.

This document began as a cold analysis and was then paused while ADFA-4954 made Kolibri reachable
from the interface. Building that produced evidence the analysis did not have. It is folded in below
and marked, because it **changes** the model rather than merely illustrating it.

## Context

The box runs two, already-decided, fundamentally different kinds of operation:

- **LIVE / REST** — the box is up; the device only POSTs/polls the in-server dash-node core
  (`nginx :8085 → node :4000`, `/k2go-api`). The user can keep using the system and its current
  content while it happens. This is the "live content channel" of ADR-4832. It covers **content**
  (search/download/delete books, ZIMs, Maps FQR regions, Kolibri channel seeding), credentials, the
  WebView auto-login session, the dashboard self-update on dash-node ≥ 1.2.0 (ADFA-5051) and
  update-check. Downloads are durable jobs (`static/dashboard/sockets/jobs.ts`); the rest are direct
  calls. All of it lives in one router, `static/dashboard/routes.ts`.
- **STOPPED / proot** — `InstallService` + `PRootEngine` run `pdsm stop` (stopping **all** box
  services, not one), execute `runrole`/Ansible in a transient `proot --kill-on-exit` over the rootfs,
  and the *app* (`SetupProgressActivity` / `ServerController.startEnvironment`) is what boots the box
  back. proot cannot be entered after start and only one may run at a time (ADR-4832 / ADR-5011). It
  covers **module-app installs** (Calibre-Web, Kiwix reader, Maps module, the Kolibri app, Code),
  rootfs install, the dashboard rebuild on dash-node < 1.2.0, backup/restore (`DeepOpService`), and
  reset/delete.

Deciding on these two classes took real effort. The problem is **not** the two mechanisms — it is that
**no single source of truth declares which class an operation is.** The class is re-derived at every
surface, from whatever is handy:

- a single "Rebuild" button forks REST vs proot on an **invisible on-disk version**, with a **silent
  fallback to proot** when the version is null/unparseable (`redesign/DashboardRebuild.java:80-84`,
  `redesign/DashboardVersion.java:32-45`);
- the same logical "Rebuild" shows **two different progress UIs** — an in-dialog spinner (REST) vs the
  full-screen `SetupProgressActivity` (proot) — with two different "can't leave" gates;
- `DashboardDetailFragment` advertises **"REST API / System core"** chips yet its one button can drop
  into a proot rebuild (`redesign/DashboardDetailFragment.java:64-76`);
- `ModuleActionSheet` mixes a LIVE **"Open"** and a STOPPED **"Install"** in one menu with no signal of
  the difference (`redesign/ModuleActionSheet.java:101-143`);
- Home cards **conflate a proot app-module with REST content** — one "Books" card means both *install
  Calibre-Web (proot)* and *add books (REST)* (`redesign/LibraryHomeFragment.java`, `ModuleRegistry`);
- `SetupProgressActivity` orchestrates **both classes in one pipeline** and re-derives each row's class
  from a hard-coded key switch (`mod:`/`maps` → proot; `zim`/`kolibri`/`books` → REST);
- "Maps" is **two mechanisms under one name** (module install = proot; FQR region = REST);
- reversibility splits along the same seam (delete a ZIM/book = REST; a module can only be *hidden*,
  not uninstalled).

**What building Kolibri added (ADFA-4954).** Two findings, and the second is not cosmetic.

*The class is re-derived from a field that does not survive the activity.* Whether a content flow is
running pre-install is held in four plain booleans on `SetupLibraryActivity` — `zimWizard`,
`mapsWizard`, `booksWizard`, `kolibriWizard`. Each Confirm screen asks the activity which mode it is
in. None of the four is saved, so when the system destroys and restores the activity the fragments
come back and the flags do not: the Courses confirm step decides it is the Get More door, disables
itself and shows a notice about Get More in the middle of the wizard, and the user can no longer
queue anything. ZIM, Maps and Books fail the other way — they conclude they are live and go looking
for a server that does not exist yet. `configChanges` covers rotation, so this needs process death or
"don't keep activities"; the state it lands in cannot be recovered without restarting the wizard.

*The model is missing a third answer: **deferral**.* Seeding a Kolibri channel is a LIVE operation on
both doors — the same POST to the in-server REST core, same mechanism. What differs is whether it can
run **now**. In the wizard there is no box, so the order is written to a wishlist and a provisioner
drains it once the system is up. Four content flows already do exactly this (`ZimWishlist`,
`MapsWishlist`, `BooksWishlist`, `KolibriWishlist`), and the drain is only triggered from
`SetupProgressActivity`. With only LIVE and STOPPED, that distinction has nowhere to live, and the
four booleans are what stands in for it. Adding the model without naming deferral would simply move
them house.

**What the code survey found (ADFA-5061, before writing anything).** Three things the analysis
assumed and the code does not do:

*There is no positive "the extraction finished" marker.* What exists is the opposite:
`InstallGuard` writes `.install_in_progress` at the start of any pipeline and deletes it only on a
clean terminal (`InstallGuard.java:25`, `InstallService.teardown():923`). A process killed mid-extract
leaves it set, and that is deliberate. So "success" is inferred from two things at once — the marker
is gone **and** the rootfs looks plausible. `installed_tier` is not that signal either: it is written
*before* the download starts (`InstallService.java:285`) and never corrected if the pipeline fails.

*The boot check is real but narrow.* `LibraryActivity.evaluateRecovery():518` declares a damaged
install only when the marker is still set **and** the server did not answer within 25 s, and then
blocks with a non-cancellable dialog offering restore or reinstall. A rootfs corrupted *after* a clean
install is not caught: the app opens the library offline and says nothing. The verdict itself lives in
`install/domain/InterruptedInstallDetector.java`, is pure, and is the one piece of this chain with a
JVM test — it is the shape everything below should copy.

*"Is a system installed" is answered nine different ways*, at three levels of rigour and over two
different paths for the same binary (`bin/bash` in `SystemStateEvaluator`, `usr/bin/bash` in
`DeployFragment:567`). Only the canonical `SystemStateEvaluator.isSystemInstalled():31` consults
`InstallGuard`; the looser ones (`InstallService:258`, `InstallController:134`, `ShareController:388`,
`CloneFragment:368`) can report "installed" while an extraction is running.
`DashboardFragment.evaluateSystemState():623` is a hand-copy of `SystemStateEvaluator.evaluate()`
rather than a call to it. Most of these live in the legacy god classes the strangler migration has not
reached yet.

Underneath, the UI conflates **two orthogonal axes**:

- **Axis A — kind:** an *app/module* (installed via proot) vs *content* (added/removed via REST).
- **Axis B — execution class:** LIVE (REST) vs STOPPED (proot).

A user-facing operation is really a point `(platform, kind, class)`. Today no type captures that, so
every surface guesses, and the guesses drift.

**Why now.** Kolibri is expanding right now (ADFA-4954, in a separate working repo) and already
straddles both classes: installing the Kolibri app is STOPPED/proot; seeding channel content is
LIVE/REST. It is the freshest place ad-hoc mixing is being added. Each new platform multiplies the
problem. Lock the model before it hardens.

**The UX contract we want (hybrid visibility).** The class is not just an implementation detail — it
changes what the user can do, so it must be conveyed, subtly:

- **LIVE (REST):** services stay **up** → **green**. The user is **free to navigate** and keep using
  the box with whatever content it already has. A "keeps running in the background" affordance is
  legitimate (there *is* a background).
- **STOPPED (proot):** services are **down** → **amber** ("your system is installing"). The user is
  **locked into** the progress index / card by navigation gates — they cannot wander the app because
  the system is off. A "running in the background" button **must not exist** (there is no background).

Convey it through a shared vocabulary — a colour token (green = live / amber = stopped), the
lock/gate behaviour, and the button-set — chosen from the operation's class, not hand-coded per
screen. Subtle, not in-your-face: the lights change, buttons appear or don't.

## Decision

1. **Introduce an explicit operation model as the single source of truth.** Each user-facing operation
   declares `(platform, kind, class, reversibility)`, where `kind ∈ {APP_INSTALL, CONTENT, SYSTEM}` and
   `class ∈ {LIVE, STOPPED}`. The class is a **property of the operation, defined once** — never
   re-derived from an endpoint string, a pending-provisioner flag, an on-disk version, or a per-screen
   key switch.

2. **Separate the two axes in the model (and, over time, in the UI).** A *platform* (Books, Wikipedia,
   Maps, Kolibri, Code, Dashboard) has an APP install (proot) and, where applicable, CONTENT operations
   (REST). Model them as distinct operations on the same platform, each with its own class, instead of
   one card that silently means both.

3. **The class drives a consistent UX contract** (the hybrid above), from shared tokens:
   `LIVE → {colour: green, navigable: true, backgroundAffordance: allowed, progress: in-context}` and
   `STOPPED → {colour: amber, navigable: false (gated to the index/card), backgroundAffordance: none,
   progress: full-screen index}`. Presentation reads these from the class; screens stop hand-picking.

4. **One dispatch point per operation** reads the class and selects mechanism + progress surface +
   gates. Kill the implicit per-surface derivation: replace the `SetupProgressActivity` key switch and
   the invisible version fork's *silent* fallback with an **explicit** resolver — and when the class
   genuinely can't be determined, fail loud (or default with a visible note), never silently.

5. **Allow class to be resolved, not just statically tagged.** Two operations prove the class is
   sometimes state-dependent and the model must express "class resolved at dispatch":
   - the dashboard rebuild is **LIVE on ≥ 1.2.0, STOPPED on < 1.2.0** (a per-version resolver);
   - the Maps runrole install is a STOPPED op that **does not stop all services** (it coexists with a
     live server) — so `STOPPED` needs a sub-distinction ("stops the whole box" vs "stops nothing,
     runs runrole alongside a live box"). The model should carry that nuance, not paper over it;
   - a LIVE operation invoked before any box exists cannot run at all and must be **deferred** — see
     decision 7. The resolver's input is "is a system installed, and which tier", a fact the device
     can look up rather than a flag a screen has to carry.

6. **Scope for this ADR (per the decision to stay model + guidance):** define the model, the UX
   contract, and the migration rules; then apply it FIRST to (a) the **dashboard card / Rebuild** — the
   sharpest mixing example — and (b) **Kolibri** (install = STOPPED, seeding = LIVE), so the newest work
   adopts the model. Everything else (module cards, `ModuleActionSheet`, `SetupProgressActivity`, the
   reversibility model) migrates **incrementally** under follow-up tickets. No big-bang refactor.

7. **Deferral belongs to the model, not to a flag per flow.** Dispatch has three possible answers,
   not two: run it live, run it with the box stopped, or **queue it, because there is nothing to run
   it against yet**. Deferral is a property of the moment, not of the mechanism — same operation, same
   class — and it is what the four `*Wizard` booleans are impersonating today. Once the resolver
   answers from "is a system installed", those fields disappear and the restore bug goes with them:
   there is no longer any state to lose.

8. **One owner for the system facts — and there are four of them, not one.** The survey showed the
   resolver's input is not a single boolean:
   - **installed** — a rootfs is on disk and no install is in progress (`isSystemInstalled`);
   - **healthy** — the install was not interrupted (`InterruptedInstallDetector`'s verdict);
   - **server up** — something answers on the box (`ServerController`'s poll, cached in
     `ServerStateRepository`; `RestReadiness.apiReady()` is a third, blocking variant);
   - **platform present** — the module for *this* content type is installed. The rootfs ships
     software, not content, and a tier decides which platforms it carries: Basic offers neither
     Courses nor Books, Standard offers Courses, Full offers everything. Get More enforces that today
     by probing the module's endpoint (`GetMoreHubFragment.reachable():269`) and only drawing a card
     that answers, while the wizard enforces it from the chosen tier
     (`computeWizardAvailability():129`). Both are needed: the tier says what you are entitled to,
     the probe says what is actually there.

   Defined once here and consumed by ADFA-4758, which asks for the same thing as "persist and surface
   the installed tier/state". Agreeing it late means building it twice, in two shapes.

9. **Consolidating the nine checks must not homogenise them.** Some are loose on purpose:
   `InstallService.runPipeline():258` asks "is there a directory to wipe", which is a legitimate and
   *different* question from "is there a healthy system". Silently upgrading it would change when a
   rootfs is re-extracted — the data-loss path ADFA-4758 exists to make safe. The single reader
   therefore exposes **more than one question**, and each call site migrates to the one it was already
   asking. Only the indefensible divergences are corrected: `DeployFragment`'s different bash path and
   `DashboardFragment`'s hand-copied evaluator, both of which are supposed to agree with the canonical
   answer and today may not.

## Options considered

### Option A — Explicit operation model + hybrid UX contract, incremental migration (chosen)
| Dimension | Assessment |
|-----------|------------|
| Complexity | Low–Med (a small type + resolver + UX tokens; UI migrates piecemeal) |
| Blast radius now | Low (dashboard card + Kolibri only) |
| Durability | High (new platforms declare their class; presentation derives) |
| Risk | Low (existing surfaces keep working until migrated) |

**Pros:** single source of truth; new platforms slot in; matches the agreed "model + guidance" scope;
the hybrid contract is captured once. **Cons:** two representations coexist during migration; requires
discipline to actually retire the per-surface derivations.

### Option B — Big-bang UI refactor to the two axes now
| Dimension | Assessment |
|-----------|------------|
| Complexity | High |
| Blast radius now | High (cards, action sheet, progress screen at once) |
| Durability | High end-state |
| Risk | High (touches every install/content path simultaneously) |

**Pros:** clean end-state immediately. **Cons:** large, risky, and out of the chosen scope; would
stall Kolibri rather than guide it.

### Option C — Do nothing / keep per-surface derivation
**Pros:** zero cost now. **Cons:** drift compounds with every platform; Kolibri bakes in more ad-hoc
mixing; the exact failure the effort is meant to prevent.

## Trade-off analysis

Option A buys a durable model with a small immediate footprint and lets the in-flight Kolibri work
adopt it now — the highest-leverage moment. B is the cleaner destination but too heavy and risky to do
in one move, and it isn't what's needed to *unblock* the current expansion. C is cheapest today and
most expensive by the third platform. A is B done safely over time.

## Consequences

- **Easier:** new platforms and operations declare `(platform, kind, class)` and get consistent
  mechanism, progress, colour, gates, and reversibility for free; "which mechanism runs?" has exactly
  one answer; Kolibri's two-class straddle stops being special-cased.
- **Also fixed:** with "server up" and "platform present" as separate facts, the Get More hub stops
  saying "nothing is installed" when the box is merely switched off — today every probe fails and an
  intact Standard system looks identical to one with no modules at all
  (`GetMoreHubFragment.buildCards()`, the `k2go_gm_none` branch).
- **Also fixed, as a side effect rather than a patch:** the four `*Wizard` booleans stop existing, so
  a restored activity can no longer leave a content flow in the wrong mode. That defect does not get a
  ticket of its own — it is a symptom of the thing this ADR removes.
- **Harder / to revisit:** a migration plan is needed so the per-surface derivations are actually
  retired and not merely shadowed — that is ADFA-5062; the model must handle the resolved cases
  (version-gated dashboard rebuild, maps-no-stop, deferral) rather than assuming a static tag; the
  reversibility gap (modules can't be uninstalled) becomes visible and gets its own decision in
  ADFA-5063.
- **Do not close ADFA-4758 as superseded.** Two of its four asks did ship — the guard that skips the
  OS re-extract when a system exists, and persisting the installed tier — but the pipeline is still
  one `ACTION_START` governed by flags rather than two separately invoked installers, and the guarded
  system-change flow with backup and migration of customised content does not exist. That second half
  is the one that can lose a user's data.

## Action items

1. [x] Confirm the ticket (ADFA-5061), hang it off Epic ADFA-1028, rename this file, and open the
       follow-ups: ADFA-5062 (surfaces) and ADFA-5063 (reversibility).
2. [ ] Land the model, in this order:
       a. one reader for the four system facts, with the several questions of decision 9;
       b. a pure resolver returning run-live / run-stopped / defer, tested on the JVM the way
          `InterruptedInstallDetector` already is (no emulator);
       c. the `kind`/`class` type on each operation, handling the version-gate and maps-no-stop cases;
       d. the UX-contract tokens (colour, navigable/gated, background affordance, progress surface).
3. [ ] Apply to the dashboard card / Rebuild: make the REST/proot fork explicit; align colour + gates +
       progress to the resolved class; remove the silent proot fallback.
4. [ ] Apply to Kolibri (coordinate with ADFA-4954): install = STOPPED, seeding = LIVE, each presented
       per its class.
5. [ ] Express deferral in the model and retire the four `*Wizard` booleans on `SetupLibraryActivity`,
       deriving the answer from whether a system is installed.
6. [ ] ADFA-5062: migrate `ModuleActionSheet`, the Home cards (split app-install vs content), the
       `SetupProgressActivity` key switch, Maps' two mechanisms under one name, and the five remaining
       call sites that probe the disk themselves (`InstallController`, `ShareController`,
       `CloneFragment`, `DeployFragment`, `DashboardFragment`).
7. [ ] ADFA-5063: the reversibility field, and the decision on real module uninstallation.
8. [x] Diagram: `operation-model-roadmap.svg` — ticket map, dependencies and per-item progress. A
       surfaces to operations to class map of the *current* code is still to draw.

## References

ADR-4832 (live content channel / single proot), ADR-5011 (dash-node rebuild, Track A/B);
`static/dashboard/routes.ts`, `static/dashboard/sockets/jobs.ts`; `install/presentation/InstallService.java`,
`redesign/DashboardRebuild.java`, `redesign/DashboardVersion.java`, `redesign/DashboardRebuildRunner.java`,
`deepop/DeepOpService.java`, `env/EnvironmentControl.java`, `redesign/ServerController.java`,
`redesign/SetupProgressActivity.java`, `redesign/ModuleActionSheet.java`, `redesign/LibraryHomeFragment.java`,
`redesign/ModuleRegistry.java`; Kolibri: `kolibri/data/KolibriRestClient.java` (LIVE seeding) +
`ModuleRegistry` kolibri module (STOPPED install), ADFA-4954 (in-flight, separate repo).
Deferral evidence: `redesign/SetupLibraryActivity.java` (the four `*Wizard` fields and their readers),
`kolibri/presentation/KolibriProvisioner.java`, `kolibri/data/KolibriWishlist.java`.
System facts survey: `InstallGuard.java`, `SystemStateEvaluator.java`,
`install/domain/InterruptedInstallDetector.java` (+ its test), `redesign/LibraryActivity.java:518`,
`redesign/GetMoreHubFragment.java:129,269`, `redesign/RestReadiness.java`.
Related tickets: ADFA-4758 (split the install pipeline), ADFA-5062, ADFA-5063.
Progress: `operation-model-roadmap.svg` (same folder).
