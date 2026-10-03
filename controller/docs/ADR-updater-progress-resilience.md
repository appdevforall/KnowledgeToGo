# ADR: Standardize updater progress and network resilience (K2GO-443, K2GO-383)

Status: Proposed

## Context

The box has four updaters. Three are content refreshes driven by dash-node
wrappers (Forgejo repos, Code on the Go add-ons, Code on the Go build assets);
the fourth is the dash-node rebuild (self-update). Today all four are minimalist:
an indeterminate spinner plus, at best, the last log line and final counts. None
of the three content refreshes gives per-item percent, speed, pause, resume, or
retry.

The problem to solve is NOT only showing information. It is control and
resilience against network errors: a network change (Wi-Fi to data and back) or a
dropped link must not break a large download or leave an update half-applied. A
flaky origin is one more possibility to absorb (an R2 move has been floated as a
candidate; nothing is established yet, and this ADR does not assume it). Without
pause/resume/retry/renegotiate, finishing a large transfer (the rootfs was the
proof) is close to impossible. So pause, resume and retry are in the
specification, not optional polish.

This is a strong reuse situation. The proven mechanism already exists:

- dash-node durable job engine (`sockets/jobs.ts`): per-job `phase`, `percent`
  (-1 = indeterminate), `speed` (bytes/s), `detail`, structured status over the
  generic `/:type/*` REST surface, plus pause / resume / retry / cancel.
- aria2c runners that download with resilience: `sockets/kiwix.exec.ts` and
  `sockets/maps-base.exec.ts`. Canonical aria2 args (`--continue`,
  `--check-integrity`, `--split`, `--max-tries=5`, retry-wait, timeouts) and an
  outer `withRetry` loop that re-runs aria2 on a FULL interface loss (exit 19
  DNS) and resumes via `--continue` across a mobile handoff. Pause keeps the
  partial + `.aria2`; cancel prunes it.
- App side: `RestContentClient` (type-parametric: percent/speed, pause/resume/
  cancel, start-or-attach, "Reconnecting n/5"), the `download/domain/*` set
  (`Aria2ProgressLine`, `DownloadEta`, `DownloadRetryPolicy`, `DownloadVerifier`),
  `ZimDownloadService` (foreground shell), `DownloadStateViewModel`, and
  `Aria2Manager` (the app's own aria2 downloader, sharing the canonical args).

The canonical aria2 flag set is already mirrored between the dash-node runners
and `Aria2Manager.java` (documented in `kiwix.exec.ts`).

## Decision

Standardize the four updaters on one progress contract and one app component,
and move the downloading updaters onto the existing aria2 job engine rather than
building anything new.

1. Content downloads (add-ons, build assets): register a durable job-engine
   runner per type (template: `kiwix.exec.ts` / `maps-base.exec.ts`). aria2c does
   the download (percent + speed + `--continue` resume + the Wi-Fi-drop outer
   retry loop); the existing mirror keeps ONLY verify (md5 / sha256, Cloudflare
   strip, catalog rewrite) and the finalize (generate the browse page, atomic
   staging swap). This replaces the mirror's `urllib` per-file download. The box
   gains pause / resume / retry / cancel and network-change resilience for free,
   identical to maps / kiwix / books / kolibri.
2. Forgejo (git): wrap the existing `refresh_forgejo` orchestration in a
   job-engine runner that reports per-repo progress (git `fetch --progress`
   percent where available, else repo N of M) and retries a failed repo. git has
   no aria2-style mid-transfer pause/resume; retry-per-repo is the realistic
   control and is enough (each repo op is small, fast-forward or a side ref).
3. Dashboard rebuild (K2GO-383): this is a compile (yarn build), not a download,
   so aria2 does not apply. It keeps its own progress but gains percent + ETA +
   persistence over its existing phases (building / promoting) and log. No
   pause/resume (a compile is not resumable); cancel already exists (ADFA-5333).
4. App: replace the bespoke inline progress in `AddonsRefresh` /
   `CodeAssetsRefresh` with the shared download UI already used by the job-engine
   modules (`RestContentClient` + `ZimDownloadService` + the `download/domain`
   progress/ETA parsing), driven by one shared progress component. Add
   `code_addons` and `code_assets` (and a forgejo job type) to the job types.

One progress contract across all four: `{ phase, percent (-1 = indeterminate),
current, total, speed, detail }`. Each updater fills what it can (download bytes
for add-ons/assets; per-repo for Forgejo; compile phases + ETA for the rebuild).
Not identical metrics in all four: one shape, one UI, filled per source.

## Lifecycle (who sets / clears / what if it dies)

- Pause / resume / cancel state is owned by the durable job engine (existing, it
  survives a dash-node restart). No new persistent markers are added.
- Resume is aria2 `--continue` plus the `.aria2` control file; pause keeps the
  partial, cancel prunes it (existing `cleanupPartials`). A process death
  mid-download leaves a resumable partial, not a half-applied update: the atomic
  staging swap still only runs after a clean, verified download.
- The content-specific finalize (page + swap) stays outside the download, so a
  paused or failed download never serves a partial tree.

## Reuse, not duplication

- Do NOT add a fourth bespoke downloader. The content runners reuse the aria2
  args + `withRetry` loop from `kiwix.exec.ts` / `maps-base.exec.ts`.
- The per-module detached-job client and the dash-node route trio are the
  content-module shared-extraction follow-up: the app side rides one shared
  `RestContentClient` / `DetachedJobClient`, not three near-copies. This ADR
  depends on that extraction (or lands with it) so the three content updaters
  share one path.

## Scope and phasing (pragmatic)

- K2GO-443 (content): add-ons and build assets first (large files, where aria2
  resilience matters most), then Forgejo (git, per-repo). Build assets is the
  natural first slice (9 large `.br` files).
- K2GO-383 (dashboard rebuild): percent + ETA + persist on the compile; separate
  ticket, shares the progress contract and the UI component.
- Both are APK + dash-node: dash-node emits the structured progress; the app
  renders it with the shared component.

## Out of scope / notes

- git mid-transfer pause/resume (not an aria2 download): retry-per-repo only.
- Origin flakiness is a possibility, not an established fact (an R2 move has been
  floated but not confirmed; it is being looked at separately). aria2's
  `--continue` + `--max-tries` + the outer loop is general resilience that would
  help any flaky origin; this ADR adds no origin-specific code or assumption.
- The canonical aria2 arg set is already shared text across runners +
  `Aria2Manager`; adding content runners keeps that note's "change all copies"
  rule (or extract the args once as part of the shared work).
