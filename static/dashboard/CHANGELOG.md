# dash-node (REST API) - changelog

One line per version, newest first. Every REST-facing change bumps the version in `package.json`
(the app surfaces it via `/system/dashboard/update-check` and the "Update available" pill), so this
file is the human record of what each bump enables. Keep entries short: `version - change (TICKET)`.

- **1.1.4** - Calibre-Web auto-login: send Flask-Login `remember_me` so the session includes a persistent `remember_token`, which sticks in the WebView despite anonymous/guest browsing. (ADFA-5043)
- **1.1.3** - `/auth/:service/session`: server-side login returning the session cookie, for WebView auto-login as box admin (Calibre-Web / Kolibri). (ADFA-5043)
- **1.1.2** - ZIM download URL built from the project subdir (`/zim/<project>/<file>`), so non-Wikipedia ZIMs download instead of 404ing. Pairs with the app sending `<project>/<file>`. (ADFA-5042)
- **1.1.1** - `/system/dashboard/update-check`: reports installed vs. latest so the card can show "Up to date / Update available". (ADFA-5026)
- **1.1.0** - In-app rebuild of the dash-node REST core (self-update) + versioning discipline starts. (ADFA-5011)
- **1.0.1** - Rebrand to K2Go Dashboard. (ADFA-4445)
