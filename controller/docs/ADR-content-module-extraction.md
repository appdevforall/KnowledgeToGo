# ADR: Extract the shared content-module plumbing (K2GO-449)

Status: Proposed

## Context

Content modules (Forgejo, Code on the Go add-ons, Code on the Go build assets)
each copy the same plumbing:

- A foreground download service per module. `ZimDownloadService`,
  `BooksDownloadService`, and now `CodeAssetsDownloadService` (K2GO-443) share one
  shell: a static `ContentDownloadSession`, static getters delegating to it, static
  start/pause/resume/cancel/retry, `onStartCommand` action dispatch, the
  notification, and the `ContentDownloadSession.Host` methods. Only the type, the
  START item-building, and the notification text differ.
- A per-module block in `redesign/ModuleActionSheet` and
  `redesign/ModuleDetailFragment`, keyed by `"<module>".equals(key)` (the READY
  action row and the installed-state button). It grows with every module.
- For the wrapper updaters, a near-identical refresh route trio in dash-node and a
  clone/baked mirror-path resolver duplicated across `tools/*-refresh.sh`
  (K2GO-440 added the resolver to a second wrapper; K2GO-443 a third would follow).

The K2GO-443 build-assets work added the third download service and the third
resolver. Per the design-coherence rule, several copies around one concern is a
redesign signal, not another patch.

## Decision

Extract the shared plumbing in small, separately reviewed slices, newest first,
without destabilizing the tested services.

1. Shared download-service base: an abstract `ContentDownloadServiceBase` (a
   `Service` implementing `ContentDownloadSession.Host`) that holds the generic
   instance behavior: `onStartCommand` dispatch of pause/resume/cancel/retry, the
   notification scaffolding, the channel, and the default `Host` methods. Abstract
   hooks give the per-module bits: the module's `ContentDownloadSession`, the
   START item-building (keys/labels/sizes/bodies), and the notification text/
   contentIntent. `CodeAssetsDownloadService` adopts it FIRST (it is the newest and
   smallest, and not yet depended on). `ZimDownloadService`, `BooksDownloadService`
   and the maps service migrate onto it in later slices, each reviewed on its own,
   so a ZIM regression is never bundled with the extraction.
   - The slice-2a listener-leak fix (clear on view-detach) and the terminal-order
     rule live in the shared UI driver, so every module gets them once.
2. Generic module-action registration: a small `ModuleActions` registry (per
   module: the action label, its gate, and its handler) that `ModuleActionSheet`
   and `ModuleDetailFragment` iterate, so neither carries a per-module `if`. Adding
   a module registers an entry; the shared UI files stop changing per module.
3. Shared refresh-wrapper lib: one sourced `tools/lib/updater-refresh.sh` with the
   clone/baked mirror resolver (using `[ -s ]`, not `[ -f ]`, per the K2GO-440
   review) and the common staging/swap body, for the wrappers that remain until
   their module migrates to the job engine.

## Migration order (safe, incremental)

1. `ContentDownloadServiceBase` + `CodeAssetsDownloadService` adopts it. (No tested
   service touched.)
2. `ZimDownloadService` / `BooksDownloadService` / maps adopt the base, one slice
   each, each reviewed and device-checked.
3. `ModuleActions` registry; `ModuleActionSheet` / `ModuleDetailFragment` iterate it.
4. Add-ons and Forgejo move to the durable job engine (K2GO-443), now without new
   clones. Retire each module's refresh route trio + wrapper as it migrates.

## Lifecycle / risk

- The per-type `ContentDownloadSession` singleton stays the single owner of
  progress/pause/reconnect state; the base adds no new state.
- Each tested-service migration is its own reviewed, device-checked slice; a
  regression is never bundled with the extraction itself.
- Back-compat: a module's old wrapper route stays until that module fully migrates
  and shipped APKs that call it have aged out.

## Consequences

- A new content module becomes config + module-specific code; the shared UI files
  and the service shell are untouched.
- The build-assets service clone (K2GO-443, transitional) is absorbed by step 1.
- No behavior change for users; this is structural. Each slice is reviewable and
  reversible on its own.
