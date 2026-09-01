# ADR-5368 — the app identifier moves off the IIAB namespace; the attribution stays

**Status:** proposed

## 1. The decision in one line

Move both the `applicationId` and the Java `namespace` from `org.iiab.controller` to
**`org.appdevforall.k2go`**, accept that installed devices reinstall, and **keep every IIAB
attribution exactly where it is**.

## 2. What the identifier is, and what it is not

Three different names carry "identity", and conflating them makes this look either trivial or
impossible:

| | what it is | who sees it | changing it |
|---|---|---|---|
| `applicationId` | the install identity Android keys everything on | nobody, outside Settings and adb | a **different app** |
| `namespace` (Java package) | how the source is organised | developers | mechanical |
| `android:label` | the product name | everyone | free |

`android:label` is already **"Knowledge to Go"** — the user-facing rebrand happened long ago. What
remains is an identifier that says `org.iiab`, and that is a **reverse-DNS namespace claim on
iiab.org**: it declares which organisation publishes this app.

**This is an administrative decision, not a technical one.** Nothing is broken today, and no
external constraint forces it. AppDevForAll publishes this app and the identifier should say so.
It is recorded as an ADR because, unlike most naming, it has consequences that cannot be undone
once devices are in the field (§3, §9).

## 3. Why now: the window closes and never reopens

Android provides **no migration path between application IDs**. The private data directory is keyed
on the identifier; a new one starts empty. Measured on the test device, the data at stake is
**2.9 GB of installed rootfs per device**.

The cost of this change is therefore (devices in the field) × (a 2.9 GB reinstall). The installed
base is currently **fewer than ten devices**. Every month of deployment makes it more expensive and,
past some fleet size, effectively irreversible. Doing it while the base is negligible is the entire
argument for doing it now.

## 4. Decision — the name is `org.appdevforall.k2go`

Reverse-DNS of a domain the organisation controls, organisation first, product last.

## 5. Decision — the rename covers the Java namespace too

If the motive were cosmetic, changing only the `applicationId` would do, and far more cheaply: the
Java package is invisible to users. Because the motive is **identity**, the source must stop claiming
the namespace as well.

## 6. Non-goal — this is a rename, not a de-IIAB-ing

Changing who publishes the app says nothing about what the app runs. It installs and runs
Internet-in-a-Box, and saying so is simply accurate. A rename is an easy thing to over-extend, so
the boundary is written down:

**Explicitly out of scope, and to be left alone:**

- user-facing strings that name IIAB — they describe what is actually running;
- the "powered by IIAB" attribution;
- the rootfs, which *is* Internet-in-a-Box;
- the copyright headers in the source (see below).

The repository ships under GPL v2 and its notices stay as they are. Removing credit would make the
product's own documentation and UI less accurate, which is reason enough on its own.

## 7. Recon — what actually carries the name

Surveyed across `controller/`, `static/`, `tools/`, `archive/` and a device's installed rootfs.

### 7.1 `controller/` — 545 files

| kind | count | nature |
|---|---|---|
| `package` / `import` lines | 1126 | namespace; mechanical IDE refactor |
| fully-qualified names in code | 511 | same |
| string literals | 43 | 42 intent actions + 1 test fixture |
| XML | 5 | 3 layouts (custom View FQNs), main + debug manifests |
| docs | 6 | prose |

**The 42 intent-action literals keep working unchanged.** They are unique-by-convention strings
(`"org.iiab.controller.WATCHDOG_START"` and similar), not identity. They are renamed for coherence,
not correctness — which means they can move in a follow-up without breaking anything.

The one bare `"org.iiab.controller"` literal is a **negative test fixture**
(`RsyncProcessMatcherTest:36`), asserting that an arbitrary string is *not* our rsync process.

### 7.2 What needs deliberate handling

- **`google-services.json` pins `package_name`.** A new Firebase app entry is required; analytics
  history splits at the changeover.
- **`AndroidManifest.xml:4` carries the deprecated `package=` attribute** — delete it rather than
  update it; AGP takes the namespace from Gradle.
- `lint-baseline.xml` and `release/output-metadata.json` are generated — regenerate, do not hand-edit.

### 7.3 What is already safe (verified, not assumed)

- `AndroidManifest.xml:51` — `android:authorities="${applicationId}.provider"`, parameterised.
- **No custom permissions, no deep-link schemes, `allowBackup="false"`.** Nothing external names us.
- The self-updater reads `getPackageName()` throughout.
- The process matchers key on paths derived from `getFilesDir()` and on a command tail, never on the
  package literal.
- proot's binds and the generated `iiab` CLI derive every path from `getFilesDir()` at launch.

### 7.4 `static/` — zero references

The guest-side assets (`dashboard`, `pdsm`, `site`, `zim`, `pdfjs`) do not know the host package.

### 7.5 `tools/` — one file, and it is a document

Only `ROOTFS_SIZE_PILOT_ANALYSIS.md`. **`tools/proot-builder/build_static.sh` references
`data/data/com.termux/...` — that is the Termux build container, not this app.** It must not be
touched; changing it would break the native-binary build.

### 7.6 `archive/` — three legacy files

`termux-setup` scripts, not executed by the app. They ride along inside the rootfs mirror (§7.7) and
refresh on the next bake.

### 7.7 The installed rootfs — the risk that does not exist

**Zero host paths are baked into the guest.** A grep for `/data/data/org.iiab` and
`/data/user/0/org.iiab` across the guest's `etc`, `usr`, `opt`, `root` and `library` returns nothing
outside the source mirror.

The rootfs does carry a **full copy of the repository at `/opt/iiab-android/`** (529 matches), but
that is source, not runtime configuration. It goes stale at the rename and refreshes on the next
rootfs bake; nothing reads the old identifier from it at runtime.

This was the finding that could have made the rename infeasible. It does not.

## 8. Migration — a clean cut, not a bridge

There is no in-place upgrade across identifiers. The fleet reinstalls.

**Rejected: an export/import bridge.** Backups live in `getFilesDir()/rootfs/backups`, which is
private, and the import path `CLAUDE.md`'s design map describes
(`DeployFragment.importBackupSafely`) **no longer exists in the tree** — that section is stale. The
bridge would have to be built from scratch and would move 2.9 GB through shared storage requiring
double the free space, for fewer than ten devices.

## 9. Consequences, stated plainly

- **The changeover release cannot ship through the in-app updater.** Across identifiers Android
  treats the download as a new app, not an update. That release is installed out of band, with a
  human at each device.
- **Both apps can coexist.** Installing the new one without removing the old leaves the old holding
  2.9 GB. The runbook is *uninstall first*, and it must say why.
- **Device-to-device clone sharing across the boundary installs a second app** on the receiver.
  Worth a note until the fleet is uniform.
- After the changeover everything returns to normal: OTA, cloning and backups all work within the
  new identifier.

## 10. Verification matrix

Two halves, and the second is the one that matters: **the rename must change identity and nothing
else.** Run on a device with the new build unless noted.

### 10.1 The change did happen

| # | Check | How | Expected |
|---|---|---|---|
| 1 | Install identity | `adb shell pm path org.appdevforall.k2go` | resolves |
| 2 | Old identity gone | `adb shell pm path org.iiab.controller` | not installed |
| 3 | Private dir | `adb shell run-as org.appdevforall.k2go ls` | app dirs listed |
| 4 | No stale identifier in the tree | `grep -rn "org\.iiab\.controller" controller/ --exclude-dir=build` | only deliberate attribution/history |
| 5 | Deprecated attribute gone | `grep -n 'package=' controller/app/src/main/AndroidManifest.xml` | no match |
| 6 | Firebase | `grep package_name controller/app/google-services.json` | new identifier, entry exists in console |

### 10.2 Nothing else changed — functional semantics preserved

| # | Area | Check | Expected | Why it could break |
|---|---|---|---|---|
| 7 | Fresh install | Install, complete a rootfs install end to end | reaches services, HTTP 200 on `/k2go-api` | the whole point |
| 8 | Environment boot | Cold start | UP, 0 kills (ADFA-5365 behaviour intact) | proot binds derive from `getFilesDir()` |
| 9 | proot capability | On an affected device, first launch | one ADFA-5362 learning line, then `PROOT_NO_SECCOMP=1` | the verdict is keyed on `versionCode`, so it re-learns — expected, not a regression |
| 10 | FileProvider | Trigger an in-app update install | the package installer opens the APK | authority is `${applicationId}.provider`; sharpest test of the rename |
| 11 | OTA updater | new-identifier → new-identifier update | updates in place | signer comparison uses `getPackageName()` |
| 12 | Terminal | Open the terminal, run `iiab` | CLI works, paths point at the new private dir | script is generated from `getFilesDir()` |
| 13 | Intent actions | Start/stop the server; run a module install; a download | services respond | the 42 literals are unchanged strings — this proves they are inert |
| 14 | Notifications | Any foreground service | notification shows | channels are per-app; new app = new channels |
| 15 | Device-to-device | Clone/share between two new-identifier devices | pairing and transfer work | `ApkShareName`, rsync matcher |
| 16 | Backup / restore | Create a backup, restore it | round-trips | paths derive from `getFilesDir()` |
| 17 | Dashboard rebuild | Trigger a rebuild | completes | runs guest-side; must be untouched |
| 18 | Layouts with custom Views | Open portal, module detail, maps landing | render | the 3 XMLs carry FQNs that follow the namespace |
| 19 | Unit tests + lint | `./gradlew :app:testDebugUnitTest :app:lintDebug` | green, baseline regenerated | |
| 20 | Native binaries | Confirm `tools/proot-builder` untouched | `com.termux` paths still present | changing them breaks the build |

### 10.3 Explicitly out of scope for this matrix

Analytics continuity (history splits by design, §7.2) and the copyright-header question (§6).

## 11. Sub-phasing

1. **The rename** — `applicationId`, `namespace`, the manifest's deprecated `package=`,
   `google-services.json`, regenerated baselines. Atomic by nature; it does not decompose.
2. **Docs and runbooks** — every `run-as org.iiab.controller` line, plus the changeover runbook
   from §9.
3. **Intent-action literals** — cosmetic, safe to defer, proven inert by check 13.
