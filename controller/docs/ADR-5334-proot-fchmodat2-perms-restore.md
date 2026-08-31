# ADR-5334 — proot must translate `fchmodat2`; the rootfs build detects capability, it does not patch call sites

**Status:** proposed — design only, no production code. Landed as sub-phases, each a diff + commit
message; a maintainer commits and runs verification.

**Scope note (form):** genericized per the ADR authoring convention — no personal names, no specific
device identifiers. "the build host" = the machine (CI runner or SBC) that bakes the rootfs; "the
device" = the Android target that runs the same proot at runtime.

---

## 1. The bug, stated as a missing fact

The rootfs bake replicates the on-device proot environment on a build host and runs the IIAB install
under it. After Ansible, the install performs several **recursive, permission-preserving copies as
root under proot** (`tar -x`, `cp -a`). On a build host with **glibc ≥ 2.39 and kernel ≥ 6.6**, the C
library restores a directory's mode with the **`fchmodat2` syscall (452)**, called as
`fchmodat2(dirfd, path, mode, AT_SYMLINK_NOFOLLOW)`.

The bundled proot does **not** know syscall 452. proot rewrites guest paths only for syscalls in its
translation table; an unknown syscall passes through untranslated, so the **guest** path
(`/library/www/html/home/js`) reaches the **host** kernel, which has no such path → `ENOENT`. The copy
aborts, `iiab-android` (`set -e`) exits rc=2, `flag_install_ready` is never written, and every tier
lands on HOLD.

The single missing fact is not "site-updater's tar is wrong" and not "cp -a is wrong." It is:

> **proot does not translate `fchmodat2` (452).**

Everything else is a symptom. The install always worked — and still works on the device and on the
historical build host — precisely because their older glibc uses the classic `fchmodat`, which proot
*does* translate. This is an environment capability regression, not an install-script defect.

## 2. The measured evidence (do not infer from versions)

Under glibc 2.39, `strace -e trace=chmod,fchmodat,fchmodat2` on the exact operations:

| Operation | Calls the failing syscall? |
|---|---|
| `tar -xpf` (root default, preserve perms) | **yes** — `fchmodat2(…, "./css", 0755, AT_SYMLINK_NOFOLLOW)` |
| `tar --no-same-permissions -xf` | **yes** — identical; the flag changes the *target mode*, not whether the chmod happens |
| `cp -a --no-preserve=mode` | **no** — issues no chmod at all |

Conclusions that drive the decision:
- A `tar` flag **cannot** avoid it — the directory-mode restore is unconditional. The earlier
  `--no-same-permissions` patches are therefore ineffective and must be reverted.
- The failure is a property of the **(glibc, kernel, proot)** triple, not of any one call site. It
  must be **measured directly**, never guessed from a version string (a version check would be a second,
  drifting copy of the real fact).

## 3. Decision — one fix, in the one place that owns the fact

**Teach the app's proot to translate `fchmodat2` (452) exactly as it translates `fchmodat`.** The two
syscalls share their first two arguments `(dirfd, pathname)`; proot only needs those for path
translation, then lets the host kernel (≥ 6.6 has 452) perform the chmod. Concretely, inside the
existing proot build patch (`tools/proot-builder/build_static.sh:118` — the `# --- PROOT PATCH ---`
block that already sed-patches `packages/proot/build.sh`):

- add `fchmodat2` to `src/syscall/sysnums.list`, and
- route 452 through the same path-translation case as `fchmodat` in the syscall enter handler.

`termux/proot` master does **not** carry 452 (verified: `sysnums.list` lists only `SYSNUM(fchmodat)`),
so this is a **patch, not a version bump** — bumping `PROOT_VER` (`build_static.sh:99`) would not help.

This one change makes `tar` / `cp` / `rsync` work **unmodified**, on the device and on any build host,
old or new. It **collapses the pre/post-`fchmodat2` distinction** rather than encoding it in the
installer. It is also the only change that fixes the device itself if a device toolchain ever adopts
glibc ≥ 2.39.

## 4. Decision — measure the capability; never assume it (detection-first + fail-closed)

Two probes of the **same single fact**, at the two points where it matters. Both run the real
operation under the real proot; neither sniffs a version.

**4.1 Publish gate (owns "is this proot binary correct?").** Extend the existing binary smoke test
(`tools/proot-builder/smoke-native-binaries.sh`, ADFA-4465, already runs on `ubuntu-24.04-arm` — a
post-`fchmodat2` host) with a **perms-restore probe**: under the freshly built proot, extract a tiny
tree containing sub-directories with `tar -xp` (and copy with `cp -a`) into a throwaway rootfs, and
assert exit 0 and that a directory's mode was actually set. On the current (unpatched) proot this probe
**fails on 24.04** — i.e. it reproduces this very bug — so it blocks publishing a proot that cannot do
the restore. After §3 it passes. This is the single source of truth for the capability.

**4.2 Bake guard (uses the fact; never ships broken).** At the start of `build-iiab-rootfs.sh`, run the
same perms-restore probe once with the proot this run will use:
- **OK →** proceed on the **normal path** (§5, route A).
- **BROKEN →** **fail fast** with a diagnostic that names the missing `fchmodat2` translation and points
  here — instead of running the whole install and leaving every tier on a silent HOLD.

The guard measures the fact at the point of use; it does not re-derive it and it is a no-op once §3
ships. Fail-closed replaces "build, then discover HOLD."

## 5. Decision — two routes, and the normal route is byte-identical to today

- **Route A — normal (pre-`fchmodat2`, or any host with the patched proot).** The install scripts run
  **exactly as they always have**: no `--no-same-permissions`, no `--no-preserve=mode`, no per-call-site
  flags. This is the route the device and the historical build host already take, so it carries **zero
  regression risk**. All the scattered flag edits on `fix/ADFA-5334-site-updater-proot-safe-mirror`
  (`site-updater.sh:24`; `iiab-android:412,471,479,705`) are **reverted** — they are the duplicated,
  per-site truth this ADR exists to remove.
- **Route B — post-`fchmodat2` host with an unpatched proot.** There is deliberately **no install-script
  route B.** The only sanctioned responses are: (B1, durable) the patched proot from §3 → the probe
  passes → route A; or (B2, interim only) run the bake on a **pre-`fchmodat2` host image** (glibc < 2.39,
  e.g. an `ubuntu-22.04-arm` runner) so the probe passes → route A. B2 is a **configuration** choice
  (runner image), not code, and it disappears the moment §3 ships.

The point the maintainer raised — "fix for one and break the other and we never finish" — is answered
structurally: only route A ever installs, route A is unchanged, and the capability is proven before use.

## 6. Forks considered and rejected

- **Different runner hardware.** Rejected: GitHub-hosted arm hardware is fixed, and the lever is not
  hardware — it is the userspace **glibc** in the runner image, which *is* selectable (that is B2).
- **"Downgrade Ubuntu" as the fix.** Rejected as *the fix*, kept as *interim fallback (B2)*. It is
  correct and faithful (it is where the build already works) but it is a reprieve: 22.04 reaches EOL, and
  a future device/build glibc bump resurfaces the bug. §3 is the actual resolution.
- **Per-call-site flags (`tar --no-same-permissions`, `cp --no-preserve=mode`).** Rejected: proven
  ineffective for `tar` (§2), semantics-changing for `cp` (drops exec bits), and — most importantly — a
  second copy of the fact in N places, which the reduction gate forbids.
- **Bump proot instead of patch.** Rejected: upstream lacks 452 (§3).

## 7. Reduction scorecard (acceptance gate)

| Metric | Before | After | Evidence |
|---|---:|---:|---|
| Places that encode "how to copy safely under proot" | **≥ 4** call sites (site-updater, demo, yarn, dashboard, pdf.js) | **0** — the install copies are untouched | §5 |
| Sources of the "does proot restore perms?" fact | 0 measured (assumed per site) | **1 measured**, guarded at point of use | §4 |
| Syscall-translation gap in proot | **1** (452 unhandled) | **0** | §3 |
| Build outcome on a broken environment | silent HOLD after a full install | **fail-fast diagnostic** | §4.2 |
| Net new install-script behavior | — | **0** (route A unchanged) | §5 |

Every row goes down or holds; the fix is a subtraction from the install and an addition in the one
place that owns the fact. Gate satisfied.

## 8. Verification matrix

| Environment | Expected |
|---|---|
| Pre-`fchmodat2` build host (glibc < 2.39), unpatched proot | probe OK → route A → all tiers reach `flag_install_ready` (unchanged) |
| Post-`fchmodat2` host (glibc ≥ 2.39), **unpatched** proot | probe BROKEN → **fail fast**, no HOLD, no half-baked rootfs |
| Post-`fchmodat2` host, **patched** proot (§3) | probe OK → route A → all tiers green |
| Device runtime, patched proot | unchanged install/boot behavior; on-device proot verification per the standard device gate |

Smoke-test (§4.1) is the pre-publish gate for the proot binary; the bake matrix above is the
integration gate.

## 9. Sub-phasing (each = diff + proposed commit message; a maintainer commits + verifies)

- **5334-p1 — stop the bleeding without duplicating (config + guard).** Revert the scattered flag edits
  (route A restored). Add the bake perms-restore **guard** (fail-closed, §4.2). Pin the bake to a
  pre-`fchmodat2` runner image (B2). **Gate:** a bake run is green (via B2) and the guard fails fast when
  pointed at an unpatched proot on a post-`fchmodat2` host. Ships the unblock with zero install-script
  workarounds.
- **5334-p2 — the real fix (proot).** Add `fchmodat2` (452) to the proot build patch (§3) and the
  perms-restore probe to the binary smoke test (§4.1). **Gate:** smoke test green on `ubuntu-24.04-arm`;
  then device verification of a normal install/boot on the patched proot.
- **5334-p3 — collapse.** With the patched proot released, remove the B2 runner pin; the bake runs on any
  host on route A. The bake guard stays as a canary. **Gate:** a green bake on a post-`fchmodat2` runner
  with the patched proot.

**No production code until this note is approved.** Stop at each gate (note → p1 diff → verify →
p2 diff → device-verify → p3).

## 10. Consequences

- The install scripts and the device path stay on the code that has always worked; the risk is confined
  to one proot patch behind a smoke-test gate and a device gate.
- New recursive copies added to the install in future need **no** special handling — the invariant is
  "proot restores perms," proven by the probe, owned in one place.
- If proot is ever re-based on an upstream that adds 452, the patch becomes a no-op and is dropped; the
  probe keeps the guarantee honest across that change.
