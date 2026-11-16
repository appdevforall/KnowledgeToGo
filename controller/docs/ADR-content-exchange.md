# ADR: granular content exchange between boxes

Status: ANALYSIS : Pillar 0 (contracts: identity + manifest + adapter port + core REST
API) FROZEN 2026-10-22. No ticket created; anchor is the existing K2GO-427. Decisions
below are settled for v1; later pillars build against this frozen Pillar 0 (topology A).

This is a living design document. We pour the full analysis here (not in memory,
which stays a short index). No Jira ticket is attached yet: the work is larger
than one ticket. A direct ticket opens only when the first shippable deliverable
is ready to build: "install content from an offline disk". That deliverable needs
the whole mechanism first (you cannot cook breakfast without the stove, the pan,
the oil and the fire), so this ADR lands the whole mechanism; the pillars below
become area-level tickets later, under an Epic, each with explicit approval.

Open decisions are marked `OPEN:`. Sections to fill in are marked `TO DETAIL`.

ADR altitude: this doc fixes DECISIONS, CONTRACTS (as shapes, not frozen signatures) and
REJECTED alternatives : the things expensive to rediscover or re-litigate. Implementation
detail (exact field names, function signatures, UI layout) is left to the build and
marked `TO DETAIL` / "confirmed when the adapter is built". Generalist on code, precise
on boundaries and the why.

## Context and problem

Today a box shares its whole library or nothing: the clone flow (CloneFragment +
rsync) copies everything. Two real needs break that model:

- Phones have different free space. A user with a small phone cannot take "the
  whole library"; they need to pick items.
- Users want to move content between boxes offline (an external disk), add to a
  box, remove items to free space, and avoid copying what the other box already
  has.

So we want a subsystem for GRANULAR content exchange: list what is indexed, pick
items, export them, share them selectively, import them with validation, and
delete them to reclaim space. It must work for BOTH Knowledge to Go (Android host)
and Internet-in-a-Box (no Android), and it must stay aware only of the apps that
are actually installed, and learn new apps as modules are installed.

First concrete apps: Kolibri, Kiwix, Calibre-Web.

First user-visible deliverable: import selected content from an offline external
disk into another K2Go or IIAB.

## Tickets (anchor + constellation; no new ticket created by this ADR)

The analysis is bigger than one ticket (it builds the whole mechanism), but the first
deliverable points at an EXISTING ticket so the work is not in the air:
- ANCHOR: K2GO-427 "Make K2Go & content installable from a connected drive" (To Do). Its
  CONTENT side = our first deliverable (offline import), and it names the manage / remove
  use case = our delete-to-reclaim pillar. Scope note: "& K2Go" also means sideloading
  the APK itself from the drive : a separate concern, NOT the content-exchange mechanism.
Open sibling tickets this subsystem also serves (found, not created):
- K2GO-376 "When cloning, choose content instead of all-or-nothing duplication": the
  granular selective-share pillar (Pillar 2 network share).
- K2GO-396 "Content / storage management so you don't have to uninstall the app to free
  space": the delete-to-reclaim pillar (Pillar 1 delete).
- K2GO-425 "Support local installation / content update from a non-K2Go server": the
  import-from-URL / non-K2Go-source carrier (Pillar 3 input; IIAB-as-source).
- K2GO-494 "Distribute Code On the Go plugins and other auxiliary content": the Code on
  the Go adapters (trivial, static-file; see per-app notes).
The pillars become area tickets under these as the work starts, each with explicit OK.
This ADR creates no ticket.

## Decision 1 (ACCEPTED): the core is an independent REST service in the box

The core is a standalone service that runs IN THE BOX (the Debian system), exposes
its OWN REST API, and is client-agnostic. It is not part of any one front end.

Portability is a first-class requirement: the same core runs on
- systemd IIAB hosts: AMD64 and ARM64, and
- our proot host (Android phones): ARM64 and ARM 32-bit.

Clients talk to the core over its REST API. Two clients:
- K2Go: the Android app -> dash-node (the app's proxy/BFF, which we own) -> core.
  A three-level path: app, dash-node (REST), core service (REST) in proot.
- IIAB: the IIAB admin Console -> core. We do NOT build the Console integration;
  we leave the core "ready to connect" with a documented REST contract, so IIAB
  can consume it the same way dash-node does. (The Console is not our code.)

Why this shape:
- One brain, two environments and all four architectures. The core only has to
  "speak REST": send and receive. Who calls it (dash-node or the Console) does not
  change the core.
- The core sits next to Kolibri/Kiwix/Calibre in the box: it queries their indexes
  locally, reads/writes their databases, and deletes their files.
- Each front keeps what it is good at: the app does UI/QR/transport; dash-node adds
  K2Go orchestration; the Console serves IIAB.

Scope now vs later:
- NOW: the core + its REST contract + the K2Go path (app -> dash-node -> core).
- LATER (deferred, the unknown part): how IIAB sends content, and the IIAB Console
  UI. We design the core so this is a documented extension point, not a rewrite.

### Sub-decisions (all ACCEPTED)
- D1a core runtime/language: lean Python. IIAB is Python/Ansible-native, Kolibri is
  Python, and Python is trivially portable across AMD64/ARM64/ARM32. The core
  exposes REST (e.g. Flask/FastAPI). dash-node (Node) stays as the K2Go proxy, so
  the box runs both runtimes (IIAB already runs mixed runtimes).
- D1b app<->core path: app -> dash-node -> core (Luis's call). dash-node is a thin
  relay, not a second brain. Watch the "one owner per fact" rule: the CORE owns
  "what content exists" and the export/import/delete operations; dash-node must not
  re-derive or re-own those.
- D1c (ACCEPTED) who orchestrates a long operation: NOT the core alone. The core
  is BLIND to the world the operation runs in: the phone battery, the phone's real
  free disk (the box writes into a subtree of the phone storage, so "server free
  space" is a fiction: the final disk is the phone's), the network (metered /
  offline), and even whether the box is still alive (the box can die while the app
  looks healthy). So:
  - The core exposes PRIMITIVES (list / manifest, stage-export one item, ingest-
    import one item, delete one item) and reports its own in-box sub-progress. It
    does not own "the transfer" and does not drive the whole long op by itself.
  - On K2Go the APP orchestrates: a foreground service keeps the op alive through
    Doze and the phantom-process killer; it preflights the PHONE disk, guards
    battery / metered network, and it is the SENDER (rsync runs in the app process;
    the app pulls on receive). The core feeds it information (what exists, item
    bytes, dedup answers) and does the in-box half (read / write the app DB + files).
  - On IIAB the Console runs inside the box (always-on Pi, shares the box fate), so
    it can own more of the orchestration, and the core can "know everything" there:
    the sender is the Console, co-located with the core.
  - Ownership is SHARED, and the split differs per environment. Common ground: the
    core primitive set + one progress / status vocabulary. The orchestration and the
    real-world guards live in the FRONT, sized to that front's reality.
  - Principle: whoever can see the real-world constraints owns the orchestration; the
    core owns only the in-box execution primitives. The sender is the front (app for
    K2Go, Console for IIAB), never the core.
  - Grounded by existing hybrid precedents: see "Recon: hybrid precedents" below.
- D1d (ACCEPTED: dual) how the core is updated / deployed. Each new app adapter is a core
  change, so the update cadence matters. Two mechanisms, with the tension Luis
  raised:
  - Ansible role: bakes into the rootfs, IIAB-native, but static: a change forces a
    rootfs re-bake and a slow service stop / start. Good as the baseline installer.
  - Live self-update: like dash-node's self-update (ADFA-5026 / K2GO-440), which
    diffs the installed version against origin/main and rebuilds in place, no re-bake.
    Good for shipping adapter additions to the deployed fleet.
  Lean for v1: the role installs / owns the core baseline (IIAB parity) AND the core
  carries a dash-node-style live self-update so deployed boxes pick up adapters
  without a full re-bake. Dual mechanism, mirroring what dash-node already does.

## Architecture: the pillars

### Pillar 0: shared contracts (first; freeze before forking)

Everything else consumes these. If we do not freeze them first, parallel work
diverges (repo rule: shared contracts land first). There are TWO contracts:
- Outer contract: the core's REST API (consumed by dash-node and the IIAB Console).
  Platform-facing. Decision 1.
- Inner contract: the app-adapter port (core <-> Kolibri/Kiwix/Calibre). Content-facing.
Both are defined here; the manifest and content identity are shared by both.

#### Identity semantics (grounded recon: where each id comes from, who computes it)

The identity choice is not interchangeable per app: it has collision and deletion
risk, so we confirm the SOURCE of each id before we trust it.

- Kiwix / ZIM: AUTHORITATIVE, read-not-computed. The ZIM header carries a UUID set by
  the upstream scraper at build time; it is byte-copied on download, so the same file
  always has the same UUID and we never generate it (no "same file, two UUIDs" risk).
  Our `kiwix_catalog.csv` already keys the content LINE by (creator, lang, flavour)
  and the VERSION by `date`. Dedup: same line, compare date / UUID.
- Kolibri: AUTHORITATIVE, upstream + reused. `channel_id` + integer `version` come
  from Kolibri Studio (our catalog `source` is the Studio public API). Kolibri also
  content-addresses its files by md5 in its own storage. We REUSE Kolibri's identity
  and its content-addressing: one source of truth, no second scheme, nothing
  recomputed on-device. This is the "reuse the app's own mechanism" case.
- Calibre: WEAK for cross-box dedup, and DYNAMIC. Calibre-Web is not a book store: one
  "book" record can be any media (epub, pdf, image, video, audio) and can hold several
  formats at once, so ISBN is NOT a usable key (most items have none). Split the two
  identity needs that the other apps let us conflate:
  - LOCAL identity (export / delete this exact item on this box): `metadata.db`
    `books.uuid`, generated once when the item is added. Stable within the box,
    authoritative-local, free. The autoincrement `id` is only a row number and collides
    across boxes: never use it cross-box.
  - CROSS-BOX dedup ("do you already have this?"): no native answer for arbitrary media.
    Use a SOFT, advisory key from metadata Calibre ALREADY has (zero compute):
    normalized title + authors + format + byte-size (`data.uncompressed_size`), plus any
    external id in the `identifiers` table (isbn / doi / ...) WHEN present as a stronger
    hint. Byte-size is a free, strong discriminator. idKind = soft: "looks like you may
    already have this", never an automatic skip or delete.
  - A true content hash is the only authoritative cross-box key for arbitrary media, but
    it is the on-device cost we avoid. So: opt-in "strict" mode only, computed ONCE by
    the SENDER at export and written into the manifest (like metalink for downloads);
    the receiver hashes only its OWN candidates that already collided on the soft key,
    never the whole library. Deferred, not v1 default.
  The adapter boundary CONFINES all of this to the Calibre adapter; the manifest and
  core stay app-agnostic.

Consequence: identity is TWO handles, not one, because "grab / delete this exact item"
and "is this the same content as yours" are different questions (Calibre forces the
split; the other apps happen to answer both with the same id):
- `localRef` = the app's own handle to an item on its own box (export / delete). Always
  authoritative-local, free.
- `dedup` = { line, build, version, idKind }. idKind authoritative for Kiwix / Kolibri
  (hard skip) and soft for Calibre (advisory). We never pretend a weak id is strong.

#### Manifest (draft): one small top-level file + one per-item entry

Collection-level (read first, zero ingest cost):
- `format` (e.g. "k2go-content-manifest/1"), `generated`, `source` {box, env}, `items[]`.

Per item:
- `app` (adapter key: kiwix|kolibri|calibre), `kind` (app content type), `title`, `bytes`.
- `identity`: two handles (see "Identity semantics"):
  - `localRef`: the app's own handle to THIS item on THIS box, for export / delete.
    Authoritative-local, free. Kiwix=ZIM UUID/file; Kolibri=channel_id+version;
    Calibre=books.uuid.
  - `dedup`: { `line`, `build`, `version`, `idKind` (authoritative|soft) } for cross-box
    "already have this?". Kiwix line=(creator,lang,flavour) build=UUID version=date;
    Kolibri line=channel_id build=version; Calibre line=soft key
    (title+authors+format+size [+external id]) idKind=soft.
- `integrity`: { `algo`, `value`, `source` } or null : the UPSTREAM hash (metalink
  `.meta4` / `.md5`) when present; never computed on-device by default.
- `payload`: relative path of this item's own archive (collection-open, item-atomic).
- `requires`: dependency item ids (usually empty; a Kolibri channel bundles its own
  db + files inside its payload).

#### App-adapter port (draft): the inner contract, one adapter per installed app

The single owner of "how THIS app identifies, stores, exports, imports and deletes its
content". The core and the manifest stay app-agnostic; a new app = a new adapter.

- `app()` / `isInstalled()` : the service loads an adapter only if its app is present.
- `list() -> [ManifestItem]` : enumerate indexed content with identity + size +
  upstream-hash-if-any (Pillar 1 read side).
- `check(incoming) -> Presence` : ABSENT | SAME_VERSION | OLDER_HERE | NEWER_HERE,
  using the identity envelope. Drives "you have an older version: replace or keep
  both?". For a soft id (Calibre) the verdict is advisory.
- `export(id, destDir) -> ExportResult` : write THIS item's own archive + return its
  manifest entry (Pillar 2). Per item, never a monolith.
- `importItem(entry, payload) -> ImportResult` : ingest one already-local item archive
  into the app index, reusing the app's own import where it has one (Pillar 3).
- `delete(id) -> DeleteResult` : remove the item from BOTH the app index and the
  physical files (Pillar 1). Where the app exposes its own delete, delegate to it.

Design rule: where an app has an authoritative mechanism (Kolibri content-addressing,
Kiwix library.xml + UUID), the adapter DELEGATES to it rather than re-deriving : one
source of truth. The weak case (Calibre) is isolated behind its adapter.

#### Outer contract (draft): the core REST API

Client-agnostic, same on systemd (IIAB) and proot (K2Go), localhost-only in the box
(like dash-node today); reached by dash-node (K2Go) or the IIAB Console. JSON for
metadata, octet-stream for item payloads. Prefix `/v1`. The core NEVER opens a transfer
to another box and never owns the whole operation: it is a set of per-item PRIMITIVES
the FRONT sequences (D1c). The carrier (disk / rsync) is entirely front-side, so the
SAME API serves offline-disk and network-share: only the front's carrier changes.

Discovery
- `GET /v1/apps` : installed content apps that have a loaded adapter (what the box can
  handle). Honors "aware only of installed apps".

Read (Pillar 1)
- `GET /v1/content` : the manifest of everything this box has, grouped by app, each item
  with identity {localRef, dedup}, size, title. This is what the manager UI renders
  (category + disk usage + per item). Filterable by app.
- `GET /v1/content/{app}/{localRef}` : one item detail.

Dedup (receiver-side; used by share preflight + import)
- `POST /v1/dedup/check` {items:[dedup-key]} : per item ABSENT | SAME_VERSION |
  OLDER_HERE | NEWER_HERE + idKind. Runs on the RECEIVER core (it knows what it has).
  This is the preflight the QR / offline flow needs: "what can I import, what do I
  already have". For a soft idKind the verdict is advisory.

Export (Pillar 2) : the core STAGES, the FRONT moves the bytes
- `POST /v1/export/plan` {items:[localRef]} : returns manifest entries + EXACT bytes per
  item (computes sizes, moves no payload), so the front preflights the PHONE disk and
  the user picks BEFORE a byte moves.
- `GET /v1/export/item/{app}/{localRef}` : streams that ONE item's own archive. The
  front pulls it under its foreground service + space guard, item by item, with pause /
  resume / retry per item, and writes it to the disk or sends it. The core does not
  choose the destination and does not own the sequence.

Import (Pillar 3) : the FRONT hands the core a local payload
- `POST /v1/import/validate` {manifest} : per item importable | NO_APP | dedup verdict.
  Partial import is first-class; nothing is moved. Lists what will import and what is
  skipped and why.
- `POST /v1/import/item` {app, entry, payloadPath} : ingest ONE already-local item
  archive into the app index (payloadPath is box-visible: on proot the front places it
  where the box can read it, no double copy). Per item, so the front drives the
  sequence, reports progress and retries one.

Delete (Pillar 1) : reclaim space
- `DELETE /v1/content/{app}/{localRef}` : remove from the app index AND the physical
  files; returns freed bytes. Destructive: the FRONT confirms with the user first, the
  core executes. One item at a time.

Sub-progress (reporting, NOT ownership)
- Where a single item is slow (assemble a Kolibri channel bundle, ingest one), the call
  streams chunked progress or the front polls `GET /v1/op/{id}` for THAT item's in-box
  sub-progress. The front still owns the whole operation, the foreground service and the
  real-world guards (D1c). The core never runs a background job that owns the export /
  import.

Mapping: the outer API is just the core composing every loaded adapter; each endpoint
maps 1:1 to an adapter-port operation (`GET /content`->list, `dedup/check`->check,
`export`->export, `import/item`->importItem, `DELETE`->delete). One owner per fact: the
core owns "what exists / export / import / delete"; dash-node is a thin relay, not a
second brain.

### Pillar 1: the service (query / index / delete)

In the box. Responsibilities:
- Enumerate INSTALLED apps and load the matching adapter for each.
- Expose a unified read API: what content exists, grouped by category, with sizes
  (so the UI can show disk usage by category and per item).
- Expose a delete API: remove an item from both the app index and the physical
  files, reclaiming space.
- React to the module lifecycle: when a role/module is installed or removed, add
  or drop its adapter, so the service is aware only of what is present. The source is
  the IIAB installer's own rootfs flag files, NOT a probe or a cached list: see
  "Installed-apps source" below (resolves review finding #2).
- Delete to reclaim space: DECIDED : delegate to each app's own delete, never drive the
  app DB + files directly. See "Delete semantics" below.

#### Installed-apps source (how the registry stays current; resolves review #2)

The registry must know which apps are installed and stay current as modules are
installed / removed, WITHOUT becoming a second source of truth for "what is installed".
The box already has that owner, and the Android app already consumes it (ADFA-5104 /
K2GO-393): the IIAB installer's OWN flag files on the rootfs.
- `etc/iiab/local_vars.yml`: the INTENT flags, `<key>_install: True`, written BEFORE a
  role runs and reverted on failure (so it can be falsely true after a mid-install crash).
- `etc/iiab/iiab_state.yml`: the COMPLETION markers, `<key>_installed: True`, written at
  the END of the role play, after all downloads. This is the "did it finish" truth.

Decision: the core resolves installed apps by READING these files (prefer the
`iiab_state.yml` completion marker; fall back to the `local_vars.yml` intent), restricted
to the apps the core has adapters for. NOT by probing the box (`os.path.isdir` / a live
HTTP probe), NOT by a cached list it must invalidate, NOT via the Android ModuleRegistry
(K2Go-only).

Why this is the single source, not a second one:
- The files are written by the thing that installs; reading them is reading the owner,
  not a parallel copy. Invalidation is free: they live inside the rootfs, so a reset /
  restore / clone / reinstall replaces them with everything else: nothing to clear. This
  is what the review-second-pass "no duplicate truth" rule actually asks for.
- They are IIAB's own files, present on BOTH systemd IIAB and proot K2Go, so the same
  core logic serves dash-node and the IIAB Console: environment-agnostic by construction.
  This is why #2 is a CORE decision, below the REST API, serving both clients.
- "Could not read" (null) stays distinct from "nothing installed" (empty): an unreadable
  rootfs is not an empty box.

Staying current: the completion file is small, so the core re-reads it on demand (per
request, or on an mtime change). Re-reading the owner's file is NOT a second source, so
the "do not re-derive / do not cache a parallel truth" rule (finding #2) is met: that
rule is about a PARALLEL cache, not about reading the one file.

Rule reuse, not duplication: the Android app (Java `InstalledModules` /
`InstalledModulesReader`) and the core (Python) are both CLIENTS of IIAB's flag-file
contract (`<key>_install` / `<key>_installed`), which IIAB's installer owns. The contract
lives in one place; if it changes, both readers follow. The core does not copy the app's
reader; it consumes the same upstream contract.

Supported-module contract (REQUIRED, the price of one source of truth): every module the
core supports MUST write the IIAB flags: `<key>_install` (intent, in `local_vars.yml`)
and `<key>_installed` (completion, in `iiab_state.yml`). A module that does not write them
is invisible to the core: there is no second place to look, on purpose. So a new supported
module either follows this or is changed to, before the core can see it.
Verified compliant: the Code on the Go family, which are our own roles and were the doubt
here. Both `tools/upstream-patches/overlays/roles/code_assets` and `.../code_addons`
assert `<key>_install` from `local_vars.yml`, write it to `iiab.ini`, gate their install on
`<key>_installed is undefined` (idempotent), and write `<key>_installed: True` to
`iiab_state.yml` at the END of the play. So they carry the stronger completion marker the
core prefers. No role change needed. A future custom module must match this.

OPEN (minor): the exact rootfs path the core sees for these files per environment (the
app resolves it via `SystemStateEvaluator.rootfsDir`; the core is already in the box, so
it is a fixed path there): confirm when wiring the registry.

#### Delete semantics (reclaim space; resolves the Pillar 1 OPEN)

Scope: delete an ITEM to reclaim space (K2GO-396: "free space without uninstalling the
app"), NOT uninstall a whole app. Module uninstall is a bigger, separate capability: the
box has NO uninstall today (`InstalledModules`: "A module install only ever adds. There is
no uninstall today"), and the install flags are add-only. So Code on the Go (a whole static
blob with no sub-items) has no item-level delete here; dropping it would be a module
uninstall, out of scope.

Decision: DELEGATE to the app's own delete, never drive the app DB + files directly. The
app owns its index and storage invariants; the core calls the app's delete and the next
list() reflects it (no separate state to track : one owner). Confirmed per app:
- Kolibri: delegate to Kolibri's own delete task `kolibri.core.content.tasks.deletechannel`
  (dash-node already does this: `sockets/kolibri.query.ts` deleteChannel). MANDATORY, not a
  preference: Kolibri content files are shared / content-addressed across channels, so a
  direct rm of a channel's files would corrupt other channels. The task also does the
  shared-file refcounting, so it frees only files no other channel still uses.
- Kiwix: remove the .zim file, then re-run the indexer `iiab-make-kiwix-lib` to drop the
  `library.xml` entry (reuse the KiwixManageController / ADFA-5004 path; mind the reindex
  race noted in `routes.ts`). A ZIM is self-contained, so the file is the unit.
- Calibre: `calibredb` is ABSENT on the box (recon), so delete cannot shell out to it. Use
  Calibre-Web's own remove (its API / management path), which drops the book row plus its FK
  rows (data, authors link, identifiers) and its files; a direct DB + dir removal is the
  careful fallback only if no Calibre-Web path exists. Confirm when built. (Export has the
  same constraint: with no calibredb, the core tars the book's own directory from
  `books.path`, which is what Calibre reads back.)

Reclaimed bytes: report what the APP actually freed, not the item's nominal size. Deleting
a Kolibri channel that shares files with another frees only the non-shared bytes, so an
item's `bytes` (available size) is an upper bound on what a delete reclaims, not the amount.

Lifecycle / long-op: Kolibri delete runs as an async Kolibri TASK, so delete is a long-ish
op. The FRONT owns its progress and its confirmation (destructive and outward-facing: the
front confirms with the user first, the core executes, one item at a time), and the core
reports its in-box sub-progress, the same shape as export / import (D1c).

### Pillar 2: output (export + share)

The content manager UI (served by the box, shown by the app / IIAB web): content
by category, disk usage, per-item selection. Two outputs over the SAME manifest:
- Export offline: write the archive (manifest + content + structure) to an
  external disk. FIRST GOAL.
- Selective share: rsync-style like clone, but per selected item. The QR carries
  the preflight manifest (what the receiver can and cannot import, and what it
  already has) instead of only credentials. OPEN: QR size: a full index may not
  fit in a QR, so the QR likely carries a pointer/summary and the sender serves
  the full manifest. DETAILED CONTRACT: see "Pillar Share" below.

### Pillar 3: input (import)

Read an archive or a disk, validate the manifest against the INSTALLED apps,
import what is importable (per app), report/skip the rest ("you do not have the
app for this content"), and dedup against what already exists. Partial import is
first-class.

### Cross-cutting concerns (not pillars, but they thread through all)

- Space / disparate storage (the hard problem): preflight the space, import
  partially, keep per-item granularity. This is the reason the subsystem exists.
- Unified transport: offline disk and rsync/QR should share ONE manifest and ONE
  validation path; only the carrier changes.
- Environment parity (K2Go + IIAB): a consequence of decision 1. The core must
  not assume Android.

## Offline carrier and container (fragility vs practicality)

The worry: a 20 GB monolith on a USB needs 20 GB just to LAND, then more to import, so
importing 10 GB of it needs ~30 GB. That breaks the whole granular goal.

Root cause (the key fact): the "need the whole thing locally to decide or extract" cost
comes from ONE property, not from "a single file": a SOLID / opaque container that must
be fully copied local and read sequentially. Two independent properties remove it:
- Read / extract IN PLACE from the medium (USB / OTG), never copy the archive local
  first: then you only write the items you import, never the whole archive.
- Random access, not sequential: a format with a directory lets you read the index
  cheaply and extract only selected members.
  - tar / tar.gz = sequential, `.gz` is solid: to reach the last member you process the
    whole stream. This is the trap.
  - zip = a CENTRAL DIRECTORY at the end: seek to it (KB), read the full listing without
    the body, then seek to and extract ONLY the picked members, each with its own CRC32.
    Our content (zim / mp4 / epub / pdf / webp) is ALREADY compressed, so zip STORE mode
    (no compression) is zero CPU on a Pi 3B and still gives the index + per-entry CRC32.

So neither extreme is forced. The format spectrum, ONE manifest underneath:
- v1 DEFAULT: open directory (BagIt-style). `manifest.json` (identity + per-item size +
  upstream-hash-if-present) + one content file per item in a readable layout. Browsable,
  usable without the app (open the mp4 / epub directly), per-item import (copy only what
  you pick: no 30 GB), validate-and-skip on tamper. Carrier = USB / OTG read in place.
- OPTION (later): a single STORE-zip (manifest as the first entry + central directory).
  For the user who wants one tamper-resistant file: still index-readable and
  partial-extract-in-place, so it does NOT bring back the 30 GB cost. Same manifest.
- OPTION (later): import-from-URL. Any static HTTP server with Range support (Python
  `http.server` has it since 3.7): fetch the small manifest, then HTTP Range GET only the
  selected items. The network twin of zip random access / reading loose files in place.
  (Between two phones the QR / rsync path wins; URL import shines for a laptop or a USB
  server holding the content.)

We are NOT reinventing: "loose content files + a manifest with per-file checksums,
validate-and-skip" is the mainstream pattern.
- BagIt (LoC RFC 8493): a spec for exactly offline file transfer with a manifest +
  checksums for fixity (`bagit.txt` + `manifest-sha256.txt` + `data/`). Almost our design.
- APT / Debian repos: tiny `Release` (hashes + sizes) -> `Packages` (per-.deb hashes +
  sizes) -> loose `.deb` in `pool/`. Read indices, validate, fetch only what you pick.
- OCI image layout (docker save to a dir): `index.json` + content-addressed
  `blobs/sha256/<digest>` loose files; copy around offline, pull partially; the
  content-address = automatic dedup + integrity + self-detecting moves.
- git-annex: built for large files over sneakernet (USB transport drives are first-class):
  a light manifest + checksum-named content, sync only selected items, integrity by name.
- OPDS, and Kiwix (`library.xml` + loose `.zim`) and Calibre (`metadata.db` + loose
  `Author/Title/` files): the apps we integrate ALREADY use manifest + loose files.

Fragility mitigations (cheap, standard, in v1):
- The manifest is the authority: a missing / renamed / changed item is reported and
  SKIPPED, never a hard error (the rest import fine).
- Per-item size + upstream-hash-if-present = a fixity check at import, zero on-device
  compute by default (BagIt-style).
- Name each content file with its native id / version (not only a friendly name), so a
  careless rename cannot silently swap one item for another and a move is detectable.
- Optional: a checksum of the manifest itself (tiny) to detect an edited manifest.

v1 accepts limits and unfolds (endorsed):
- v1: open directory + USB / OTG + offline import + delete-to-reclaim; soft dedup; no
  on-device hashing. This alone fully solves the 30 GB problem (item-atomic copy).
- v1.x: the store-zip container option; import-from-URL (Range); strict-mode hashing;
  selective network share (QR / rsync per item).

## Resilience: pause / resume / retry (sender and receiver)

A phone transfer is interrupted constantly: the battery dies, the screen locks (Doze),
the user switches network (join a hotspot, then a 2.4 GHz AP, then find a 5 GHz AP to go
faster), the app is backgrounded or killed. BOTH ends must pause, switch, and continue
where they left off : never restart from zero. This is FRONT-owned (D1c); the core only
serves restartable primitives.

Advantage we have: WE are the source. The files already exist on the sender (we do not
fetch them from the internet), so there is always something to resume from : no upstream
to re-request, no re-download.

Per-item op-state, persisted by the FRONT (durable across process death, like the install
banking K2GO-408 / 187):
  PENDING -> COPYING(offset) -> COPIED -> IMPORTING -> DONE | FAILED(retryable)
- The ITEM is the unit of resume: an interrupted item is retried on its own; the rest are
  untouched. v1 does per-item retry (re-do the interrupted item).
- Within-item byte resume (continue a half-copied big file from its offset) uses the
  mechanisms we already run: rsync partial-tree resume (clone), aria2 `--continue`, HTTP
  Range. v1.x adds this so a 4 GB item does not restart from zero.

Sender side (export / share): `GET /v1/export/item` is restartable (Range), so the front
pauses, drops the carrier, switches network, and resumes the SAME item from its offset.
The core holds no transfer state; the front does.

Receiver side (import): a partially-copied item is re-copied (Range / rsync) then handed
to the core; ingest of ONE item is idempotent (re-import replaces / dedups, never
double-counts). The front banks the queue, so a battery death resumes the queue, not
restarts it.

Network change mid-transfer: the front re-establishes the carrier and resumes from the
last offset; it must NOT silently restart or (for ADB) re-pair : K2GO-173 is the
cautionary precedent (reconnect silently re-triggered pairing).

Precedents reused (see "Recon: hybrid precedents"): the foreground service (Clone
WatchdogService), durable app-side progress (DashboardRebuildService), per-item retry +
resume (RestContentClient, aria2). We are not inventing a resume engine; we drive the
existing ones per item.

## Decisions status (pointers; detail lives in ONE place above)

To avoid a duplicate-truth in this doc, each decision's detail lives in one section:
- Core in the box + D1a-d: "Decision 1" + the open-questions log. ACCEPTED.
- Content identity / dedup: "Identity semantics" (localRef + dedup + idKind). DIRECTION
  SET; Calibre residual confirmed when its adapter is built.
- Archive format + offline carrier: "Offline carrier and container". DIRECTION SET
  (open directory v1; store-zip + URL carriers later). The ITEM is the atom; a SOLID
  monolith is rejected, but a store-zip (random-access directory) is a valid later option.
- Transport unification (one manifest, many carriers): DECIDED (same section).
- Delete semantics: DECIDED. Delegate to each app's own delete (Kolibri deletechannel
  task, Kiwix rm + reindex, Calibre-Web remove : calibredb is ABSENT on the box);
  item-level only, module uninstall out of scope (box has no uninstall). See "Delete
  semantics".
- Pillar 2 export BUILT (fork, commit 777d401c): POST /v1/export/plan + GET
  /v1/export/item streaming, per item. Per-item archive shape RESOLVED: Kiwix = the .zim
  streamed as-is; Calibre = tar of the book dir (books.path; calibredb absent); Kolibri =
  tar of `kolibri manage exportchannel` + `exportcontent` staged output (content-addressed,
  so import dedups shared files). Export staging must live on the content volume, not /tmp,
  and preflight the BOX free space before staging (code-review finding). Code-review done
  (5 findings, fork applying fixes: HTTP/1.1 for chunked, per-member OOM, staging dir, body
  cap, e2e test).

## Build order and the first deliverable

The first user-visible deliverable is OFFLINE IMPORT (plug an external disk, pick,
import). It sits on top of the whole stack, so the build order is bottom-up:

1. Pillar 0 contracts (manifest + identity + adapter port + core REST API).
2. Pillar 1 service + the three adapters (read side first: list + identity + size).
3. Pillar 2 export to disk (write the archive) : the other half of the disk round trip.
4. Pillar 3 import from disk (validate + partial + dedup) : the deliverable lights up.
5. Then: selective network share (rsync/QR), delete-to-reclaim, the manager UI polish.

So "install from offline" is the first thing that works end to end, but only after
the stack beneath it exists.

## Work plan (session topology)

A CONFIRMED (contracts first, then 2 forks). B and C kept for context.

- Option A (CHOSEN): contracts first, then fork. This session owns Pillar 0
  (freeze the contracts + this ADR). Then two forks: Fork-Service (Pillar 1 + the
  three adapters) and Fork-Flows (Pillars 2+3 + archive format + manager UX), both
  building against the frozen contract, syncing through this session.
- Option B: three parallel forks now (Service / Output / Input) + this session as
  integrator. Faster in appearance, but the forks design against a contract that
  is not yet agreed, so they diverge.
- Option C: one session, sequential. No divergence, less parallelism.

## Per-app notes (identity confirmed by recon; ops TO DETAIL)

- Kolibri: content DB (channels/nodes), import/export already touched in
  K2GO-475/476. Identity + read CONFIRMED on the box: `/library/kolibri/db.sqlite3`
  (opened read-only), channel_id (32 hex) + integer version, size = SUM of DISTINCT
  available local files (a file can hang off several nodes; a flat join double-counts).
  The adapter reuses the same source and aggregation dash-node uses
  (`static/dashboard/sockets/kolibri.query.ts`): one source of truth. Delete + export:
  via Kolibri's own tools vs direct DB (TO DETAIL, Pillar 2/3).
- Kiwix: ZIM files. Identity CONFIRMED: ZIM UUID (book id) + `library.xml`; the dated
  filename carries the version axis. Export = the ZIM file; delete = remove the file +
  the library entry (the existing KiwixManageController path, ADFA-5004).
- Calibre-Web: NOT book-only : arbitrary media (epub/pdf/image/video/audio), multi-
  format per record. Local identity = `metadata.db` `books.uuid` (free, authoritative-
  local). Cross-box dedup = SOFT key (title+authors+format+`data.uncompressed_size`
  [+ `identifiers` when present]); content hash opt-in / sender-computed only. Read
  CONFIRMED on the box: `metadata.db` opened read-only (the
  `static/dashboard/sockets/books.query.ts` pattern), reading `books.uuid`, `data.format`
  / `data.uncompressed_size`, `authors`, and one `identifiers.val` as the optional
  stronger hint. Note our `books_catalog.jsonl` (Get More) carries the weak local `id` +
  title/author: that is the download catalog, not the installed-library identity.
- Code on the Go family (code-assets, code-addons): the TRIVIAL case : static files, no
  database, no index semantics. Adapter = enumerate the served tree, copy / drop / remove
  files. Identity = path + the per-file `.md5` the mirror already ships (reuse, no
  compute). Proves the adapter model scales DOWN as well as up (K2GO-494). "Moving files
  for the sake of moving files."
- Integrity source CONFIRMED: metalink `.meta4` ships upstream sha256 + size (used
  today for rootfs and maps); reuse it in the manifest, do not recompute on-device.

## Recon: hybrid precedents (why the front co-owns long operations)

Every heavy or long operation on K2Go has ALREADY been forced out of the pure
in-box / Ansible-role model: the APP had to co-own it, because the backend two
levels down (under proot, sometimes with the system stopped) is blind to the phone
real world and can die without the app noticing. Evidence in the current code:

- Disk guard (K2GO-386): the in-box healer "dies with the box: only an Android-side
  guard, independent of the box, catches" a disk-fill (`diskguard/domain/DiskGuardPolicy.java`
  header). The app reads the phone real free space (`StatFs`) and reserves a 2 GiB
  op-floor (StorageGuard) before every app-driven heavy op. So the box view of free
  space is a fiction; the phone owns the real-space truth. Direct support for D1c.
- Clone (`redesign/CloneFragment.java` + the rsync TransportEngine + WatchdogService):
  the APP runs the rsync transport in its own process and holds a FOREGROUND service
  to stop Android SIGKILLing rsync (exit 137, the phantom-process killer). The app
  builds the QR handshake and is the SENDER. The backend does not move the bytes.
- Maps (K2GO-393 / 394): the heavy base-map (pmtiles) download was pulled OUT of the
  Ansible role into the dash-node durable job engine (front-driven); the role in-proot
  task only asserts the files are present and skips (`creates:`). The role cannot do a
  heavy live download under a stopped system; the front does it, the role finalizes.
- Content download / Get More (`content/RestContentClient.java` + the per-area
  foreground download services: Kolibri / Addons / CodeAssets / Forgejo seed): the APP
  drives long in-box downloads via a foreground service + POST / poll, with per-item
  retry and network-policy gating. The box engine does the fetch / resume / checksum,
  but the APP owns the lifecycle, the user-facing progress, and the guards.
- Rebuild (`DashboardRebuildService`, K2GO-383 / 451): the APP is the persistent
  progress owner (survives fragment recreation and minimize), drives the notification,
  and self-heals rebuild-zombies: the box-side rebuild cannot report durable progress
  to the user by itself.

Design consequence for the core: it must be a set of PRIMITIVES the front drives, not
a self-contained in-box job runner. The content-exchange subsystem reuses these exact
seams (foreground service, phone-disk preflight, app-owned transport, durable
app-side progress) rather than re-inventing an in-box owner that cannot see the phone.

## References (existing patterns to reuse)

- Clone / rsync selective share: redesign/CloneFragment, RsyncManager (the
  all-or-nothing baseline this generalizes).
- dash-node: the in-box REST service pattern the app already consumes
  (static/dashboard).
- Backup / restore + manifest validation: RootfsArchiveValidator, the rootfs
  identity manifest (.iiab-rootfs.json, docs/ROOTFS_MANIFEST.md) : precedent for a
  manifest-validated archive.
- Content download durable job engine (RestContentClient, the dash-node jobs) :
  how the app drives long in-box operations.

## Pillar 4: the K2Go front (APK) and the content manager

The backend (Pillars 0-3) is in main. The user-facing deliverable of K2GO-427 lives in the
APK: one content MANAGER with per-item actions, reusing the Clone / Get More lessons.

### The manager (gestor): the home
Content grouped by app / category, each item with its size; a total used and the FREE disk;
per-item multi-select. From a selection the user ACTS. One surface: export / import / share /
delete are actions on it, not separate screens.

### Actions (the modes)
- EXPORT (to a connected drive): write the OPEN-format bundle (manifest + one archive per
  item) to a USB / OTG drive. Open on purpose: the content stays browsable, not a black box.
  Core: POST /v1/export/plan + GET /v1/export/item; the app owns the disk IO, the foreground
  service and the phone-disk preflight.
- IMPORT (from a connected drive): read the drive's manifest, show per item importable /
  dedup verdict / NO_APP, select, import (partial first-class). The receiver half of export.
  Core: POST /v1/import/validate + /v1/import/item.
- SHARE (phone to phone): clone-like. A QR handshake carries the preflight manifest (what the
  receiver can / cannot import, what it already has), then a per-item rsync-style transfer.
  Granular Clone. Reuses the Clone transport + QR + the receiver's /v1/dedup/check.
- DELETE (reclaim space): drop selected items; the core delegates to each app's own delete.

### Shared infra (reuse, do not reinvent)
Foreground service (the Clone WatchdogService pattern), a per-item queue with pause / resume /
retry (the resilience design), progress UI (bar, %, ETA, bytes, speed), the PHONE-disk
preflight (StatFs, app-side), the QR handshake, Material 3, and the layered architecture.
These are the Clone / Get More / rebuild lessons, applied per item.

### Android storage constraints (recon: what we can and cannot read / write)
- Reading disk SIZE is NOT restricted. `storage/StorageProbe` (StatFs getAvailableBytes /
  getTotalBytes) is the app's shared, in-production free/total probe (ADFA-5105, used by the
  ZIM / Maps / Kolibri screens). The manager's FREE-disk figure comes from StorageProbe, not
  from the core: on K2Go the app owns the phone-disk truth (D1c). The core's `/v1/storage`
  gives content totals (usedByContent, perApp) and the box free view (authoritative for IIAB).
- The REAL restriction is external-DRIVE (USB / OTG) access for export / import, and it is
  FLAVOR-dependent: the app declares MANAGE_EXTERNAL_STORAGE (R+) / WRITE_EXTERNAL_STORAGE
  (<=29), but the PLAY flavor REMOVES MANAGE_EXTERNAL_STORAGE (K2GO-442:
  `permissions/StoragePermission.isRequired` self-corrects, "treat storage as optional");
  standard / fdroid keep it. And a USB / OTG volume on modern Android is reliably reached only
  through SAF (the system document picker), even with broad access.
- Decision (OPEN, Luis's call): the export / import drive IO uses SAF (DocumentFile + an
  ACTION_OPEN_DOCUMENT_TREE picker) : portable across ALL flavors incl. Play, and the reliable
  path to a USB drive. The alternative (raw File paths on the broad-access flavors only) is
  simpler but excludes Play and is unreliable for USB OTG. Lean: SAF. This shapes the Export /
  Import slices (2 / 3), not the manager (slice 1). On-device SAF-sees-the-USB check is a
  slice-2 verification (needs a physical drive on the 7T).

### First pass (proposal): the DISK round-trip + the manager
The K2GO-427 story is "install content from a connected drive", so the first pass is the DISK
carrier end to end: Manager (list + sizes + free disk + multi-select) + EXPORT-to-disk +
IMPORT-from-disk + DELETE-to-reclaim. DEFER SHARE-over-QR (the network carrier) to a second
pass: same manager, a different carrier. (A tighter first pass is Manager + Import + Delete,
with Export right after. Luis's call.)

### Core gaps the front reveals (fill in the core FIRST)
Two core capabilities the manager needs that do not exist yet:
1. DELETE endpoint: `DELETE /v1/content/{app}/{localRef}` -> { ok, freedBytes }. Designed
   (delegate to the app's own delete: see "Delete semantics"), not coded.
2. STORAGE-STATUS endpoint: `GET /v1/storage` -> { volume, totalBytes, freeBytes,
   usedByContentBytes, perApp: [{app, bytes}] }. The core has per-item sizes but no storage
   summary (box disk via shutil.disk_usage; content totals from the adapters). The PHONE's own
   free disk stays app-side (StatFs).
dash-node needs NO change for these: its relay is generic (`apiRouter.use('/v1', conexProxy)`),
so any new `/v1/*` core endpoint is auto-exposed. dash-node changes only if a path needs
non-relay logic (none here).

### Import flow design (the receiver slice, mirror of Export)
The receiver half of the disk round-trip. Reuses Export's infra: the SAF carrier port (the
reader is the mirror of Export's writer), the foreground service, the progress UI. No new
permission (SAF). Core endpoints (all exist): POST /v1/import/validate, POST /v1/dedup/check,
POST /v1/import/item, and DELETE (for the replace-older flow).

1. SAF: an ACTION_OPEN_DOCUMENT_TREE picker -> the user selects the drive/folder holding the
   bundle -> DocumentFile read.
2. Read the top-level manifest.json from the tree (a few KB: the collection-open point is you
   read the manifest WITHOUT ingesting the payloads). Parse items[].
3. Validate against THIS box: POST /v1/import/validate {manifest} -> per item importable |
   NO_APP | dedup verdict (ABSENT/SAME_VERSION/OLDER_HERE/NEWER_HERE + idKind). The UI groups
   the manifest items and marks each: "can import", "already have (same)", "newer available",
   "older than yours", "no app for this (skipped)". A soft (Calibre) verdict is advisory.
4. The user selects what to import (partial first-class). PHONE-DISK PREFLIGHT here is the
   crux of the disparate-storage problem: StorageProbe (StatFs) gives the phone's free space;
   the UI shows "selected total X / free on device Y" and warns / disables when it will not
   fit, so the user picks what fits (a small phone takes a subset of a big drive).
5. Import the selected items, per item (the carrier streams FROM the drive; the core ingests):
   - SAF-read the item's archive and write it to the box-visible import-staging path the core
     reads (registry.import_root, app-private storage the app can write), then
     POST /v1/import/item {app, entry, payloadPath} -> the core ingests it (delegating to the
     app's own import: Kolibri importchannel, Kiwix place + reindex, Calibre-Web upload).
     Remove the staging copy after ingest. Per item -> partial + retry; skip items the user
     deselected or that validate SAME_VERSION.
   - The "already have / newer / older" dynamic (Luis): SAME -> skip; NEWER incoming over an
     OLDER_HERE -> offer "replace" (import the new, optionally DELETE the old via the delete
     endpoint, the user's choice: keeping both is allowed, it costs double space); OLDER
     incoming -> default skip with a "you already have a newer one" note.
6. A FOREGROUND service owns the queue + progress (reuse Export's), with per-item
   pause/resume/retry (receiver-side resilience: battery dies -> resume where it left off, not
   restart). ENOSPC on a copy/ingest -> stop/skip that item gracefully.

Note: on K2Go the import writes into the phone storage (the box is a subtree of it), so the
box-side import preflight (registry.import_root) and the app's StorageProbe are the SAME
physical disk; the app's StorageProbe is authoritative (Android's real view).

### Build topology (contracts first, then fork : same as the backend)
- Fork-core (now): implement the two endpoints above against these shapes. Independent of the
  UI scope; unblocks the app.
- Fork-app (after the first-pass scope is set): the manager + the disk actions, against the
  frozen core contract. Android, layered, Material 3.
- l10n gate (Luis, 2026-10-22): the manager is delivered across slices whose strings still
  shift as the actions land, so UI strings stay parked in `strings_untranslated.xml` during
  the slices and migrate to `strings.xml` (all 33 locales) IN THE CLOSING PR of the feature,
  not per slice. The closing slice's checklist: migrate the strings, verify light/dark
  on-device, and the 2nd code-review pass.

## Open questions log

- Decision 1 (core in the box): ACCEPTED.
- D1a (core runtime = lean Python): ACCEPTED.
- D1b (app -> dash-node -> core path): ACCEPTED.
- D1c (long-op orchestration): ACCEPTED. Shared / front-owned; the core owns only
  in-box primitives, the front (app / Console) is the sender and the guard.
- D1d (core update): ACCEPTED. Dual: Ansible-role baseline + dash-node-style live
  self-update.
- Dedup identity per app: DIRECTION SET. Two handles: `localRef` (export/delete, free)
  + `dedup` (cross-box). Kiwix + Kolibri authoritative. Calibre (arbitrary media, no
  ISBN): localRef = books.uuid; dedup = SOFT key (title+authors+format+size) + hash
  opt-in. CONFIRMED on the box for all three (Kiwix/Kolibri/Calibre); field names and
  soft-key normalization implemented and tested.
- Pillar 1 read side BUILT (fork, feat/K2GO-427-conex-core-read): Kiwix + Kolibri + Calibre
  adapters, 43 tests, verified on-device. Transitional note: the Kolibri/Calibre adapters
  reuse dash-node's own DB queries' shape (kolibri.query.ts / books.query.ts), so the
  listing SQL now exists in both dash-node (TS) and the core (Python). Both read the one
  authoritative DB, so it is not a duplicate SOURCE, but it is a duplicated query rule; the
  end-state (D1b: the core owns "what content exists", dash-node is a thin relay) collapses
  it by routing dash-node's content listing through the core. Track for the consolidation.
- Archive format: DIRECTION SET (collection-open, item-atomic: one manifest + one
  archive per item). Per-item shape RESOLVED in Pillar 2: Kiwix = the .zim as-is; Calibre =
  tar of the book dir; Kolibri = tar of exportchannel+exportcontent output.
- Pillar 0 FROZEN (2026-10-22): identity envelope (localRef + dedup/idKind), manifest,
  adapter port (inner), core REST API (outer). Later pillars build against it (topology A).
- Session topology: A CONFIRMED (contracts first, then 2 forks). Fork 1 (read side)
  building on feat/K2GO-427-conex-core-read.
- Installed-apps source (review #2): DECIDED. The core reads IIAB's own rootfs flag files
  (`iiab_state.yml` completion, fallback `local_vars.yml` intent), the same single source
  the app uses (ADFA-5104 / K2GO-393); a CORE decision serving both dash-node and IIAB.
  Read side slice ships it as a documented startup snapshot; the live re-read is the next
  Pillar 1 step. See "Installed-apps source".
- Does the QR carry a manifest pointer/summary, with the sender serving the full
  index?

## K2GO-507: export as a liveness-paced long operation (prepare / poll / fetch)

Problem (device pass, A16, 2026-10-23): `GET /v1/export/item` calls the adapter's
`open_export()` and the server sends the 200 + chunked headers only AFTER it returns.
For Kolibri, `open_export` stages the whole channel synchronously (the Kolibri export
CLI into a `cxkolibri-*` temp) before returning, so a 2.8 GB channel takes minutes; the
app client (`openExportItem`, 20 s read timeout) times out in `getResponseCode()`. Kiwix
(`file_stream`) and Calibre (`tar_stream` of the book dir) stream directly, fast first
byte, so only large staged items fail.

Decision: replace the single blocking export-item request with a long operation, uniform
for all apps (no per-app special case, no alias: nobody uses the feature yet). The front
owns the sequence (D1c); the core exposes primitives. The timeout becomes a client
inactivity (stall) timeout, not a wall clock: the op is alive while it makes progress.

Contracts (outer REST, through dash-node /v1 relay):
- POST /v1/export/prepare {app, localRef}
  -> 200 {opId, state, totalBytes}. state is "staging" (work started in a background
  thread) or "ready" (nothing to stage: Kiwix/Calibre). 404 item absent/not exportable;
  507 when the staging disk cannot hold totalBytes (reuse has_room).
- GET /v1/export/status/{opId}
  -> 200 {state, stagedBytes, totalBytes, error?}. state: staging | ready | error.
  stagedBytes = du of the op temp: the liveness heartbeat AND the prepare-phase progress.
  For "ready", stagedBytes == totalBytes. 404 unknown opId (swept) -> client re-prepares.
- GET /v1/export/fetch/{opId}
  -> 200 chunked octet stream of the staged tar (Kolibri) or the direct stream
  (Kiwix/Calibre). Content-Disposition carries the suggested name. on-close removes the
  op temp and drops the op. 409 when state != ready.
- DELETE /v1/export/{opId}
  -> 200 {ok}. Client abort: drop the op and remove its temp.

Op lifecycle (one owner per transition; the hard part, per the design-coherence rule):
- CREATE: prepare allocates {opId -> temp, state, totalBytes, error, lastSeen} in an
  in-memory registry and starts the staging thread (or marks ready).
- CLEAR, exactly one of: (1) fetch on-close (normal completion); (2) DELETE (client
  abort); (3) a TTL sweep of ops whose lastSeen (bumped by status/fetch) exceeds a
  timeout, so an abandoned prepare never orphans a multi-GB temp; (4) a STARTUP sweep of
  {staging_dir}/cxkolibri-* (and stray op temps) on conex-core start, for ops lost to a
  process death.
- The registry is in-memory; a conex-core restart loses ops (acceptable: the front
  re-prepares; the startup sweep reclaims orphan temps). Matches the stateless core and
  avoids a durable op-state. One source of truth: the filesystem for bytes, the registry
  for the in-flight set.

Single source of truth: op state + stagedBytes are owned by the core. The app's per-item
phase (prepare | write) is a PROJECTION read from the status poll; the app never stores a
competing "where is this export". No duplicate truth.

Resilience: v1 recovers by RE-PREPARE (not re-attach). On app death or core restart the
opId is lost; the client prepares a fresh op and the old is swept. A later enhancement can
persist the opId and re-attach (the RestContentClient pattern: POST + poll, re-findable
job); not required for v1.

App side: ConexCoreDataSource gets postExportPrepare, getExportStatus, openExportFetch (the
fetch uses a STALL read timeout that resets on each read, not a 20 s wall clock; the
foreground service + Cancel are the control). drain() becomes: per item, prepare -> poll
status (feed the prepare-phase bar + liveness) until ready/error -> fetch -> copy
(write-phase bar). ExportSession/ExportJob gain a per-item PHASE and a prepare percent
(stagedBytes/totalBytes); ExportProgressFragment shows the two-phase per-item bar (~50/50).

conex-core version (folded in, this ticket already changes the core): add
conex_core.__version__, expose GET /v1/version {version} (and optionally in /v1/apps), and
have the conex_core role write it to the [conex_core] section of iiab.ini, mirroring dash-node's
package.json version and /system/version, so a box can report which core it runs.

Reuse: RestContentClient POST+poll (content/RestContentClient, ADFA-4893/4897) is the
client precedent; tar_stream/file_stream (export.py) and _stage_export (kolibri.py) are
unchanged primitives the fetch reuses. Build order: conex-core endpoints + registry + sweep +
version first, then the app client + two-phase progress; integration verified on the A16
(export a 2.8 GB Kolibri channel end to end).

## Pillar Share: granular wireless send (network carrier + QR handshake)

Status: DESIGN (deferred from v1; the v1.x "selective network share" in the carrier spectrum
above). Anchor ticket K2GO-376 (granular share). The Share button exists in the manager,
disabled ("not available yet"). This section is its contract; no new ticket from this ADR.

The key fact (already decided under cross-cutting "unified transport"): Share is the NETWORK
TWIN of Export. Same bundle, same manifest, same validation and import path: ONLY the carrier
changes. The two carrier ports were built for exactly this (ExportCarrier / ImportCarrier
doc: "a future network carrier reuses the same port, so it is never hard-wired into the
sequence"). So Share adds a transport and a pairing step, NOT content logic.

### Topology (ACCEPTED): the receiver PULLS from the sender

The bundle format is already pull-shaped: import reads a manifest, then streams only the
payloads it picks. So Share does not push: the SENDER exposes its selected items, the
RECEIVER runs the existing import flow against a network source. Reuse, do not reinvent: the
sender's in-box conex-core already serves the export long operation (POST /v1/export/prepare,
GET /v1/export/status, GET /v1/export/fetch: K2GO-507) plus the manifest; the receiver
fetches those over the WLAN instead of over the loopback relay. No new server: the sender's
conex-core is the share server, scoped to the shared item set; the receiver's app points its
import at the sender's address.

### The carriers

- Sender: nothing new on the content side. It selects items (the same selection as Export)
  and makes them fetchable: its conex-core already stages and streams per item (prepare / poll /
  fetch). Share only opens that conex-core to the paired receiver on the hotspot / WLAN, scoped
  to the shared items plus the session token, never the whole API.
- Receiver: a network ImportCarrier (the mirror of SafImportCarrier): readManifest() and
  openPayload(name) become HTTP GETs (with Range) against the sender, instead of SAF reads.
  Everything downstream is unchanged: validate against the INSTALLED apps, dedup, partial
  import, stage to /library/.conex-staging, POST /v1/import/item.

### Pairing (ACCEPTED): reuse the sync QR handshake

The box-to-box sync handshake already exists and is hardened: SyncHandshakeHelper encodes
host / port / credentials in a QR, validated by SyncCredentialValidator (the security slice
already shipped). Share reuses it:
- The sender shows a QR carrying: its hotspot / WLAN address + port, a short-lived session
  token, and a POINTER to the bundle manifest (NOT the full manifest: a full index does not
  fit in a QR, the OPEN item from Pillar 2, resolved here by pointer + served manifest).
- The receiver scans, joins the sender's hotspot / WLAN (Connect's medium), fetches the
  manifest over HTTP, then proceeds with the existing import review and flow.

### Pairing is a pre-step, not the contract (ACCEPTED: v1 UX phone-to-phone, contract peer-agnostic)

Establishing the network (who is on the same LAN) is SEPARATE from the transfer. An IIAB box on a
wired LAN already has the network, so it just transfers; two phones must first bring one up (hotspot /
wifi). That pre-step is Android's job and REUSES Connect / Clone's existing join flows (Connect is 2
steps; Clone is 2 or 3, depending on wifi vs hotspot). In every one of those flows the LAST step is the
same: hand over the info to actually access (Connect: reach the service; Clone: the QR to pull). The
Share contract IS exactly that last step: the final QR carries the /v1/share address + token + manifest
pointer, and the pull follows. Everything before it (bringing up the network) is outside the conex core,
the way plugging the Ethernet cable is not part of content exchange.

Consequences:
- The /v1/share contract + the network ImportCarrier are PEER-AGNOSTIC (plain HTTP + manifest + token).
  v1 only builds and tests the phone-to-phone UX; a laptop / server (IIAB box as sender, or URL import
  as receiver, "URL import shines for a laptop" above) is a later carrier, NOT excluded, and needs no
  core change.
- Step 1 (the /v1/share contract + network carrier) is pure core + loopback and needs NO
  network-establishment work. The Android pairing is a separate PRESENTATION slice that reuses
  Connect / Clone and owns the "do not leave the hotspot on" lifecycle. The core never toggles the
  hotspot.

### Free space lives on the RECEIVER (ACCEPTED)

The relevant free space for a send is the RECEIVER's, not the sender's. After fetching the
manifest (which carries per-item sizes), the receiver runs its OWN preflight with pieces that
already exist: ImportPreflight.fits(selectedBytes, StorageProbe.freeBytes(receiver)) plus the
installed-apps validation and dedup. The sender never needs the receiver's disk figure: the
receiver decides what it can take, item by item (partial is first-class). This is the "space
in Share" answer: the receiver-side import preflight on the fetched manifest. (Related but
separate gap for Export: the export flow does not preflight the DESTINATION drive's free
space today, it only reacts to ENOSPC mid-write. A destination preflight is a small
standalone improvement, not part of Share.)

### Resilience (reuse)

Per-item pause / resume / retry is already the model (see Resilience above), and the sender
is the source, so a send is always resumable: a dropped WLAN connection re-fetches the failed
item (HTTP Range or re-request the item), the rest stand. The receiver's import is already
per-item and partial.

### Reuse ledger (do not reinvent)

- SyncHandshakeHelper + SyncCredentialValidator: QR pairing + validation.
- Connect: the hotspot / WLAN as the transport medium.
- conex-core /v1/export/* (K2GO-507) + the manifest: the sender serves these; no new server.
- ContentImportService + the import flow: unchanged on the receiver.
- StorageProbe + ImportPreflight: the receiver-side free-space preflight.
- Clone is the whole-box twin; Share is Clone scoped to the selected items.

### Gate / token lifecycle (closing the two-party pincer): ACCEPTED

The failure to avoid (Clone's gap, confirmed in the next subsection): the host exposure is "open
until the host user closes it", with no deadline and no signal from the remote. One jaw only: if the
user walks away, it stays open. Share inverts the default: the gate EXPIRES by default, and either
party can close it.

The gate separates TWO lifetimes (the refinement that makes large, resumable transfers work).
Collapsing them into one idle TTL was the first-cut error: a multi-hour transfer that pauses would
be reclaimed mid-way.

CONNECTION lifetime (ephemeral, Layer 1): the idle TTL and the in_use pin belong to ONE open stream,
not to the session. A dead socket is reclaimed after the idle TTL (~10 min); a live stream is pinned
so the sweep never drops a temp under it (reuse last_seen / in_use / _sweep_expired from the export
op). Per-item resume = re-fetch that item (the export op re-stages). The connection carries the
token; the session never learns the client's IP, so a reconnect from a new IP (DHCP, AP change) with
the same token is just a new connection on the same session. IP-resilience is therefore free in the
core; re-finding the host at a new IP is the app's job (re-emit the QR with the current address).

SESSION lifetime (durable, Layer 2): the session holds the token (the durable transfer handle +
resume code), the scoped item set (the "selection") and the per-item delivered state. It does NOT
expire on idle. It lives until ONE of:
  : COMPLETE : the receiver signals it has every item (after a quick final verification) : the clean
    success close; the host then releases the protection.
  : CANCEL (DELETE, by host OR receiver) : abort; drops the protection and any in-flight stream. The
    sender's foreground share service calls DELETE in onDestroy (the app-side closer).
  : a long SAFETY CAP (hours), so a truly abandoned session is eventually reclaimed : NOT the
    ~10-minute connection idle.
  : process death : the session is in-memory (v1 DECISION), so a restart loses it and sweep_startup
    reclaims the child temps on boot.

v1 storage DECISION: in-memory + a long safety cap. The session metadata is tiny (token, a list of
(app, local_ref), a delivered set); the payloads stream from on-disk export temps, so there is NO
OOM risk from the registry. Persisting the selection to a disk file is a v1.x move purely for
cross-restart resume durability, to revisit by real-world performance, not by memory pressure.

"Protection" = the Layer-2 session being open: while it is open, the token holder can fetch the
selection; CANCEL closes Layer 2 and with it the transfer. Resume hours later = the same token: the
host re-emits the QR (current address + same token) and the receiver continues, skipping items
already delivered.

delivered is set ONLY on a CLEAN item close (full byte count), never on a mid-stream drop, so a
dropped item stays pending and resumes; the receiver drives the close with COMPLETE, not an implicit
drop-based auto-close. The manifest surfaces per-item delivered as an ADVISORY hint (progress +
what the sender streamed cleanly): it is NOT the receiver's resume authority. The receiver skips an
item only when its OWN persisted state has it, since the sender marking an item delivered (full body
written to the socket) does not prove the receiver saved it. This matters for the step-2 receiver
fork, not for the loopback core slice.

The two-party pincer still closes (no actor's absence leaves it open): the receiver closes it
(COMPLETE or CANCEL), the host closes it (CANCEL / the foreground service onDestroy), and the host
self-bounds it (the safety cap + startup sweep). The connection idle TTL bounds a dead socket
independently. The token is bound to the session (revoking the session revokes the token) and scoped
to the item set, so there is no separate credential lifecycle to leak (the leak Clone mitigates with
RsyncManager.stop() clearing the secrets file). Default = closed; staying open requires an active
session, bounded by COMPLETE / CANCEL / cap.

Ownership (app vs cx): the CORE owns the durable session (token + selection + delivered state,
IP-agnostic), the connection idle TTL, and the endpoints (create / manifest / fetch-with-resume /
complete / cancel / the safety cap). The APP owns the pairing (hotspot / wifi + QR), re-discovering
the host at a new IP, re-emitting the QR with the current address + same token, the background
foreground service + the client retry loop, and the final user-facing "done" confirm.

### Why the pincer closes here but not in Clone (transport paradigms): analysis

The root cause of Clone's gap is the TRANSPORT, not an oversight. Two paradigms:

- Machine OFF (Clone): CloneFragment stops the server (stopApkServer, ServerController) and clones the
  whole system into a static copy, so the live services are DOWN. The only transport available is a
  STANDALONE static binary (rsync daemon), built by .github/workflows/build-static-binaries.yml and
  pinned in controller/binary_version.txt. That binary is MUTE on the host side: RsyncManager
  .startServer just launches `rsync --daemon` and returns a boolean, with no listener and no
  onComplete. The client side DOES talk (startClient parses rsync stdout: onProgress / onComplete /
  onError), so the RECEIVER knows when it finished, but the HOST never learns a client finished. One
  blind jaw: the pincer cannot close without the user.

- Machine ON (Share): selected-content share does NOT stop the system, so the live core (conex-core) is
  available as a CONVERSATIONAL transport. conex-core already computes the total up front (prepare ->
  totalBytes), streams per item, and knows when each fetch closes (on_close). So it can signal done
  and close the gate itself. The pincer closes BY DESIGN, with the primitives already hardened for the
  export op (K2GO-507).

So the decision for Share is system-level (conex-core as the talking transport), not the APK-level
mute-binary model. The APK still owns UX, battery and network (foreground service, wake / wifi locks),
but delegates the transport-that-talks to the core. Clone (whole-system, machine off) cannot use this:
conex-core is down. Closing Clone's pincer would need to give the standalone daemon a VOICE: a wrapper /
sidecar that watches the daemon's connections and reports completion to the APK. That is a separate
effort (its own ticket), not part of Share; it is noted as a Clone follow-up (the
exposure-until-user-stops gap is real but out of scope here).

### Readiness: what is pinned vs what we discover while building

Method (endorsed, and already proven by v1 + K2GO-507): freeze the contracts that are expensive to
retrofit (the "shared contracts first" discipline of Pillar 0), then implement incrementally and let
code-review + on-device validation catch the rest. The test for "pin it first" is the design-coherence
rule: a missing FACT / OWNER / LIFECYCLE is expensive to retrofit (pin it); a missing CASE is cheap to
patch in review (discover it).

Pinned (ACCEPTED, safe to build against):
- Bundle format + manifest + identity (built + validated for disk).
- Carrier seam (ExportCarrier / ImportCarrier: "a future network carrier reuses the same port").
- Import flow (validate vs installed apps, dedup, partial, stage, /v1/import/item: built + validated).
- Topology: receiver pulls from the sender's conex-core.
- Gate / token lifecycle (the five closers + session-bound token).
- Free-space preflight on the receiver (ImportPreflight + StorageProbe on the fetched manifest).

Open but DISCOVERABLE while building (cases, not facts: do NOT block):
- QR payload exact shape (pointer + address + token) and size limits.
- Per-item retry / re-request over a flaky WLAN (reuse the export stall timeout + resume).
- Progress UI reuse (the two-phase bar already exists).

Open and BLOCKING-IF-WRONG (pin with a one-line decision before the slice that touches them):
- Trust on the local link: token scope enforcement + plain HTTP vs TLS on the hotspot. Pin before the
  gate is exposed off loopback.
- WLAN on_close reliability: confirm the server _stream finally fires on a remote client drop (not
  only loopback), or the in_use pin can stick. Confirm before relying on it.
- Hotspot ownership: Connect owns it; Share must borrow and not leave it on. Pin before Share toggles
  the hotspot.

Build order (contracts first, then fork: same as the backend and K2GO-507):
1. Shared contract: the /v1/share gate (scoped to item set + token, the five closers) in conex-core, plus
   the network ImportCarrier interface. Merge this first.
2. Fork: sender (expose selection via the gate) and receiver (network carrier + QR scan + import),
   each built against the frozen contract.
3. Resolve the three blocking-if-wrong items as their slice is reached.

Recommendation: we have the primary directives. Start implementing step 1 (the shared contract) once
the three blocking items have a one-line decision each; discover the rest in review + on-device. Do
NOT wait to theorize everything: v1 and K2GO-507 show the incremental path with code-review as the net
works.

### OPEN questions

(The gate / token lifecycle and the scoped /v1/share gate are RESOLVED above; trust and
WLAN on_close and hotspot ownership are categorized as blocking-if-wrong in Readiness. What
remains genuinely open:)
- Who hosts: box-to-box (one box's hotspot) vs phone-to-phone (one phone's hotspot serving
  its box). Clone's transport choices inform this.
- Discovery: QR only (v1.x) vs mDNS / zeroconf later.

### Scope

v1.x, after the disk round-trip (done). Ties to K2GO-376. When Share is scheduled, it reuses
this section as its contract.

### Amendment K2GO-577: rsync transport, no temp copies, resume after a drop (ACCEPTED, Nov 2026)

Status: ACCEPTED. ZIM shipped in conex-core 0.4.0 (PR 1); Kolibri is PR 2. This amendment
supersedes two lines above: "openPayload ... HTTP GETs (with Range)" in "The carriers" and the
re-fetch model in "Resilience (reuse)". The gate, the token, the two lifetimes and the five
closers are unchanged.

Problem (measured, not assumed):
- The HTTP fetch stages a full copy before the import: a ZIM is written twice, a Kolibri channel
  up to three times (sender export temp, receiver tar, receiver extract, then the Kolibri copy).
- A drop restarts the item from zero. On a phone, a multi-GB item needs the free space of two
  or three copies and may never finish on a weak link.
- Kolibri's own network import is not drop-resilient (spike, 15 Nov, Kolibri 0.19.5): it does
  not resume partial files, and after a sender Wi-Fi cut its retry appended to the in-flight
  files, which then failed the checksum and the command ended in error.

Decision: the transport is rsync, it runs IN THE BOX, and conex-core owns it, so IIAB reuses the
same delivery.
- Sender: one rsync daemon per box, alive only while a share session is open. One read-only
  module per item ("<sessionId>-<itemRef>"); a filter admits only that item's files; the module
  password is the session token; the daemon logs nothing. The session owns its modules: create
  adds them, every closer removes them, the last close stops the daemon, the startup sweep kills
  a daemon a dead core left. The manifest advertises the module per item.
- Receiver: the receiver's own conex-core pulls (POST /v1/import/item with a source instead of a
  payloadPath), as the same import long operation (same status, cancel and TTL).
- The HTTP fetch stays for mixed versions: a sender without the daemon advertises no module, and
  a receiver box whose core refuses the pull falls back to the staged import.

This is the "voice" the Clone analysis above asks for. The daemon is still mute, but it is no
longer alone: conex-core supervises its lifecycle, and the receiver reports per-item progress
to the gate (POST .../progress), which keeps the session fresh and feeds the sender's view.
COMPLETE still closes the pincer.

Per app:
- ZIM (one large file): rsync --inplace --append-verify into /library/.conex-partial/kiwix/,
  then a rename into the zims dir. A kill of either side keeps the bytes; the next pull appends
  and verifies the WHOLE file (a corrupt partial is detected and re-sent). The rename is the
  commit, so Kiwix never sees a partial ZIM.
- Kolibri (PR 2): static files, no tar. The content files are content-addressed and immutable
  (file name = md5), so they move once, straight into the receiver's KOLIBRI_HOME storage, with
  partial names until verified (never --inplace into storage, never --delete: the storage is
  shared across channels). The channel DB and Kolibri's manifest move into a small session dir.
  The sender stages nothing: the DB already lives in content/databases, `exportcontent
  --manifest-only` writes only the manifest, and the file list is the channel's available
  local files in the main db. The receiver then links them with three stable Kolibri commands:
  `importchannel disk` -> `scanforcontent --channels <id> --channel-import-mode none` ->
  `importcontent disk`. Source review of Kolibri 0.19.5 (proven on device): scanforcontent alone
  marks a file available on existence only and does not set admin_imported (content without it
  can be auto-removed by content requests); importcontent alone fails a free-space check that
  counts files already in place. The scan first, then importcontent (which copies nothing,
  sets admin_imported and honors the manifest) passes on a full disk.

Integrity is the transport's job: Kolibri marks a file available if it exists. So a file gets
its final name only when it is complete and verified (rsync whole-file checksum; for Kolibri
the md5 in the name).

Android (K2Go only): the box rsync is a Linux process that Android routes by the default
network, which can be cellular when the hotspot has no internet. The receiver app relays the
pull through a loopback relay pinned to the hotspot Network (ShareRelay), the same pinning the
HTTP carrier uses. conex-core stays platform-neutral.

Lifecycle (who sets, who clears, what if the process dies):
- Daemon + secrets: set by the first open session; cleared by the last close, the safety cap,
  or the startup sweep (pid file of the process group). A daemon that dies mid-session is
  restarted on the next manifest or progress call.
- Partial files: set by the pull; cleared by the rename on success; kept on a drop or a cancel
  so a Retry resumes; removed after 48 h by the sweep (at startup and at each pull start).
- Kolibri files of an abandoned pull (DECIDED: keep): they land straight in Kolibri storage,
  so a pull that nobody retries leaves them there, unreferenced. Kept on purpose: a later Retry
  or import of the channel reuses them (the name is the md5), and pulling into the work dir
  first would add one move per file under proot and re-send files other channels hold.
- Relay: opened by the receiver's import run at its first pulled item; closed when the run ends.

Exposure: one more listener (TCP 9873) while a session is open. See
conex-core-share-exposure.md "rsync transport".

Evidence: OPPO + OnePlus 7T, 333 MB ZIM over Wi-Fi at about 20 MB/s; cut at 131 MB, Retry
resumed from 131 MB, md5 equal on both phones, no staging copy, session and daemon closed.

Open:
- Resume of a multi-GB partial on a slow phone (the --append-verify pre-read against the rsync
  I/O timeouts): to test.
- IIAB: the host firewall must admit TCP 9873 when the share exposure is on.
- conex-core is delivered with the rootfs today; a live update like dash-node is planned, so
  mixed versions become rare (the HTTP fallback covers the gap).
