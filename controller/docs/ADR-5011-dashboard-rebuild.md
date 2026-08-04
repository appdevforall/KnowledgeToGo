# ADR-5011 — Rebuilding the dash-node REST core without a rootfs rebuild

Status: Proposed (ADFA-5011). **Track A** (app-orchestrated, box-asleep rebuild) targeted for this PR;
**Track B** (REST self-update toolchain) seeded now, matured incrementally toward v2.

## Context

- dash-node (the in-server REST core: Express on `127.0.0.1:4000`, fronted by nginx, run by `pdsm`) is
  just compiled TypeScript under `/library/dashboard`. Yet updating a few of those files currently
  requires the **full ~2h rootfs rebuild**, because that is the only shipping path. `tools/dev-push-dashboard.sh`
  already does an in-place update on the box, but only as a **manual** dev step run from a terminal
  inside the proot — most users can't do that.
- **Single proot (from ADR-4832).** `PRootEngine` has no mutual exclusion: a second concurrent proot
  operation over the same rootfs collides (shared `/tmp`, `/dev/shm`, ports, service restarts, global
  `killall -9 proot`) and corrupts state. proot also **cannot be entered after start**. And you can't
  "just kill the REST": stopping dash-node/proot takes the **whole** engine down (kiwix-serve, nginx,
  everything). So any self-update must respect "one proot op at a time".
- **dashboard is core, not a user module (ADFA-4842).** It is the REST API that maps FQR and all
  content downloads depend on — not installable/removable like books/maps/kolibri.
- dash-node **stalled at 1.0.1** through many structural changes: there was no versioning discipline,
  so "installed version" told users nothing.

## Decision

### Track A — app-orchestrated, box-asleep rebuild (primary; this PR)

1. **The app is the surgeon; the box is the asleep patient.** This inverts the normal pattern (box
   orchestrates, app observes): for a self-update, the app drives a **gated sequence** of proot
   commands and reads each output to decide whether to advance. Everything proot is **down** during
   the op (accepted; it is brief), which sidesteps the single-proot constraint entirely — there is
   never a second, overlapping proot.
2. **App-provided, idempotent, version-independent scripts.** The app carries its own tools (bundled /
   pushed), so the rebuild does **not** depend on what tooling the box already has, nor on the REST
   API being reachable. This is what makes it work from **any** installed version, including 1.0.1 —
   there is no bootstrap chicken-and-egg. Scripts must be idempotent and safe across 1.0.1 / 1.1 /
   1.2 / 2.0, like the upstream iiab/iiab Ansible roles the other modules run.
3. **Gated sequence (2–3 steps), each verified before the next:**
   - **Preflight + backup** (`tools/preflight-dashboard.sh`): non-destructively confirm `/opt/iiab-android`
     is a clean git repo on `main` that can `git fetch`, that the required tools exist, that there is
     disk headroom, and report installed vs available version. If anything obstructs → abort with a
     clear reason; **nothing is touched**.
   - **Fetch + build + test** (`tools/rebuild-dashboard.sh`): `git reset --hard origin/main` → build in
     a **staging** dir → **smoke-test the staged build** on a temp port (`tools/dashboard-smoketest.sh`).
   - **Swap + verify**: promote the staged `dist` only if it passed → restart dash-node → re-verify
     live; on any failure **roll back** to the step-1 backup and leave the box as it was.
4. **Reuse the module pipeline + gates.** Run through `PRootEngine` under `InstallGuard` with the
   module **status-window** UX: the user **cannot leave mid-rebuild**, and re-entering returns to the
   live status window (same protection as an Ansible module install). Scope the stop to dash-node
   where possible rather than a full engine teardown.
5. **Surface as a rebuild-only "system module" card** (module template like matomo/maps/kolibri, but
   the only action is **Rebuild** — no install/remove/hide). This keeps dashboard as core (ADFA-4842)
   while giving it a home consistent with the other modules. The card shows **installed vs available**
   version and flags "behind".
6. **Versioning.** Bump dash-node `1.0.1 → 1.1.0`; increment per change from here. Installed version is
   read from `package.json` **via proot** (present in every version — version-independent), not from a
   REST endpoint. The in-app rebuild is available **from 1.1.0 on**; a box still on 1.0.1 bridges once
   via a normal update/install (or the manual `dev-push` script), then is self-service. Rebuilds always
   jump to the tip of `main` (not incremental).

### Track B — REST self-update toolchain (secondary; seeded now, matured later)

7. dash-node progressively gains its **own** ability to update itself "consciously": a toolset that can
   answer "do I have everything I need?" and "did my own tests pass?" and otherwise refuse. Seeded in
   this PR as **groundwork, not the primary path**: `GET /system/version`, `POST /system/dashboard/rebuild`
   (detached), `GET /system/dashboard/rebuild/status`, and the smoke test. These are kept but **not
   relied on** for the shipping flow (they carry the self-reference + bootstrap problems Track A
   avoids). Over time (toward v2) this can become the fast path for boxes already on a capable version,
   layered on top of the safe app-orchestrated floor.

## Consequences / caveats

- **Downtime:** the whole proot engine is down for the rebuild (~1–2 min). Accepted trade-off for
  reliability and single-proot safety. It is still far cheaper than the ~2h rootfs rebuild.
- **Inverted control:** the app orchestrates step-by-step while the box is passive — deliberate, and
  the opposite of the content-download flow (box-owned durable jobs, ADR-4832).
- **Idempotent/version-independent scripts are a hard requirement**, or an update from an old version
  could wedge a half-applied state. The build→test→swap→rollback structure means a failed build never
  ships; the previous `dist` is restored.
- Track B's REST endpoints exist but must not be advertised as the way to trigger a rebuild yet.

## Alternatives considered

- **REST-alive self-rebuild ("local anesthesia").** Minimal downtime, but the REST call rebuilds the
  process serving it (self-reference → needs `setsid` detachment) and depends on the endpoint already
  existing (bootstrap: absent on 1.0.1). Rejected as the primary path; retained as the Track B seed.
- **Full engine teardown via a dedicated Ansible role.** Unnecessary — we only rebuild dash-node and do
  not need a second proot; the app-orchestrated sequence is lighter and sufficient.
- **A separate command server (cmdsrv).** Already rejected in ADR-4832; still applies.

## References

`tools/dev-push-dashboard.sh`, `install_iiaboa_dashboard` (top-level `iiab-android`),
`static/dashboard/{server.ts,routes.ts,package.json}`, `tools/rebuild-dashboard.sh`,
`tools/dashboard-smoketest.sh`, `tools/preflight-dashboard.sh`, `PRootEngine`,
`InstallService`/`InstallGuard`, `ModuleRegistry` (ADFA-4842: dashboard-is-core), ADR-4832
(single proot / in-server channel).
