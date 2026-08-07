# dash-node (REST API) - changelog

One line per version, newest first. Every REST-facing change bumps the version in `package.json`
(the app surfaces it via `/system/dashboard/update-check` and the "Update available" pill), so this
file is the human record of what each bump enables. Keep entries short: `version - change (TICKET)`.

- **1.2.0** - Self-update cutover baseline: no endpoint change, but from this version the app updates the dash-node core **live over REST** (`POST /system/dashboard/rebuild`, the blue-green rebuild from ADFA-5011) instead of a proot rebuild. Installs on < 1.2.0 still use the proot path to reach 1.2.0; 1.2.0+ update to 1.2.1+ via REST. (ADFA-5051)
- **1.1.6** - Kolibri modules now in English. REST-facing because the diagnostic text changed: the `blockers[]` strings from `GET /kolibri/ready` and the `error` text on Kolibri jobs. Shapes, status codes and field names are unchanged.
- **1.1.5** - Credentials: `POST /credentials/calibre` now validates against live Calibre-Web (401 on reject, save-unverified when the service is down); `GET /credentials/:service` returns the default password only while still at the factory default, for full form prefill. (ADFA-5044)
- **1.1.4** - Calibre-Web auto-login: send Flask-Login `remember_me` so the session includes a persistent `remember_token`, which sticks in the WebView despite anonymous/guest browsing. (ADFA-5043)
- **1.1.3** - `/auth/:service/session`: server-side login returning the session cookie, for WebView auto-login as box admin (Calibre-Web / Kolibri). (ADFA-5043)
- **1.1.2** - ZIM download URL built from the project subdir (`/zim/<project>/<file>`), so non-Wikipedia ZIMs download instead of 404ing. Pairs with the app sending `<project>/<file>`. (ADFA-5042)
- **1.1.1** - `/system/dashboard/update-check`: reports installed vs. latest so the card can show "Up to date / Update available". (ADFA-5026)
- **1.1.0** - In-app rebuild of the dash-node REST core (self-update) + versioning discipline starts. (ADFA-5011)
- **1.0.1** - Rebrand to K2Go Dashboard. (ADFA-4445)
