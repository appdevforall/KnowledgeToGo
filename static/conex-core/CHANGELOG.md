# conex-core (content-exchange core) - changelog

One line per version, newest first. Every REST-facing change bumps `conex_core.VERSION` (reported by
`GET /v1/version`, and written into the `[conex_core]` section of iiab.ini by the conex_core Ansible
role), so this file is the human record of what each bump enables. Keep entries short:
`version - change (TICKET)`.

- **0.3.0** - /v1/share gate: a token-scoped, two-lifetime share session (ephemeral connection vs durable session) over a scoped item set, reusing the export op per item, for granular wireless Share. (K2GO-376)
- **0.2.0** - export as a prepare/poll/fetch long operation, so a large Kolibri channel stages without timing out the client. (K2GO-507)
- **0.1.0** - initial content-exchange core: read (list + identity), export, import and delete over /v1 across Kiwix, Kolibri and Calibre. (K2GO-427)
