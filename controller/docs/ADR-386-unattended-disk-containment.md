# ADR-386 — Unattended disk-fill containment & maintainability (prevent · contain logs · reap-restart from the app)

**Status:** proposed — design only, no production code. Lands as small diffs a maintainer verifies. This
is the single home for the K2GO-386 disk-fill work; if a layer's detail grows it splits into a lettered
delta (`ADR-386a`, `ADR-386b`) rather than a separate ADR.

**Scope note (form):** genericized per the ADR authoring convention — no personal names, no device
identifiers. "the box" = the Debian userland under proot; "the device" = the Android target; "upstream"
= Internet-in-a-Box (iiab/iiab).

---

## 1. Why this exists (the stakes, stated plainly)

A single misbehaving box process can **saturate the device's disk in hours** — faster on a faster phone
— until it hits `ENOSPC`. When that happens the server breaks *and the user's phone is left full and
unusable for anything else*. We have measured this class directly: an orphaned `php-fpm` busy-looping
into its log at ~600 MB–1.3 GB/min, and unbounded service logs reaching ~1.8 GB each with nothing ever
trimming them.

Our users are **not technical**. The product's promise is a server that just works on a phone. So the
goal of this effort is not to detect-and-blame; it is to make the system **robust and simple to
administer**:

- **resolve these situations unattended**, through **parameters** (thresholds/intervals we can tune), not
  through a human running commands on the box;
- **keep the system alive** — recover by containing or restarting, not by denying service and walking
  away (a monitor that spends battery only to leave the phone stopped in a bad state is worse than none);
- **report to the developers** the situations that warrant attention, so we learn what happens in the
  field without the user having to notice or act.

Everything below serves that goal. This is a maintainability effort as much as a bug fix.

## 2. The problem, as facts

Three failure shapes, all the same root class (a box process consuming disk faster than anything reclaims
it), plus the environment that makes them hard:

- **Vector A — a runaway *real* log file:** visible on disk, grows without bound.
- **Vector B — deleted-but-open:** the process holds an *unlinked* fd; disk is consumed but there is **no
  file** to find or rotate. Only **closing the fd (stop/restart the process)** frees it.
- **Speed:** the php busy-loop is a *firehose* (≫ any gentle rotation interval); most logs are a slow drip.

Environment facts that shape every decision:

- **proot has no systemd and no running cron** → `/etc/cron.daily/logrotate` **never runs**; logrotate is
  installed but never triggered (root cause of the unbounded logs).
- **Upstream configs assume a Raspberry Pi** (systemd, always-on, cron, signal-based `postrotate`). Well
  made for that world; unusable in ours.
- **An orphaned service is off proot's ptrace**, so an in-box healer cannot reach it — device-proven, only
  an **outside-the-rootfs** actor (the Android app) can stop it. This is why the last layer lives in the app.

## 3. The strategy — three layers, one home

| Layer | Owns | Failure it handles | Where it runs |
|---|---|---|---|
| **L1 Prevent** | php-fpm does not run idle | removes the *cause* of the known orphan-loop on a default build | rootfs (ansible/patch) |
| **L2 Contain logs** | bounded, proot-correct rotation | Vector A, steady/moderate growth | in-box (dash-node-triggered) |
| **L3 App backstop** | reap + **restart to keep alive**, and report | Vector B + the fast firehose; the net for anything L2 misses | Android app (outside rootfs) |

They are complementary, not redundant: L1 stops one cause, L2 bounds ordinary growth cheaply, L3 is the
outside net for the acute and the invisible. Reporting (§7) spans all three.

## 4. Layer 1 — Prevent (php-fpm not idle)

The idle `php-fpm` enabled unconditionally by the nginx role is the orphan that busy-loops. We move
php-fpm ownership to the roles that use it (Matomo enables its own), so a default build never runs it →
it cannot orphan. Carried as `tools/upstream-patches/0002-php-fpm-role-ownership.patch` (WIP upstream);
PR #543. **Note:** the rootfs build sources patches from `main`, so this takes effect only once merged
to `main` — a branch bake still reads `main`'s patches (verified).

## 5. Layer 2 — Contain logs (K2Go owns its proot logging)

We **own a centralized, proot-correct log-rotation config set** for the services we run, discarding the
RPi-oriented inherited snippets (learn from them, write ours):

- **`copytruncate`, never a postrotate signal** — proot cannot drive `systemctl`/`invoke-rc.d`, and a
  failed reopen is exactly Vector B. copytruncate truncates in place; the writer keeps its fd.
- **Size-based** (`size 100M`, `rotate 3`, `compress`, `delaycompress`, `missingok`, `notifempty`,
  `su root root`) — our threat is a file that grows, not a calendar.
- **Trigger = dash-node** (the box's always-up process), **every 10 min, no run at boot** — boot is the
  heaviest/most fragile moment (Python services starting; phantom-process-killer risk). Accepted tradeoff:
  a <10-min session never rotates; the size cap + the guard below bound it.
- **A firehose guard runs FIRST, in the same tick** (deterministic order, one clock — no second timer to
  drift): before logrotate, dash-node truncates any log past a firehose threshold (~1 GiB) **in place**,
  so logrotate never has to copy a multi-GB runaway (a copy doubles disk and pegs CPU on a weak phone, and
  still would not stop the writer). This is the in-box half of L3 (§6); a recurring firehose is flagged for
  the app-side reap.

**The config set (verified on-device against the pdsm wrappers + live `/var/log`):**

| Service | Log | In the set |
|---|---|---|
| php-fpm | `/var/log/php8.4-fpm.log` | **yes** — override inherited snippet (add size cap, copytruncate) |
| nginx | `/var/log/nginx/*.log` | **yes** — override (drop `invoke-rc.d`, copytruncate) |
| calibre-web | `/var/log/calibre-web.log` (pdsm redirects here) | **yes** — added (`missingok`) |
| dash-node | `/var/log/dash-node.log`, `/var/log/dash-rebuild.log` | **yes** — added |
| kiwix | — none (`kiwix-serve --daemon`, no redirection) | **excluded** (no file to rotate) |
| kolibri | `/library/kolibri/logs/*.txt` (+ `archive/`) | **excluded** (self-managed, bounded ~64 KB) |

**Install:** an idempotent `tools/setup-proot-logging.sh` (ansible-role-shaped) moves the inherited
nginx/php-fpm snippets out of `/etc/logrotate.d` (we override them; a duplicate path fails logrotate),
writes `/etc/logrotate.d/k2go`, and validates with `logrotate -d`. It runs **at deploy time, not at
dash-node startup** — reconfiguring on every boot would be needless churn (and updating the config is
exactly a deploy concern). The three deploy paths call it: the **rootfs build** (`iiab-android`'s
`install_iiaboa_dashboard`), so a clean R2 rootfs ships preconfigured; and the two update paths
(`rebuild-dashboard.sh`, `dev-push-dashboard.sh`). Since the version bump that carries a dash-node
change is delivered through the dashboard-update mechanism, an update that reaches a device also
re-asserts this config. Phase 2 folds it into a rootfs ansible role with a pdsm-owned trigger.

## 6. Layer 3 — App-side backstop (reap + restart, not deny)

The outside-the-rootfs net for what L2 cannot catch: the **fast firehose** (fills faster than a 10-min
rotation) and **Vector B** (no file to rotate; must stop the holding process). Device-proven: only the
app can stop an off-proot orphan.

**L3 splits into two halves at the proot boundary — this matters:**
- **In-box (dash-node) — DETECT + RECLAIM.** dash-node can *see* a firehose log and *truncate* it in
  place (reclaim, no copy) — but it **cannot stop an off-proot orphan** (an in-box kill does not reach it,
  device-proven; the same limitation that parked the K2GO-381 in-box healer). This half is already coupled
  to L2's tick as the firehose guard (§5): it protects L2 and bounds the disk (each tick truncates the
  runaway back, so even an unstoppable orphan cannot reach ENOSPC as long as headroom > rate×interval),
  and it emits the recurring-firehose signal.
- **App-side (Android) — STOP.** The only actor that can reap an off-proot orphan. It is driven by the
  in-box recurring-firehose signal (a log that keeps refilling after truncation = an orphan) or by disk
  pressure, and it reaps + reports. This is the half that is still to be built.

**Decisions (direction; detailed mechanism is the next design step, possibly `ADR-386a`):**

- **Targeted, not blind.** Act on the offending vector (a specific runaway log/service), keeping the rest
  of the system up. NOT the rejected "disk full → stop everything → stay down → user fixes it."
- **Restart to keep alive.** A fresh service under a fresh proot does not busy-loop, so the recovery is
  **reap + reclaim + relaunch through the lifecycle owner** (the ADR-5343 reconciler / desired-state), so
  the box comes back — unattended. Stopping-and-staying-down is a last resort only, never the default.
- **Parametric.** Thresholds and cadence are parameters we tune (floor, growth-rate, intervals), not
  hard-coded cliffs; the primary directive right now is **disk free space / abnormal growth**.
- **Single surface.** Watching disk pressure / abnormal log growth catches *any* vector, not just php.

**Open (design next):** the exact leading-indicator (growth-rate vs absolute), the targeted-vs-full
decision, and how relaunch coordinates with the reconciler. The existing `feat/K2GO-386-disk-guard`
slice (device-verified) is the scaffold to reorient from "stop + stay-down" to this.

## 7. Reporting to developers (unattended, cadence-based)

When any layer contains or recovers a situation, it should tell us — so field robustness is measured, not
guessed — **without bothering the user**:

- **Optional and cadence/rate-based:** a rare anomaly → a low-cadence digest (daily/weekly/monthly); a
  recurring one (e.g. hourly) → escalate ("we're noticing X; send a report?"). A frequency/rate rule sets
  the cadence.
- **Reuse the delivery backbone** (`DeliveryManager` / the debug-delivery path) rather than a new channel.
- The user is *informed*, not *tasked*: a report goes to developers; recovery already happened.

## 8. Lifecycle (who sets it, who clears it, what if a process dies)

- **L2 config:** installed at deploy time (rootfs build + rebuild/dev-push), idempotent — writes only
  when changed, the snippet move is a no-op once done; re-asserted on every update, not every boot. No
  persistent marker to strand.
- **L2 trigger:** dash-node's 10-min timer; owns nothing but the timer. If dash-node dies, logs only grow
  while services are up and the reconciler owns bring-up; if dash-node *wedges*, rotation stalls — the one
  Phase-1 liveness dependency, bounded by L3, removed in Phase 2 (pdsm-owned trigger).
- **L3:** a poller for the life of a box-up session (started once, stopped on teardown); recovery routes
  through the ADR-5343 desired-state owner, so no second source of "should the box be up."
- **State:** logrotate's own status file + `rotate N`/`compress` bound disk; nothing we add persists
  unbounded.

## 9. Forks considered and rejected

- **Run cron/crond in the box** — Phase 1 rejects it (dash-node can schedule); revisit only for a
  dash-node-independent trigger (Phase 2 pdsm).
- **Keep upstream snippets, just trigger them** — rejected: no size caps + proot-broken postrotate signals
  (re-creates Vector B).
- **A light logrotate pass at boot** — rejected for now (boot lightness wins; cap + L3 bound the gap).
- **Bake L2 into the rootfs now** — deferred to Phase 2, not rejected.
- **L3 = stop-and-stay-down** — rejected: denies service to a non-technical user (the whole point of §1).

## 10. Verification (per layer)

| Layer | Check | Expected |
|---|---|---|
| L1 | rootfs built with the merged patch | php-fpm installed, **not enabled**, not running on a default build |
| L2 | `logrotate -d` after install; a log grown past `size`, then a trigger | parses clean; truncated in place; writer keeps writing; no orphaned deleted-but-open file |
| L2 | short (<10 min) session | no boot rotation (by design); bounded next long session |
| L3 | fast fill + Vector B (synthetic) | contained/recovered without denying service; system back up; a report enqueued |

## 11. Consequences

- Disk-fill is handled unattended across its vectors, and the phone is kept usable — matching §1.
- We carry a small owned config set instead of inherited RPi drift; new services get one block, one place.
- One liveness dependency remains in Phase 1 (dash-node as L2 trigger); named, bounded by L3, removed in
  Phase 2.
- Field occurrences are reported to developers, so we tune the parameters from real data rather than
  guesses.
