# OTA update notes

Minimal, hand-curated notes shown in the in-app "update available" dialog.
One entry per published version, newest on top, just 1-3 editorial lines about the
main idea of the release — NOT a full changelog. The CI copies the topmost entry
verbatim into `update.json`; it never auto-generates it.

The full/official history lives in the GitHub Release notes (auto-generated on the
tag). This file is only the short summary end users read when they update.

Rule: the version header must match the release tag / `versionName` so the CI picks
the right entry.

## v0.7.0-beta
Welcome to Knowledge to Go. v0.7.0-beta brings a resilient install (pause/resume),
honest system state, and a refreshed Connect & Clone. Enjoy life offline.

## v0.6.0-beta
A complete redesign of the app and the setup experience — the biggest update since 0.5.
Setting up, backing up, restoring, and phone-to-phone sharing are now smoother and more reliable.
Installing this update is recommended.
