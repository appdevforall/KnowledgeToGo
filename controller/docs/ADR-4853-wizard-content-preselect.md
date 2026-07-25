# ADR — Wizard content pre-selection & post-install provisioning (Books first)

Status: Accepted, in progress (ADFA-4853). Books is the first module to implement the pattern; ZIM can follow. Depends on the Get More / Books download engine (ADFA-4850) and the live-content channel (ADR-4832 / ADFA-4832).

## Context

- "Get More" (ADFA-4850) lets the user add content **after** the system is installed: hub cards gate on a **live endpoint probe** and the Books catalog is queried from the **running dashboard**. This is the post-install door and it works.
- The first-run **wizard** is the other door to the same idea, but nothing is installed yet — there is no live server to probe and no dashboard catalog to query. So a "pick your content, then install" flow cannot use the live path.
- Catalog availability differs by module: the **ZIM** catalog is already app-side (a mirror the app fetches + caches). The **Books** catalog (`catalog.db`) lives inside the dashboard, synced from `iiab.switnet.org/android/pg/catalog.db.gz` (~170 KB gzipped). **Maps** is whole-system, slow, and ships by default.
- Tier reality: **Calibre-Web (books) ships only in the Full tier**; basic/standard never have it. Kiwix/maps come by default.

## Decision

Separate **selection** from **execution** with a persisted order ("leave a food order; cook it when the kitchen is ready"):

1. **Offline catalog asset.** Ship a trimmed, gzipped asset in the app, generated from `catalog.db`: `{id, title, author, language, download_url}`. It powers a minimalist offline search (title/author) with the existing language filter — no server needed.
2. **Availability by source, per door.** The same Books screen has two data sources: **live REST** (Get More, post-install) vs **local asset** (wizard, pre-install). Card/option availability is gated by **live probe** in Get More and by the **tier plan** in the wizard — Books is offered in the wizard only when the chosen tier includes Calibre-Web.
3. **Persisted order.** The wizard selection is stored in app-private storage as a list of `{id, title, download_url}`. It is app data, so it survives the system install.
4. **Post-install provisioning drain.** On first boot with the server alive and Calibre-Web present, the order is drained into the **existing `BooksDownloadService`** (one at a time, per-item retry, continue past failures). No new provisioning engine.

## Why store `download_url` in the order (not just `id`)

- `BooksDownloadService` already consumes `{id, title, url}`; feeding the order straight in means **zero new download machinery** and **no dependency on the server's catalog version** at provisioning time (app-side asset vs server `catalog.db` can drift over releases).
- The URL is one short field, and only for the handful of selected books. Keeping it removes an entire failure mode (an `id` the server catalog no longer knows) for negligible cost. The alternative — server resolves `id → url` from its own catalog — couples the two catalog versions and is rejected for v1.

## Why JSONL (gzip), not raw CSV

- Titles/authors contain commas, quotes, and accents; a naive CSV split corrupts rows. **JSON Lines** (one object per line, UTF-8) is delimiter-safe, streams line-by-line, and gzips just as well. CSV is acceptable only with strict RFC-4180 quoting; JSONL avoids the trap.
- Size is a non-issue (~170 KB gzipped for the whole catalog), so gzip is for tidiness, not necessity. A trimmed SQLite (+FTS) is the upgrade path if linear search feels slow, but is out of scope for v1.

## Options considered

- **A — Wishlist + post-install drain (chosen).** Offline asset + persisted order + drain via `BooksDownloadService`. Minimal new code; reuses the ADFA-4850 engine; matches the wizard's mental model.
- **B — Everything post-install (Get More only).** No wizard content step; the user returns to Get More after install. Simplest, but fails the "pick content during setup" requirement.
- **C — App-side live download at wizard time.** Not possible: Calibre-Web/dashboard don't exist pre-install; there is nowhere to put the books.

## Consequences / caveats

- Provisioning must be **idempotent** and **de-duped** against the library (reuse the existing in-library check), and tolerate partial completion (keep the order until resolved or dismissed).
- **Tier guard, both ends:** offer Books in the wizard only for tiers with Calibre-Web; at drain time, if Calibre-Web is absent, drop the Books order rather than hang.
- A stale/unavailable `id` fails only that book (existing per-item retry / continue), never the batch.
- The bundled asset goes **stale between releases**; acceptable because the Gutenberg catalog changes slowly. Fetch-fresh-with-bundled-fallback is a later refinement.
- Trigger the drain off the same **boot-gate / server-alive** signal the home already uses; surface it visibly (e.g. a "Finish adding your books" entry into the download-manager screen) rather than silently.

## Scope (v1)

- Asset generator (catalog.db → JSONL.gz) + bundled app asset.
- Offline Books catalog source + persisted order in the app.
- Wizard Books step reusing the Books UI, tier-gated.
- Post-install drain via `BooksDownloadService`.
- "Finishing setup" index → real per-module detail cards; auto-retry (max 3) on transient failures.
- Out of scope: real covers, wizard-time catalog fetch, ZIM/maps pre-selection.

## Follow-ups (separate tickets, hang under ADFA-1028)

- **Background jobs monitor screen.** There is no monitoring of background provisioning jobs today. We need a dedicated screen that (a) lives-monitors in-progress background work and (b) keeps a history/log of items that failed. The "Finishing setup" screen's Finish button will carry a note ("you can review failed tasks in the background jobs monitor") pointing here, so a failure is never a dead end. Only content on the LIVE route (ZIM, Books) belongs here.
- **Maps is NOT backgroundable — and the Maps card needs clear guidance.** Maps changes (other than FQR) STOP the server, so a Maps (re)install must run to completion in the foreground for however long it takes; it is not on the live/background route and must never be shown as a background job. When the Maps card is built, it must explain: the system already ships a minimal-but-functional maps; rebuilding it is usually unnecessary; FQR is often the better option; a higher-quality reinstall is an advanced decision. Maps stays out of the live provisioning index until/unless it gets a real live path.

## References

`BooksDownloadService.java`, `BooksClient.java`, `BooksLandingFragment.java`, `GetMoreHubFragment.java`, `SetupLibraryActivity.java`, `static/dashboard/sockets/books.query.ts`; ADR-4832 (live-content channel). Ticket ADFA-4853 (parent ADFA-1028); builds on ADFA-4850.
