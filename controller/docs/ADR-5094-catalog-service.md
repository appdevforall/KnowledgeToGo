# ADR — Catalog service: weekly off-device generation, on-device refresh

Status: **Draft, for discussion.** Ticket ADFA-5094 (parent ADFA-1028). Builds on
ADR-4853 (bundled catalog asset) and ADR-4954 (Kolibri content selection). **Amends
one sub-decision of ADR-4954** — that catalog staleness "rides the APK OTA" — while
leaving the layering it rests on intact.

## Context

Every content catalog is a snapshot bundled in the APK:

| Catalog | Asset | Generator |
|---|---|---|
| ZIM/Kiwix | `assets/kiwix_catalog.csv` | `tools/build_kiwix_catalog.py`, `refreshKiwixCatalog` Gradle task |
| Kolibri | `assets/kolibri_catalog.jsonl` | `tools/build_kolibri_catalog.py`, `refreshKolibriCatalog` Gradle task |
| Books | `assets/books_catalog.jsonl` | (same shape, ADR-4853) |

Each is generated at release time by a Gradle task on `assembleRelease` and refreshes
**only when a new APK ships**. `BundledCatalogSource` reads Kolibri's JSONL (a header
line with `generated`, then one channel per line) and already exposes `invalidate()`,
documented "for a future in-place catalog refresh".

ADR-4954 (D1, "Rejected — routing Studio through the dashboard to keep it updatable")
concluded that a separate updatable catalog channel was unnecessary, on the argument
that **staleness is self-cancelling**: offline, the source is unreachable and the point
is moot; online, the device can take the ~30 MB APK OTA, which carries a freshly
generated catalog. That reasoning is sound about *layering* but rests on an assumption
this ADR revisits with evidence it did not have:

1. **APK cadence ≠ source cadence.** The Books catalog has not refreshed in ~2 months —
   no APK has shipped in that window. "An online device gets a fresh catalog via the
   OTA" is only as true as the release cadence, which is far slower than Studio/Kiwix
   change. So an online device today can be browsing a months-old catalog.
2. **Cost asymmetry.** The catalog is small (~83 KB for all 142 Kolibri channels per
   ADR-4954's measurement); the APK OTA is ~30 MB. On the links this product targets, an
   83 KB pull is far likelier to complete than a 30 MB update — so tying catalog
   freshness to the APK makes the *cheaper* refresh depend on the *dearer* one.
3. **Build-time bandwidth.** Generation runs from Gradle, so **every developer build hits
   Studio/Kiwix** repeatedly. ADR-4954 itself flagged the value of fewer Studio calls.

## Decision

Move catalog generation off the build onto a **weekly server-side job**, publish
**versioned catalogs plus a manifest** to the content mirror we already serve from, and
let the app **pull the catalog when it has a connection**, keeping the APK-bundled
snapshot as the offline floor.

### D1 — Generation is a scheduled GitHub Actions workflow that publishes to Cloudflare

A **scheduled workflow** (`on: schedule`, weekly, plus `workflow_dispatch`) runs the
existing generators once a week and uploads the result to **Cloudflare R2**, reusing the
exact pipeline the release build already uses for APKs and `update.json`
(`.github/workflows/android-release-build.yml`, ADFA-4984): `aws s3 cp` to the
S3-compatible endpoint with the `CLOUDFLARE_*` account/key vars already configured, and
**fixed keys** that overwrite the previous artifact so the URL always resolves to the
latest. Studio/Kiwix are queried **once per run**, from a runner with bandwidth — not from
every developer build; the Gradle `refresh*Catalog` tasks stop calling the sources (the
bundled asset becomes a committed snapshot, or a fetch of the latest published catalog at
release time).

The artifacts are served from the same public base as the OTA —
`k2go-download.appdevforall.org` (the Cloudflare domain in front of the `iiaboa-apk-repo`
bucket, where `UpdateController` already reads `update.json`) — under a `catalogs/` prefix:

- `https://k2go-download.appdevforall.org/catalogs/kolibri_catalog.jsonl`
- `https://k2go-download.appdevforall.org/catalogs/kolibri.manifest.json`

Cloudflare fronts this with a CDN, HTTPS and `ETag`/`Cache-Control` out of the box, which
D3's conditional fetch relies on. The **content** stays on the existing Nginx mirror; only
the catalog *metadata* moves to Cloudflare, and the per-item download URLs inside it keep
pointing at the content mirror. Preferable to a hand-run cron on the mirror host: no server
to babysit, the same credentials and pattern the team already operates, and the same CDN
edge as the APKs. (Operational notes: GitHub disables scheduled workflows after 60 days of
repo inactivity and may delay a run under load — neither matters for a weekly catalog, and
`workflow_dispatch` gives a manual trigger.)

### D2 — Bundle is the floor; the pulled catalog is an overlay

The APK still ships a snapshot — a device that never gets a connection is exactly the
target, and must have a usable catalog on first run. At runtime, a newer pulled catalog
in `filesDir` **supersedes** the bundle; otherwise the bundle stands. `BundledCatalogSource`
becomes overlay-aware (read the overlay when present and newer, else the asset) and its
existing `invalidate()` is the swap hook. The floor is never removed, so a corrupt or
absent overlay degrades to "the version in the APK", never to nothing.

### D3 — Freshness is a manifest + TTL + conditional fetch

The manifest is small and cacheable:

```json
{
  "catalog": "kolibri",
  "version": "2026.08.14",
  "generated": "2026-08-14T12:00:00Z",
  "hash": "sha256:…",
  "url": "https://k2go-download.appdevforall.org/catalogs/kolibri_catalog.jsonl",
  "items": [ { "id": "<channel>", "version": 12 }, … ]
}
```

Three layers, each avoiding the next's work: a **TTL** (e.g. 7 days) decides *whether to
check*; a **conditional GET** (`ETag`/`If-None-Match` → `304`) confirms *whether anything
changed*; `hash`/`version` decide *whether to swap*. This reuses the HTTP pattern already
built for the app's update-check (lightweight endpoint + cache fallback). `items[]`
(per-channel version — already present in Kolibri's catalog, device-verified in ADR-4954)
enables a later per-channel delta and answers, locally, the "a newer version of this
channel exists" signal that ADR-4954 deferred (`remotechanneldiffstats`).

### D4 — Refresh is opportunistic + scheduled, never blocking

A `WorkManager` job, weekly, network-constrained, plus an opportunistic check on launch
when past the TTL. If offline or the fetch fails, the current catalog stands: the picker
never blocks, and content downloads validate live regardless — the catalog is a hint, not
a contract, and ADR-4954's framing of that holds. A visible freshness cue ("Catalog
updated on ⟨date⟩", from the header `generated`) plus a manual refresh makes the
"support, not truth" nature explicit.

### D5 — One mechanism, every catalog

Generation, manifest shape, overlay layering and refresh are **catalog-agnostic**; each
catalog is an adapter of {generator, manifest URL, local version, apply-overlay}. Landed
on **Kolibri first** — it already carries per-channel `version` and has `invalidate()`.
**Kiwix** follows (its `kiwix_catalog.csv` needs a version header, or better, alignment to
JSONL like the other two — a scoping decision for the Kiwix phase). **Books** already
lives in this model and is the standing evidence of the problem; it re-points to the
pulled catalog for near-free.

## What this amends in ADR-4954

Only the D1 sub-point "the staleness scenario is self-cancelling / an online device can
take the OTA". The **layering** 4954 rests on is unchanged: at wizard time there is no
box, so the app reaches the source itself — now via our mirror rather than Studio
directly. Studio (through the weekly job) remains "what exists"; the box remains "what
this device has and does". What changes is *who* fetches from Studio (a weekly job, not
every build) and *how* the device stays current (an 83 KB catalog pull, not a 30 MB APK
OTA). This is not the rejected idea from 4954 — that was routing Studio *through the
dashboard*; this is a static file on the mirror, like the app's `update.json`. The
evidence 4954 lacked — Books ~2 months stale — is what reopens the sub-point.

## Out of scope

- **Per-channel/topic delta download** (fetch only changed items). The manifest carries
  per-item versions so it is possible; v1 may re-pull the whole (small) catalog.
- **The topic tree itself** — ADFA-5094's depth half, per ADR-4954 D1/D3. Trees stay live
  per-channel and are imported on demand; this ADR is about the channel catalog's
  freshness, which the imported trees then inherit at per-channel granularity.
- **Kiwix CSV→JSONL alignment** — its own decision in the Kiwix phase.
- **Books picker changes** beyond re-pointing it at the pulled catalog.

## Phasing

| PR | Contents |
|----|----------|
| A | Server cron + mirror layout + manifest for Kolibri; Gradle stops hitting the source |
| B | App: overlay-aware `CatalogSource`, manifest client (ETag/fallback), refresh store, `WorkManager` + opportunistic check, freshness label in the Kolibri picker |
| C | Depth (the 5094 original scope): topic-tree import → serve → picker fallback, consuming the fresh per-channel version |
| — | Kiwix adoption + Books re-point: separate follow-ups |

New user-facing strings ship all 33 locales in the PR that introduces them (MissingTranslation fails the build).

## References

`BundledCatalogSource.java`, `StudioCatalogMapper`, `tools/build_kolibri_catalog.py` +
`refreshKolibriCatalog`, `tools/build_kiwix_catalog.py` + `refreshKiwixCatalog`,
`kiwix_catalog.csv` / `KiwixCatalog.java`, `books_catalog.jsonl` / `BooksCatalogAsset.java`,
`.github/workflows/android-release-build.yml` (the R2 upload + `update.json` pattern this
reuses), `update/presentation/UpdateController.java` (`k2go-download.appdevforall.org` base).
ADR-4853, ADR-4954.
