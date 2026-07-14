# K2Go docs (tier-3 help) — drop-in space

Static, nginx-served documentation for the K2Go three-tier help (ADFA-4536 / ADFA-4615).
Plain static HTML under `http://localhost:8085/k2go-docs/`.

## How it works
- The portal index (`static/site`) probes `/k2go-docs/` and shows the "K2Go docs"
  module only when the probe does NOT return 404.
- While this folder has no `index.html`, nginx returns 404 (see `k2go-docs-nginx.conf`),
  so the module stays hidden — infrastructure ready, nothing shown yet.

## Delivering documentation
1. Drop the delivered static HTML here (must include an `index.html` at the root).
2. Article pages map to tooltip IDs: `/k2go-docs/<tooltipId>` (the app links here).
3. On the next portal load the module appears automatically (probe -> 200).

## Build / rootfs
Deploy this folder to the rootfs web root and include `k2go-docs-nginx.conf` in the
:8085 nginx server (same mechanism as `static/dashboard`). All editions, ARM 32 + 64-bit.
