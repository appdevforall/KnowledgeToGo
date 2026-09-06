# ADR-390: Kiwix self-healing catalog (offline-first, freshness on demand)

Status: Accepted (implemented and device-verified 2026-09-05)
Ticket: K2GO-390

## 1. Problem

The Kiwix ZIM catalog ships as a fixed asset, `assets/kiwix_catalog.csv`, generated off-device. It has
no freshness mechanism. Kiwix rotates ZIM files monthly and prunes old dates, so a baked entry that
points at a pruned date returns HTTP 404.

Device-proven (K2GO-390, "Infinite loop attempting to download content"): a stale entry ->
`aria2 exited with code 3` (404) -> the item is never removed from the durable wishlist -> the
post-install provisioning drain re-attempts it about every 2 s, forever. Symptoms: CPU churn,
`iiab-make-kiwix-lib` re-running each cycle, `kiwix-serve` flapping, the box never settling.

## 2. Facts that shape the design

- Kiwix is FLAT: `category/file.zim`, two levels. (Kolibri is N-level -- 2 to 6, per category -- which
  is why Kolibri needs a heavy topic-tree bundle and recursive browsing. Kiwix needs none of that.)
- The ZIM filename is `<creator>_<lang>_<flavour>_<YYYY-MM>.zim`. Only the date rolls over; the identity
  (`creator`/`lang`/`flavour`) is stable. Confirmed live: `wikipedia_ab_all_maxi_2026-04` and `..._2026-07`
  coexist on the server. A within-month re-release appends a letter (e.g. `2026-07a`, `2026-07f`); the
  date is therefore `YYYY-MM[a-z]?`, and the generator must keep that letter on the DATE, not the flavour,
  so the identity stays stable across a re-release (see `build_kiwix_catalog.py` `DATE_RE`).
- The wishlist key is ALREADY date-free: `project|lang|<creator+flavour>` (the third segment is the
  catalog entry key -- `creator` joined to `flavour`, no date). So the same selection re-resolves to the
  current dated file once the catalog is refreshed. This is what makes per-element self-heal cheap.
- The size delta between months is small (well under 10 %: e.g. 111G -> 115G, 2.0G -> 2.1G). It is not a
  re-consent concern -- it is the same content, the next month's build.
- The generator already exists: `controller/app/tools/build_kiwix_catalog.py` (baked at
  `assembleRelease`). Kolibri already has a catalog-agnostic freshness CORE (manifest + ETag + overlay +
  store + scheduler, ADFA-5094) that Kiwix can reuse.

## 3. Goals and constraints

- Respect the upstream servers (Kiwix, Kolibri, Gutenberg): asset-first, minimal requests, conditional
  (ETag) checks, no continuous scraping. Shipping the catalog as an asset exists to reduce requests.
- Offline-first: the asset is the guaranteed baseline. Browsing and selection work with no network.
  Freshness is best-effort and connectivity-guarded.
- Easy for the user: silent background recovery, no UI, no size re-confirmation.
- Integral, not patched by parts.

## 4. Decision

A self-healing flat catalog for Kiwix that reuses the LIGHT freshness core and adds nothing tree-shaped.

- **Asset baseline (kept).** `assets/kiwix_catalog.csv` still ships and still loads first. It is the
  offline baseline and the instant-open source.
- **Overlay.** When online and the manifest changed, download the fresh CSV as an overlay
  (`CatalogOverlay`), preferred over the asset -- mirror `BundledCatalogSource` (overlay-by-mtime).
- **Hybrid trigger (option a).**
  - *At catalog open:* a conditional check (`If-None-Match` / ETag) against a small hosted manifest,
    TTL-gated. `304` = match = no work (nothing is consumed). Changed = adopt the fresh CSV.
  - *On a download 404:* force a conditional refresh. Because the wishlist key is date-free, it
    re-resolves to the current dated file; retry against it. If the catalog did NOT change (same file),
    the failure is real -> stop bounded (no loop).
- **Self-heal write-back.** Adopting the overlay heals the failing element AND every other stale one at
  once. The refresh itself is the classifier: a changed file means "was stale, heal"; an unchanged file
  means "genuinely gone or a network fault, stop".
- **Connectivity-guarded.** No network -> skip silently, use the asset/overlay, never error. Reuse the
  existing `hasInternet` gating pattern (the rootfs path already does this).
- **No UI, no size re-confirm.** Recovery is in the background; the size delta is negligible.

## 5. What we reuse, and what we do NOT

- REUSE (catalog-agnostic, flat): `CatalogManifestClient` (ETag/304), `CatalogRefreshWorker`
  (TTL/fetch/hash-verify/apply), `CatalogRefreshScheduler` (`scheduleWeekly` + `refreshNow`),
  `CatalogRefreshStore` (per-catalog etag/hash/last-check), `CatalogOverlay`. Wire a `kiwix` catalog the
  way `kolibri/data/CatalogRepositoryImpl.java` wires the Kolibri ones.
- DO NOT reuse: Kolibri's N-level topic-tree bundle and recursive browsing (`BundledTreeSource`, the
  tree manifest). Kiwix is flat and needs none of it. This is the "simplify much more" for Kiwix.

## 6. The loop fix falls out

- Today `redesign/ContentDownloadSession.java` `onError` sets an item FAILED but never removes it from
  `ZimWishlist` (removal happens only on DONE, `redesign/ZimDownloadService.java` `onItemDone`), so
  `redesign/ZimProvisioner.java` `drain` re-hands the same stale key forever.
- New flow: a download failure triggers refresh-and-re-resolve. A stale item heals and retries against
  the current file. Failures are counted against the catalog version (`ZimWishlist.bumpAttempts`,
  keyed by the overlay mtime): a changed catalog renews the budget; after `MAX_HEAL_ATTEMPTS` (5)
  failures against an UNCHANGED catalog the item is genuinely gone and is removed (leaves the wishlist)
  so the drain stops. No infinite re-drain.

## 7. Hosting (ops, required for the heal) -- LIVE

The heal needs a fresh source: a Kiwix catalog manifest plus the refreshed CSV at
`APK_REPO + /catalogs/kiwix.manifest.json` (+ the CSV), mirroring `kolibri.manifest.json`.
`APK_REPO = https://k2go-download.appdevforall.org`.

This is published and working. Device-verified 2026-09-05: the app pulled a fresh overlay
(`kiwix.manifest.json` generated 2026-09-02, ETag/hash recorded in the refresh store) and a 404
self-healed end to end. If the source is ever absent the app degrades to asset-only (offline-first still
works) and a 404 cannot heal, so the published source must be kept fresh.

Ops action per release cycle: regenerate and re-publish with the CURRENT generator
(`build_kiwix_catalog.py --manifest --csv-url ...`). The generator is the fix site -- re-running it
collapses re-release-letter entries (the `DATE_RE` fix) so they can heal; this is not a manual data edit.

## 8. Lifecycle (who writes it, who clears it, what if it is missing)

- Overlay: written by the refresh worker, preferred by the source, superseded by a newer overlay
  (mtime/hash), removable (falls back to the asset). The store keeps per-catalog etag/hash/last-check;
  namespaced by catalog name, so it already serves "kolibri, kiwix, ...".
- Wishlist: an item leaves on DONE (existing) or on a confirmed-gone item (new). A stale item heals
  instead of looping.

## 9. Verification

- Unit: the pure freshness rules are already covered (`CatalogFreshness`). Unit coverage of the Kiwix
  wiring and the stale-key re-resolve is a follow-up (not added in this change).
- Device (2026-09-05, OnePlus arm64, API 35), reproduced with a real stale entry
  (`bulbagarden_en_all_nopic_2026-07`, 404 on the mirror):
  - Pre-fix build: the 404 re-drains forever (35+ attempts, never removed).
  - Heal: 404 -> forced refresh pulls the overlay -> the date-free key re-resolves to the live
    `..._2026-05` -> downloads. Wishlist entry removed on DONE.
  - Bounded (no newer version to adopt): 404 -> exactly `MAX_HEAL_ATTEMPTS` (5) attempts -> the item is
    dropped -> the loop stops.
  - Offline: the asset catalog works with no network and no error.

## 10. Consequences

- Stale catalogs self-heal silently; the infinite loop is gone; the upstream servers are respected; the
  app still works offline.
- One more published artifact (the kiwix manifest + CSV) to maintain, mirroring Kolibri.
- The freshness core is confirmed reusable across content sources (Kolibri today, Kiwix here, Gutenberg
  later) without dragging in any source's browsing shape.
