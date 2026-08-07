# ADR — Kolibri content selection, homologated to the ZIM/Kiwix template

Status: **Draft, for discussion.** Ticket ADFA-4954 (parent ADFA-1028). Builds on
ADR-4832 (live-content channel) and ADR-4853 (wizard pre-selection). The server
side landed under ADFA-4949; the Android domain and REST client under ADFA-4954.

## Context

ZIM/Kiwix is the most complete content manager in the app and is therefore the
template every new one is homologated against. Kolibri ("Courses") is the next
one. This ADR records where the template transfers unchanged, where it cannot,
and what has to be agreed before implementation.

The template, as it exists today:

| Layer | ZIM |
|---|---|
| Catalog | `assets/kiwix_catalog.csv`, 3 660 rows, generated off-device by `tools/build_kiwix_catalog.py`, refreshed by the `refreshKiwixCatalog` Gradle task on `assembleRelease` only |
| Taxonomy | `KiwixCategories.ALL`, 23 hardcoded projects |
| Selection unit | the 3-tuple `project\|lang\|flavour` |
| Wizard screens | Landing (categories) → Category (entries) → Confirm (fit check) → Preparing |
| Two doors | `GetMoreHubFragment(wizard)` — tier-gated pre-install, live-probed post-install |
| Wishlist | `k2go_zim_wishlist` prefs, JSON `[{key, bytes}]` |
| Provisioner | `ZimProvisioner.drain()`, deferred while proot runs, cleared on hand-off |
| Service | `ZimDownloadService`, foreground, sequential, 3 attempts/item, continues past failures |
| Server | `kiwix.exec.ts` spawns `aria2c` then `iiab-make-kiwix-lib` |

## What Kolibri's model actually is

Verified against the Kolibri and Studio source, not inferred:

1. **The selection unit is `channel_id` + `version`.** Optionally refined by
   `node_ids[]` — a list of subtree roots, not leaves.
2. **There is no `project` axis.** No field in any Kolibri or Studio API groups
   channels into families. Two language editions of the same corpus are two
   unrelated `channel_id`s. Name-prefix heuristics will misgroup.
3. **There is no `flavour` axis.** The nearest thing is `node_ids[]`, which is
   open-ended rather than a small enum.
4. **Language is a channel property, not a selector component** — a filter
   (`?languages=`), not part of the key.
5. **A real subject taxonomy does exist**: `le_utils` subjects, hierarchical and
   dot-delimited (`SCHOOL.MATHEMATICS.ALGEBRA`), exposed as `categories` on
   `GET /api/public/v2/channel/`, with facet values from `.../channel/labels/`.
   Note the ids contain `&` and `#` and must be URL-encoded.
6. **Channel sizes are available** (`published_size`), **sub-channel sizes are
   not.** `contentnode_granular` returns resource *counts*
   (`total_resources`, `on_device_resources`), never bytes. Exact bytes exist
   only once the import task starts.
7. **`published_size` is an upper bound** for a second reason too:
   `renderable_only=True` is the default on every import path, and files already
   on disk are shared across channels by checksum and skipped.
8. **`channel_name` is a required field** on every Kolibri task serializer, used
   for job metadata. The id alone is not enough to enqueue.

### Terminology warning

**Do not name anything `Collection`.** In Kolibri, `Collection` is a
`kolibri.core.auth` model for grouping *users* — Facility, Classroom,
LearnerGroup. The collision is live in Kolibri's own code
(`lesson_assignments__collection__membership__user` appears inside a *content*
filter). This document uses **channel** for the unit and **bundle** for a
multi-channel selection.

## Where the template transfers unchanged

Everything below the picker. This is most of the work, and it is the part
already built or specified:

- Two doors on one screen set, `isXWizard()` forking the Confirm button between
  "bank to wishlist" and "start now".
- Wishlist in app-private prefs, cleared on fresh wizard and on reinstall.
- Provisioner with `hasPending`/`drain`, tag `K2Go-Provision`, deferral while
  proot runs, cleared on hand-off, registered in
  `SetupProgressActivity.orchestrateStep()` Stage 2 and in the
  `LibraryHomeFragment` fallback.
- Foreground service: single session, `PENDING/ACTIVE/DONE/FAILED`, bounded
  per-item retry, continue past failures, notification into
  `SetupProgressActivity` with `EXTRA_OPEN_STREAM`.
- `ProvisioningChecklist`, `ByteFormatter`, `StepSpine`, the M3 token rules.
- The hub slot already exists: `GetMoreHubFragment.ITEMS` carries
  `("courses", "kolibri", ic_card_courses, …, amber)`, and the picker is
  currently a `PlaceholderFragment`.
- Server-side: the `/kolibri/*` endpoints (ready, preflight, channels, catalog,
  resolve, tree, estimate, delete, task, download) and the `kolibri` runner are
  merged and follow the runner contract exactly.

## Where the template does not transfer

### M1 — The catalog has no offline source

Kiwix bundles its catalog in the APK, so the wizard works before anything is
installed. `/kolibri/catalog` proxies **live** through Kolibri to Studio, which
requires a running box and credentials — neither exists at wizard time. Same
problem ADR-4853 solved for Books with a bundled asset.

Kolibri's catalog is small: ~173 public channels against 3 660 ZIM rows. The
trap is thumbnails — v1 and v2 both inline them as base64 data URIs, which would
dominate the asset. They must be stripped or downsampled.

### M2 — Three screens, but the middle one has nothing to group by

Landing lists 23 projects; Category lists the flavours within `project+lang`.
Kolibri has neither axis, and the subject taxonomy that looked like a
replacement turns out to be empty in Studio's public library (see "Verified"
above). The Landing screen becomes a searchable, language-filtered list rather
than a grid of categories.

### M3 — Scope has no equivalent, and it matters

The three scopes Kolibri supports are: whole channel, selected topic subtrees,
and metadata-only (`include_node_ids: []` — the channel becomes browsable,
resources fetched later). The third is a verified Endless Key pattern and is
nearly free in bytes.

Note the collision this creates and guard it: in a **manifest**,
`include_node_ids: []` means metadata-only; in the **task API**, `node_ids: []`
means zero nodes and the task completes successfully having transferred
nothing. `ChannelSelection` already rejects an empty node list for exactly that
reason — metadata-only must be its own scope, never an empty list.

### M4 — Estimates are weaker than the template assumes

The ZIM Confirm screen makes a hard fit decision and disables the button when it
does not fit. For Kolibri that would be wrong for partial selections, where the
figure is an upper bound. `SeedPlan` already encodes this: `fitsIn()` returns
`null` for "cannot tell" and `isEstimateExact()` is false for any partial
selection. The Confirm screen must render three states, not two.

Sub-channel bytes are recoverable after all — `files[].file_size` on the Studio
tree — but only by walking the subtree, so treat the exact figure as something
computed on demand for the selected topics, not for every row.

## Verified against the live APIs (2026-08-05)

Four things were tested rather than reasoned, and two of them overturn the
first draft of this document.

1. **`GET /api/public/v2/channel/labels/` returns `categories: []` and
   `countries: []`.** The subject taxonomy exists in `le_utils` but is **not
   populated** in Studio's public library. Spot-checking channel and node
   payloads confirms it: `categories` is empty on every record seen. A picker
   that browses by subject would browse an empty index. **`languages` is richly
   populated** — 120+ entries with native names. Language, not subject, is the
   real primary facet.
2. **`GET /api/public/v2/contentnode_tree/<node_id>` is public, unauthenticated
   and works with no box at all.** It returns three levels per call
   (`children.results[].children.results[]`, cursor in `more`), with `title`,
   `kind`, `is_leaf`, `parent`, `ancestors[]`, thumbnails as **URLs** (not
   base64), and — importantly — `files[].file_size` per file. So topic browsing
   *and* subtree byte sizes are obtainable before anything is installed.
3. **Our own `/kolibri/tree/:channelId` cannot do that.** It proxies Kolibri's
   `ContentNodeGranularViewset`, whose `get_queryset()` is
   `models.ContentNode.objects.all()` against the **local** database, resolved
   with `get_object_or_404`. Before the channel database is imported there are
   no local nodes, so it 404s. It is a post-import endpoint by construction.
4. **The task endpoint path is confirmed**: `kolibri/core/tasks/api_urls.py`
   registers `tasks` on a `SimpleRouter`, giving `/api/tasks/tasks/` — which is
   what `kolibri.exec.ts` already posts to.

Also observed: nodes carry MPTT `lft`/`rght`, so `(rght - lft - 1) / 2` gives a
subtree's descendant count with no walk at all — a cheap "how big is this
topic?" for a row label.

### Consequence — where the boundary sits

The app talks to **two** back ends, and the split is not the one assumed:

| Need | Source | Available pre-install |
|---|---|---|
| Channel list, search, language filter | Studio `/api/public/v2/channel/` | yes |
| Topic tree, subtree sizes | Studio `/api/public/v2/contentnode_tree/` | yes |
| Free space, fit decision | box `/kolibri/estimate`, `/kolibri/preflight` | no |
| What is already installed | box `/kolibri/channels` | no |
| Seed, delete, progress | box `/kolibri/download`, `/delete`, `/jobs` | no |

Studio for *what exists*, the box for *what this device has and does*. That is
the whole rule.

## Decisions proposed

- **D1. The bundled asset is the catalog; Studio is consulted live only for the
  topic tree.** Measured against the live API on 2026-08-05:

  | Request | Bytes | Thumbnails |
  |---|---|---|
  | `/api/public/v2/channel/?public=true&page_size=1000` | 4 114 366 | 97 % |
  | the same plus `&fields=id,name,version,...` | 4 114 366 — byte-identical | 97 % |
  | `/api/catalog?public=true` | 2 607 427 | 93 % |

  Studio inlines channel thumbnails as base64 and **there is no way to opt out**:
  `ValuesViewset` (`kolibri/core/api.py:133`) fixes the field set on the class
  through `values` + `field_map` and accepts no `fields` or `omit` parameter,
  which the byte-identical second request confirms. Kolibri itself treats this
  as a problem — it ships a dedicated `channel-thumbnail/<channel_id>/` view
  (`kolibri/core/content/api.py:301`) precisely to avoid serving them inline.
  `/api/catalog` is no escape either: it offers `thumbnail_url` instead of
  base64 but drops `published_size`, `version`, `total_resource_count` and
  `root` — every field the picker needs.

  So a live fetch costs 4.1 MB to use 3 % of it, on the poor connections this
  product exists for. The useful content inside that response is 119 KB, and the
  generated asset is **83 KB** for all 142 channels once the fields the picker
  never reads are dropped as well. It is generated at release time by
  `tools/build_kolibri_catalog.py` through the `refreshKolibriCatalog` Gradle
  task, on a build machine that has bandwidth, with thumbnails and unused fields
  dropped. Search and language filtering then run locally against the asset: no
  spinner per keystroke, and no network at all for choosing a channel.

  The topic tree stays live (`/api/public/v2/contentnode_tree/<id>`): it is
  per-channel, fetched only when the user opens one, and cannot be bundled
  because shipping every tree would dwarf the APK.

  *An earlier draft of this ADR had this the other way round — asset as
  fallback, Studio as the primary source. It was written before the payload was
  measured; the 97 % is what reversed it.*
- **D2. Browse by language and keyword, not by subject.** The Landing screen
  cannot mirror `KiwixCategories`; there is no populated taxonomy to mirror. A
  searchable, language-filtered flat list of 142 channels is both honest and
  small enough to work. Revisit if Studio ever populates `categories`.

  The language list is **derived from the catalog**, not from Studio's labels
  endpoint. That endpoint offers 120+ languages, but only **21** are used by any
  public channel — it reports the available vocabulary, not what exists. Offering
  the other 99 would be offering filters that match nothing.
- **D3. Scope control on the channel row**: Full channel / Selected topics /
  Metadata only — and **all three work in the wizard**, because the tree comes
  from Studio, not from the box.
- **D7. Session state lives in an observable repository**, `KolibriSeedRepository`,
  following `ModuleQueueRepository`. The service writes; screens observe and
  detach with their own lifecycle. Not a new pattern: the repo already has five
  such repositories (`InstallProgressRepository`, `ModuleQueueRepository`,
  `ServerStateRepository`, `SyncProgressRepository`, `DeepOpProgressRepository`)
  against four content services still on `static` fields — the content services
  are the outlier, and CLAUDE.md forbids new shared mutable static state.

  The concrete cost of the static design is already recorded in-tree at
  `SetupProgressActivity:968`: `setListener()` is a single field, not a list, so
  the index and the detail fragment contend for it, and the teardown had to be
  forced synchronous with `commitNow()` because an async `commit()` "clobbered
  the index's listener, so a job finishing while back on the index never updated
  the UI (spinner stuck)". With per-observer lifecycles that class of bug cannot
  be written.

  Zim, Books and Maps are **not** migrated here — strangler policy: new code in
  the new structure, legacy when we touch it. We are not touching them.
- **D6. The fit check needs no box.** Studio's `published_size` plus `StatFs` on
  `getFilesDir()` — which `ZimLandingFragment` already does — answers "does this
  fit?" in the wizard.

  *Amended after device testing — see "Correction to D6" below.* The original
  text claimed `/kolibri/estimate` would contribute the checksum-shared-files
  discount and Kolibri's cushion in the Get More door. It cannot: it answers only
  for channels already installed, and returns bytes still outstanding rather than
  a channel's size.

### The three layers, and why the app owns the Studio client

The decision follows from the layering rather than from any cost comparison:

1. **The app** is the controller — it orchestrates and displays. It is not the
   system.
2. **The system** is the rootfs: software and logic, ~2 GB, installed once.
3. **The content** is what gets downloaded onto the system afterwards.

The wizard runs when layers 2 and 3 do not exist yet. There is no dashboard to
ask, so the app must be able to reach Studio unaided. That is a structural fact,
not a preference.

And once the app must carry a full Studio client — full, because the picker
needs search, language filtering and tree browsing, not a cut-down version —
implementing the same parsing again in TypeScript for the Get More door
duplicates work rather than saving it. Both doors use the app's client.

### Rejected — routing Studio through the dashboard to keep it updatable

Raised and dropped. The argument was that the dashboard is provisioned while the
APK is published, so Studio-facing logic would be cheaper to fix in Node.

One version of this argument is simply wrong and should not be revived: that
updating the dashboard is expensive because it lives in a ~2 GB rootfs. The 2 GB
is an **entry cost paid once**; thereafter the dashboard updates in kilobytes
through `rebuild-dashboard.sh` and the `static/` push path, in minutes today and
plausibly seconds later. Dashboard updates are genuinely cheap.

What defeats the argument is the layering above — at wizard time there is no
dashboard — supported by two further points:

- **The staleness scenario is self-cancelling.** It requires a device that is
  online (otherwise Studio is unreachable and the logic is moot) but running a
  stale APK. A device that is online can take the ~30 MB OTA update.
- **Proportionality.** If a 30 MB APK update is infeasible for a deployment,
  downloading Kolibri channels — tens of GB, the entire point of the feature —
  is far more so.

One narrower idea from that discussion is worth keeping, and is stronger than it
first appeared given how cheap dashboard updates are: `/kolibri/catalog`
currently reaches Studio *through* Kolibri's `remotechannel` proxy, which costs
an authenticated session and a running Kolibri. Having the dashboard call Studio
directly from Node would drop both. That is a worthwhile improvement to the Get
More path, not a reason to route the wizard through the box.
- **D4. Wishlist entry carries `{channelId, version, name, bytes, scope,
  nodeIds?}`.** The `name` is not redundant: `channel_name` is a required task
  field. Same reasoning ADR-4853 used to justify storing `download_url` for
  Books — do not make provisioning depend on a catalog lookup that can drift.
- **D5. Vocabulary**: `channel`, `bundle`, `scope`. Never `Collection`.

- **D8. The three live REST streams serialize on the device.** Each provisioner
  adds the other two services to the guard it already has: Kolibri defers while
  Books or ZIM hold a session, and they defer to Kolibri.

  The reason is not bandwidth, which only makes things slower. It is the
  **free-space race**: each stream measures the disk independently and at a
  different moment — kiwix with `df` against a 5 GiB buffer
  (`kiwix.exec.ts:52`), Kolibri by asking Kolibri, the wizard with `StatFs`.
  None of them knows about the others, so all three can pass their own check and
  jointly fill the disk. Fixing this only for the new stream would leave the
  hole open between the two existing ones.

  Edits to `BooksProvisioner` and `ZimProvisioner` are one term added to an
  existing `if` — additive and minimal, per the hotspot rule. No deadlock is
  possible: deferring means "return and retry later", and
  `SetupProgressActivity.orchestrateStep()` calls them in a fixed order, so the
  first one starts and the rest wait.

  **Known consequence: the feature packages now depend on each other.**
  `org.iiab.controller.kolibri` imports from `redesign` (the two sibling services
  and `MapsProvisioner` for the guard, plus `ProvisioningChecklist` and
  `SetupProgressActivity` for the UI), and `redesign` imports from `kolibri` for
  the integration. That cycle means the Kolibri package is not self-contained,
  which is what CLAUDE.md's one-feature-one-package rule is trying to buy.

  Part of it is inherent — a guard that serialises three streams has to know
  about all three — and part is legitimate reuse. The clean form would be a
  shared "content stream" port that all three implement, so each defers to an
  interface rather than to its siblings by name. That is a refactor across three
  features and is not attempted here; recorded so the next person to touch this
  does not mistake it for an accident.

  Rejected for now: enforcing the queue inside `jobs.ts`. That is the deeper fix
  and would let `routes.ts:215`'s manual workaround be retired, but it changes
  shared infrastructure and the behaviour of two features that did not ask for
  it, and `reconcileOnBoot()` — which relaunches every stuck job in a loop —
  would have to be reworked. It deserves its own ADR.

- **D9. The dashboard's Spanish comments are a defect, fixed on their own.**
  `credentials.ts`, `kolibri.session.ts`, `kolibri.map.ts`, `kolibri.exec.ts`,
  `kolibri.query.ts` and their two test files carry ~443 comment lines in
  Spanish; CLAUDE.md requires English for committed artefacts and every
  neighbouring module complies. They ship as a standalone `chore:` PR with no
  ticket — APK-neutral, no behaviour change, so it clears none of the
  traceability bars that would justify one. Standalone rather than folded into a
  later change because a pure-translation diff can be read straight through and
  confirmed to touch no logic, which a mixed diff cannot. The Android side is
  already English and needs nothing.

## Verified on a device (2026-08-07)

The server side was exercised over `curl` against a real Kolibri 0.19.5 under
proot on Android 15, with no UI involved. Everything below is observed, not
inferred.

**Confirmed working.** The authenticated session, including Kolibri's
non-standard cookie names and the CSRF rotation on login. The job engine, runner
registration, polling and cancellation. Two channels inside one job, processed
strictly in sequence. The `listInstalledChannels` CTE against a real content
database. Channel deletion. Both fail-closed validations, with the messages they
were written to produce: `no valid nodeId for channel …` and `invalid channelId:
… resolve the tokens before queueing`.

**The proot prerequisite works.** The job log recorded
`content origin (https://studio.learningequality.org): created` on the first
run and `present` on the second. Creating a *static* `NetworkLocation` over REST
does satisfy `lookup_channel_listing_status()`, and it is idempotent. That whole
chain had been reasoned out of Kolibri's source and never executed; it was the
largest risk in the design and it is now closed.

**The catalog matches reality.** For `5d53b37cc90e50128a40e293d9fadb27` the
bundled asset said version 2, 1 740 285 bytes, 37 resources; the device reported
version 2, 1 740 285 bytes, 37 files. Byte for byte.

### Defects found

All four are in the in-server dashboard (`static/dashboard`), none in the app,
and none is fixed yet. They are deliberately **not** part of the Android PRs:
they belong to a separate dashboard change, which also has to bump the version in
`static/dashboard/CHANGELOG.md` because two of them alter what the REST surface
returns.

1. **`freeSpace` is always `null`.** `kolibri.query.ts` calls
   `/api/device/freespace/` without a query parameter, but `FreeSpaceView.list`
   rejects anything where `path != "Content"` with a 400. The call throws, the
   non-blocking `try/catch` swallows it, and `fitsOnDevice` degrades to `null`
   for ever. Fix: `?path=Content`.
2. **`/kolibri/catalog` reports `null` for size and resource count.**
   `toRemoteChannel` reads Studio's field names, but that endpoint is Kolibri's
   `remotechannel` proxy, which renames `total_resource_count` to
   `total_resources` and `published_size` to `total_file_size`. Read both,
   preferring Kolibri's.
3. **`Kolibri failed importing <name>: HTTPError`** is the exception's class name
   with no detail. Extract the response body instead.
4. **Kolibri returns 500 when asked to size a channel it does not have.**
   `_calculate_batch_params` computes `max_rght` from local `ContentNode` rows
   and multiplies it without a null check, so an absent channel raises
   `TypeError`. Upstream's bug, but ours to avoid: do not call it for a channel
   that is not installed, and translate the failure.

### Correction to D6

D6 said `/kolibri/estimate` would contribute the checksum-shared-files discount
and Kolibri's own cushion in the Get More door. Its role is narrower than that:
it answers only for channels **already installed** (see defect 4), and what it
returns is the bytes still *outstanding*, not the channel's size — the view
filters to unavailable files, so a fully downloaded channel correctly reports 0.
The fit check therefore rests on Studio's `published_size` plus `StatFs`, as the
rest of D6 says, and `/kolibri/estimate` is useful only for adding topics to a
channel the device already holds.

### Still unproven

Every line of the Android side. `KolibriRestClient`, `KolibriSeedService` and
`KolibriSeedRepository` have never run, because nothing populates the wishlist
until the picker exists. Note also that the two-channel test exercised the
server's own sequencing and `overallPercent` banding — which the app
deliberately bypasses by posting one job per channel, so that code path will not
be reached from the app.

## What the REST surface still needs

Less than expected, because the app now owns browsing. One real gap, and it is
in the runner rather than the routes:

- **Metadata-only is a different Kolibri task.** `TASK_REMOTE_IMPORT`
  (`remoteimport`) is hardcoded in `kolibri.map.ts`. Importing a channel's
  database without its resources is `remotechannelimport`. Sending
  `remoteimport` with an empty `node_ids` is not the same thing — it transfers
  nothing and reports success. The runner must pick the task name per item.

Deferred, not needed for v1: exposing `remotechanneldiffstats` so the UI can say
"a newer version of this channel exists"; having the dashboard call Studio
directly instead of through Kolibri's proxy.

## Scope and phasing

| PR | Contents |
|---|---|
| 0 | `chore:` translate the dashboard's Spanish comments (D9). No ticket, no behaviour change |
| A | Studio client + offline fallback asset + generator + Gradle task, with unit tests |
| B | Wishlist, `KolibriSeedRepository`, provisioner (with the D8 guard), foreground service, `SetupProgressActivity` stream row |
| C | Courses picker: browse → channel → scope → confirm → preparing, replacing `PlaceholderFragment` |
| D | Get More door: on-device annotations, installed-channel management, metadata-only task support |

Each PR carrying new strings ships all 33 locales in that PR; the ZIM family's
100 % coverage is the bar. Strings go in a per-feature
`res/values/strings_kolibri.xml` to keep out of the shared file.

## References

`KiwixCatalog.java`, `KiwixCategories.java`, `ZimLandingFragment.java`,
`ZimCategoryFragment.java`, `ZimConfirmFragment.java`, `ZimWishlist.java`,
`ZimProvisioner.java`, `ZimDownloadService.java`, `GetMoreHubFragment.java`,
`SetupProgressActivity.java`, `content/RestContentClient.java`,
`kolibri/domain/SeedPlan.java`, `kolibri/data/KolibriRestClient.java`,
`static/dashboard/sockets/{jobs,kiwix.exec,kolibri.exec,kolibri.query}.ts`,
`static/dashboard/routes.ts`. ADR-4832, ADR-4853.
