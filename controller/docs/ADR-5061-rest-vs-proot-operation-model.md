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

   A fifth thing has to be carried alongside them: **whether the fact is known at all**. The server
   answer comes from a poll that only runs while an activity is alive, and its seed value reports
   "not up" — so before the first pass, and in any process that never started it, "down" and "never
   asked" are the same boolean. Flattening those is the same mistake at a smaller scale, so
   `ServerStateRepository.hasObservation()` keeps them apart. The dispatcher then gives both the same
   answer on purpose, because the action is identical: make sure the box is up before running against
   it. Sharing an answer is a decision; sharing a representation is an accident.

9. **Consolidating the nine checks must not homogenise them.** Some are loose on purpose:
   `InstallService.runPipeline():258` asks "is there a directory to wipe", which is a legitimate and
   *different* question from "is there a healthy system". Silently upgrading it would change when a
   rootfs is re-extracted — the data-loss path ADFA-4758 exists to make safe. The single reader
   therefore exposes **more than one question**, and each call site migrates to the one it was already
   asking. Only the indefensible divergences are corrected: `DeployFragment`'s different bash path and
   `DashboardFragment`'s hand-copied evaluator, both of which are supposed to agree with the canonical
   answer and today may not.

10. **A pending replacement is a fact, and it needs a durable home.** Decision 8 lists what is true
   about the box. This one is not: it is what has already been **decided** about it. During a
   reinstall every readable fact still says the old system is installed, healthy and answering, right
   up to the moment it is wiped — so a decision made from those facts alone will happily act on a
   system that is about to stop existing. That is not hypothetical: it shipped, and the Courses picker
   downloaded live onto the doomed system and took the user out of the wizard, so the reinstall never
   ran (ADFA-4954, PR #371).

   The fix carries the fact through `SystemFacts.withReplacementPending()` and answers `DEFER` above
   every other check. What it does **not** yet fix is where the fact comes from: the screen reads it
   from `SetupLibraryActivity.kolibriWizard` — one of the four booleans this ADR exists to retire.
   Hardening it by reading a different activity field instead would be correct in form and wrong in
   substance: still a screen answering a question about the system, still not covering first install,
   and one more half-durable carrier to migrate later.

   **Resolved, and not the way this decision first proposed.** The original text here called for a
   *persisted declaration of intent*, sibling to `InstallGuard`, on the grounds that the flag had to
   survive the process being killed. That premise was wrong on inspection. `reinstallMode` is read
   back from the Intent on every `onCreate`, and Android keeps the Intent in the task record — so it
   survives a config-change recreation **and** a process death with task restore. And where the task
   is not restored, the user is no longer inside the wizard at all, so there is no picker open to
   hijack.

   Building the persisted marker would therefore have invented durable state to cover a case that
   does not occur, with the failure mode this decision itself warned about: a marker nobody clears
   blocks every live download forever. The fact is carried by `SetupLibraryActivity.isReplacingSystem()`,
   which reads `reinstallMode`, and nothing is persisted. Recorded here rather than quietly dropped,
   because the reasoning is the useful part: *durable* and *persisted* are not synonyms, and the
   cheapest durable carrier already existed.

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
          Include a **terminal state that survives not being watched**: a LIVE operation is meant to
          be left alone, so the user usually arrives after it finished and finds a bare "Done" with
          no evidence anything happened. A finished card should keep at least the average transfer
          rate and the elapsed time — "838 MB · 32 MB/s" reads as proof of work in a way "Done" does
          not, and it is the only thing distinguishing a real download from a no-op. The rate is not
          on the device (the server does the transfer), so it has to be derived from the bytes and
          the poll timestamps we already hold, or asked of the REST core.

          **The progress screens are already shared; the hosting is not.** A survey for ADFA-5074
          corrected an earlier reading here. `SetupProgressActivity.openDetail()` hosts the very
          same fragments the live doors use — `ZimPreparingFragment`, `BooksDownloadsFragment`,
          `MapsPreparingFragment`, `KolibriSeedingFragment` — each carrying a `fromIndex` flag that
          hides its own buttons when it lives inside the index. There is no second implementation
          to merge.

          What differs is **which host the user lands in**, and it differs per type: Get More sends
          ZIM and Courses to a fragment inside `SetupLibraryActivity`, Books to the index with a
          hint that jumps straight to the detail, and Maps to the index list; the wizard banks and
          shows no progress surface at all. Five landings for one kind of work. So the unification
          is hosting, navigation and re-entry rather than rewriting screens — and where the chrome
          around the same fragment differs it is levelled **up**, since ZIM's live door has a "Run
          in background" that Courses lacks while Courses has a terminal action and a
          retry-before-leaving note the others lack. **ADFA-5074 owns this.**

          Sizing it turned out better than that reads: **maps and the modules already land on the
          index**, so only two routes actually move, and both are REST. The hosting work therefore
          costs no proot testing — which matters, because a module install can take an hour and had
          been making this the expensive item.

          **Courses moved first (done).** `KolibriConfirmFragment` now starts `SetupProgressActivity`
          with `EXTRA_HINT_STREAM = "kolibri"`, the same call `SetupLibraryActivity.startBooksDownload`
          already made, and `openKolibriSeeding()` is gone. Levelling up went one way only, and the
          host won every comparison: its Finish clears all three content sessions rather than only
          this one, and it redirects by itself when the run succeeded, where the seeding footer made
          the user press a button to leave a run that had finished cleanly. The one rule not carried
          over is deliberate — Courses withheld the exit while a channel had failed, to keep the
          retry rows reachable, and an index answering for four streams cannot withhold the exit for
          one without stranding the other three. The failure case is the host's Finish-plus-note.

          **ZIM moved next (done), and it was the one with something underneath.** Courses and Books
          already had a door that started the work before navigating; ZIM did not — *the preparing
          fragment itself* started the download from its own `onCreateView`, resolving the cart
          against the Kiwix catalogue. That is what the `fromIndex` flag was really for: reopening a
          screen that starts work poses a question ("start it again?") that has no good answer, so
          the flag suppressed it. Moving the start into `SetupLibraryActivity.startZimDownload()`
          made the flag meaningless and it is gone; the fragment is now an observer like the other
          three. The catalogue is a baked CSV cached per process and the user reached the confirm
          screen by browsing it, so resolving costs a main-thread pass — no network, nothing to
          time out.

          The extraction also carried a guard that was easy to miss because it read as part of the
          flag: `if (!fromIndex && !ZimDownloadService.isRunning())`. The second half is load-bearing
          — `ACTION_START` overwrites the session arrays outright, so starting over a live download
          loses its bookkeeping — and unlike Courses, ZIM's door asks nothing about who else is busy,
          so a second order is reachable (index, Back, Get More, pick again). What changed is the
          dishonesty: the old code dropped the new picks in silence and showed the user the previous
          session's progress as if it were theirs. The cart is now kept, so the picks survive to be
          confirmed later. **This is precisely the case the queue in item 4 exists for.**

          Two more things fell out of the extraction and are worth naming, because neither was
          visible while the code sat inside a screen. The item-label rules (which of creator and flavour is
          redundant) became `ZimItemLabel`, pure and unit-tested — they had to move somewhere that
          is not the screen, since the labels are now computed once at hand-off and displayed later
          by a different screen. And a catalogue entry with no creator and the "all" flavour
          produced `"Wikipedia · "`, a label ending on a separator: the "say All instead of nothing"
          guard only fired when the creator repeated the project. Widened, with the test.

          **A finished run could not close itself — three links in one chain.** Found on device
          once the hosting landed and Luis started using the two doors for real: a completed ZIM
          run and a completed Courses run, on two different phones, both sitting on the index with
          a green Done row under the header "Starting services." for minutes, then redirecting.

          The wording was the tell, and Luis called it: REST content does not start services. It
          indexes. Nothing was being started, so the header was describing a state the run was not
          in — and the same flag it was describing is what held the redirect.

          - **A start gate was gating completion.** `servicesReady` exists so the pipeline never
            POSTs a job before the engine answers. But `orchestrateStep()` — which sets `drained`,
            which `allComplete` requires — only runs once that flag is true, so a screen with
            nothing left to start still had to pass a readiness probe to admit that it was done.
            Worse, the probe competes with the work: `apiReady()` gives the box 2.5 s to answer
            while the box is busy serving the download being waited on. Fixed by asking the right
            question first — if no provisioner has anything pending there is nothing to launch, so
            the gate has no purpose (`nothingToStart()`). It also makes the header truthful,
            without editing a string.
          - **The pipeline ran only when the index was on top.** `onResume` posted the poll inside
            `if (!showingDetail)`. That held while a detail could only be reached by tapping a row
            — you had been on the index, so the loop was already going. The hint route broke it:
            a Get More download opens its detail during `onCreate`, so `onResume` found the flag
            already true and never started the loop at all.
          - **`render()` returned early under a detail.** Completion is a fact about the run, not
            about which screen is in front, but the whole computation sat behind that guard. A run
            finishing while its detail was open had nowhere to say so. It now steps back to the
            index and lets the normal pass draw the summary and the countdown, rather than sending
            the user home from under a screen they were reading.

          Which of the three fired on the two phones is not settled — the header says the first,
          and the redirect arriving the instant Luis took a screenshot points at a lifecycle event
          restarting the loop, which is the second. The fix removes the dependency either way.

          **Courses had no transfer rate, and the reason is upstream.** ZIM's caption shows one;
          Courses' did not, and the two client/service pairs are structural copies — same
          `onProgress(percent, speed)`, same `formatRate`/`parseRate` round trip. The difference
          is what the box sends: `/api/kiwix/jobs/:id` carries a `speed` because aria2 measures
          one, while `/k2go-api/kolibri/jobs/:id` returns 0 because Kolibri's importer reports a
          phase and a percentage and nothing else.

          It is worth having rather than deferring to a server change, because a percentage alone
          cannot separate "slow" from "stopped" — which is the question a user opens this screen
          to answer, and the one Luis could not answer on a link that had been fast hours earlier.
          So the rate is derived on the device from what the session already holds: bytes
          transferred over time elapsed (`TransferRate`, pure and tested).

          That makes it an **average over the session**, deliberately. Kolibri reports whole
          percents, so on a large channel one report can mean hundreds of megabytes; an instant
          rate computed from that reads zero between reports and absurd on one. An average is
          stable and still falls visibly when the link slows, which is the signal that matters. It
          is a fallback only — if the box ever reports a real rate, that value wins with no change
          on the device, and it should, because an instant rate is the better answer.

          Not labelled "avg" on screen, on purpose: ZIM's is instantaneous and Courses' is not, and
          labelling one of the two invites the question rather than answering it. The finished-card
          average described earlier in this item is where the labelling gets decided for all four
          at once.

          **The queue: a busy door banks instead of refusing (done).** "Another download is
          running. Try again when it finishes." was the honest message for a door with nowhere to
          show a queue. It stopped being honest once every door landed on the index, where a
          banked order already draws its row as "Queued".

          Almost none of it was new machinery. **The wishlist is the queue** — each provisioner
          defers while another stream holds the line (ADR-4954 D8) and the order stays banked
          until a later pass takes it. What was missing was a pump outside the index:
          `LibraryHomeFragment` has drained Books, ZIM and Maps since ADFA-4853 and **courses were
          never added**, so a banked courses order sat until someone happened to open the progress
          screen. That is both a standing bug — a wizard courses order could be left waiting — and
          the reason a queue was impossible: a door cannot promise "added" if nothing drains it.
          Fixed first; then `KolibriConfirmFragment` simply stopped rolling its order back, which
          made the change mostly a deletion, and `canDrainNow` went back to being internal.

          **ZIM turned out to have a third copy of one rule.** Routing its door through
          `ZimWishlist` + `ZimProvisioner.drain` — the shape Courses already had — deleted the
          resolution this ticket had moved out of `ZimPreparingFragment` two commits earlier.
          `ZimProvisioner` had been doing exactly that for the wizard since ADFA-4853: resolve the
          catalogue, build the triples, hand them to the service. Worth naming because the two
          copies had already drifted where nobody would look: they built item labels differently,
          one combining creator and flavour and the other not, so the same ZIM was named one way
          from the wizard and another from Get More. Both are now `ZimItemLabel`. It also retires
          the special case for a second order arriving over a live one — the provisioner defers
          rather than letting `ACTION_START` overwrite a running session.

          **A fifth entry surfaced, and it is not a landing.** The Books landing screen has a
          "downloads" link (`BooksLandingFragment.openDownloads` → `SetupLibraryActivity
          .openBooksDownloads`) that opens `BooksDownloadsFragment` inside that activity without
          starting anything. It survived ADFA-4988 because that ticket moved the *confirm* landing,
          and it is genuinely a different thing: a viewer for "what am I downloading?", reachable
          when nothing is running at all. The index cannot take it over yet — opened with no work
          in flight it reads the run as complete and redirects home. So the order is: teach the
          index an idle state, then this link points at it. Until then it is the one remaining
          fragment that hosts itself, and it keeps its footer.

          **The landing decision, settled: always the index.** Decided with Luis after using both
          doors on device. The hint (`EXTRA_HINT_STREAM`) and the notification deep-link
          (`EXTRA_OPEN_STREAM`) are both gone, so `openDetail` is reachable only by tapping a row.
          Three reasons, in the order that decided it:

          - **One destination, whatever the state.** The hint opened a detail "when this is the
            only stream running" — a landing that depended on something the user cannot see. That
            is the same defect the hosting work removed, in miniature.
          - **The index is the only surface that can end a run.** Finish, the countdown to the
            Library, Run in background. A detail is a dead end by construction.
          - **The real usage is set-and-forget.** Luis' framing, and it reframes the question:
            these downloads take hours, so the dominant case is starting one and leaving, not
            watching. Someone who was never going to look does not care about the extra tap; what
            they need is the screen that finishes the job while they are away and answers "is this
            going well?" when they come back.

          The wizard already behaved this way — it banks and, after the install, shows the index
          with its rows and never a detail — so this aligns Get More with it rather than inventing
          anything.

          Two consequences worth stating so they are not eroded later. **The index gained the
          transfer rate and nothing else.** "2 of 5" cannot separate slow from stopped: on a large
          item the count sits still for an hour either way. The bytes, the per-item checklist and
          the retries stay in the detail — the index is a control point, not a smaller copy of the
          card, and that boundary erodes one field at a time. And **the notification does not force
          the index when a detail is already open**: it means "take me back to my download", and
          someone inside a detail is already there by their own choice.

          Explicitly *not* done, and not because it was forgotten: the notification says very
          little, and for a set-and-forget flow it — not either screen — is the primary experience.
          Redesigning it is the right next step and there is no time before the deadline, so the
          decision was to make the index the reliable control point and leave the notification as
          it is.

          Recorded while doing it, for the landing decision below: leaving the index by Back returns
          to the picker's **confirm screen with its cart already cleared** — a spent step. Books has
          behaved this way since ADFA-4988 and nobody has reported it, and Courses now inherits it,
          which is the point: the wart is shared rather than per-type. The fix is to pop the picker
          to the hub before leaving, and it belongs with the hint decision so all four get the same
          answer, not with the type that happened to expose it.

          **The progress animation is resolved, not placed.** Today the Lottie
          (`k2go_working_loop` — cloud sending data to the device) lives in
          `fragment_k2go_zim_preparing.xml` and nowhere else, so Courses has none. Copying it
          into the Courses layout would recreate exactly the drift this item exists to remove:
          two hand-kept copies that agree until someone edits one.

          But "one animation for all four" would trade a duplication for a lie. That cloud is
          literal for ZIM, Books and Courses — the server downloads. Maps is an Ansible
          runrole building tiles with the server stopped, and it already has its own
          collapsible log; a download animation over it would describe something that is not
          happening.

          So it is shared along the axis that already exists: **the execution class decides**,
          not the content type. The shape agreed with Luis (ADFA-5074):
          - the domain answers *which visual* an operation gets — an enum (`DOWNLOAD` for
            LIVE, `BUILD` for STOPPED), pure and unit-testable, with room for a per-module
            override when one earns it;
          - presentation maps that answer to a raw resource, in one place;
          - the host reads it and puts it above the fragment slot, so no per-type layout owns
            an animation and ZIM's copy goes away.

          For now `BUILD` deliberately resolves to the same file: Maps wants a different one
          and does not have it yet, and shipping a placeholder beats shipping a second copy.
          The point of the indirection is that changing it later is one line, and that a
          module which turns out to need its own can have it without anyone touching a layout.

          **Serialising is not the net it looks like** (noted, not blocking). The three live
          streams are meant to run one at a time, which was the poor man's stand-in for a disk
          budget: if only one runs, each selector's free-space reading is roughly valid.
          `ZimProvisioner.drain` starts asynchronously, though — it fetches the Kiwix catalogue
          first and only starts the service from the callback — so within one
          `orchestrateStep` pass Books and Courses look at a ZIM that has no session yet and
          start too. Three at once, observed on device.

          Not urgent and deliberately not part of ADFA-5074: the fix would have the guard count
          an order already handed to a drain, not just a session in flight, and that changes
          serialisation semantics with a real risk of two flows waiting on each other. And it
          would still only be a stand-in — the honest answer is the run-level budget above.
          Recorded so it is not rediscovered as a surprise.

          Also decide **which lifetime each piece of state gets**. Three mechanisms are in use and
          nothing says which is for what: a `ViewModel` (survives a configuration change only), a
          `SavedStateHandle` (also survives a process death, riding the task's instance state), and
          a process-scoped singleton such as `KolibriSeedRepository` (survives neither). Two
          consequences are already visible:
          - **Courses is no longer the reference for a picker selection.** ADFA-5061 gave the ZIM
            and Books carts a `SavedStateHandle`; `KolibriCatalogViewModel` has none. Levelling it
            is not the same edit: those carts are flat maps, while Kolibri holds `Channel` objects
            plus a `PickedSubtrees` per channel. Saving the channels themselves would put a stale
            copy of the catalogue in the bundle, so the shape is to save **ids only** and re-resolve
            once the catalogue loads. The cost is the timing, not the serialising, and a channel
            that vanished between sessions needs an answer.
          - **Running download sessions are a separate, shared gap.** `ZimDownloadService` and
            `BooksDownloadService` keep their state in statics, so they do not survive a process
            death either; `KolibriSeedRepository` is no worse. That is the durable background-jobs
            monitor already noted in `ZimProvisioner` (ADFA-4874), not a Kolibri defect.

          Also converge the **two ways the dispatcher is currently consumed**. The Courses confirm
          maps all five answers to five behaviours; ZIM, Books and Maps collapse them to a boolean
          through `ContentDoor.banks()` and drop the rest. That is deliberate for now — acting on
          "damaged" or "platform absent" means writing the refusal copy, which is this item — but
          left unnamed it becomes "why does ZIM not tell me the system is damaged?". The boolean is
          a staging post, not the destination.
3. [ ] Apply to the dashboard card / Rebuild: make the REST/proot fork explicit; align colour + gates +
       progress to the resolved class; remove the silent proot fallback.
4. [ ] Apply to Kolibri (coordinate with ADFA-4954): install = STOPPED, seeding = LIVE, each presented
       per its class.
5. [ ] Express deferral in the model and retire the four `*Wizard` booleans on `SetupLibraryActivity`,
       deriving the answer from whether a system is installed. Includes giving **pending replacement**
       (decision 10) a durable home with a stated lifecycle — who sets it, who clears it on completion
       *and* on abandonment, and what happens if the process dies in between. Until then
       `KolibriConfirmFragment.isWizard()` reads one of these booleans on purpose; do not harden it in
       place.
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
