# conex-core (content-exchange core) - changelog

One line per version, newest first. Every REST-facing change bumps `conex_core.VERSION` (reported by
`GET /v1/version`, and written into the `[conex_core]` section of iiab.ini by the conex_core Ansible
role), so this file is the human record of what each bump enables. Keep entries short:
`version - change (TICKET)`.

- **0.3.3** - observability: log every error response and uncaught handler exception to stdout (the supervisor captures it to /var/log/conex-core.log), and line-buffer stdout/stderr in __main__ so output reaches the log while the server runs forever, so a failed import or share leaves a box-side trace; an uncaught error now returns a 500 JSON instead of dropping the connection. (K2GO-376)
- **0.3.2** - /v1/import/item returns 507 (not 422) when a stage runs out of disk (adapters tag it code "no_space"), so the front stops the run with a space message; /v1/export/prepare drops the proot-based free-space preflight too (same false-507 as import), failing cleanly during staging instead. (K2GO-376)
- **0.3.1** - /v1/import/item drops the free-space preflight (proot under-reports free space, so the 507 false-rejected valid imports); a real ENOSPC during ingest now fails cleanly as 422. (K2GO-376)
- **0.3.0** - /v1/share gate: a token-scoped, two-lifetime share session (ephemeral connection vs durable session) over a scoped item set, reusing the export op per item, for granular wireless Share. (K2GO-376)
- **0.2.0** - export as a prepare/poll/fetch long operation, so a large Kolibri channel stages without timing out the client. (K2GO-507)
- **0.1.0** - initial content-exchange core: read (list + identity), export, import and delete over /v1 across Kiwix, Kolibri and Calibre. (K2GO-427)
