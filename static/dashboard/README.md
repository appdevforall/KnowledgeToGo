# Dashboard REST core (dash-node)

The device's on-box REST engine (Node + TypeScript), fronted by nginx on the shared
`:8085` server under `/k2go-api/` (loopback-only). The app talks to it over REST; there is
no HTML/websocket surface. Booted via `ts-node` (which type-checks on start), built to
`dist/` with `tsc`.

## Two toolchains — this is deliberate, not a mistake

| Where | Tool | Command | Lockfile it uses |
|-------|------|---------|------------------|
| **CI** (`.github/workflows/dashboard-check.yml`) | **npm** | `npm ci --ignore-scripts` → `npm run typecheck` + `npm test` | **`package-lock.json`** (npm ci requires it) |
| **Device build** (`tools/dev-push-dashboard.sh`, `tools/rebuild-dashboard.sh`, installer) | **yarn** | `yarn install && yarn build` | none — resolves from `package.json` (there is no `yarn.lock`) |

CI type-checks and unit-tests every PR that touches `static/dashboard/`; the device path
compiles and runs the same TypeScript on the box.

## Versioning, and the `package-lock.json` "version" that looks out of sync

`package.json`'s `version` is the single source of truth — `routes.ts` reports it at
`/system/version`, and the self-update check (`versionGt`) compares against it.

**`package-lock.json`'s root `"version"` will usually lag behind `package.json`, and that is
expected — not a desync bug.** npm only rewrites that field when you run `npm install`; CI
runs `npm ci` (read-only, never rewrites) and device builds use yarn (never touches the npm
lock), so the field freezes at the last `npm install` while `package.json` advances with each
release. It is cosmetic metadata: **`npm ci` validates the dependency *tree*, not the project
version**, so the drift never breaks CI (the repo shipped 1.2.x with the lock frozen at 1.2.2
and CI stayed green). If you want them aligned for a release, either run `npm install` (the
canonical regenerator) or hand-edit the two project `"version"` entries — do **not** touch
dependency `"version"` fields. Don't spend a second doubting the drift itself.

## Test / typecheck locally

```
npm ci --ignore-scripts   # matches CI (skips the better-sqlite3 native build)
npm run typecheck
npm test
```
