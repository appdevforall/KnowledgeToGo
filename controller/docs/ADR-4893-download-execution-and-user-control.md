# ADR-4893 — Where a download runs, and who decides when it is going badly

Status: Proposed (ADFA-4893; hangs off Epic ADFA-1028). **Scope: the decision and its consequences,
no refactor.** The area tickets do the work: **ADFA-4895** (rootfs image), **ADFA-4894** (the REST
runners), **ADFA-4897** (the device's memory of a live run), **ADFA-4898** (maps), **ADFA-4896**
(FQR controls), **ADFA-4899** (Portal). `operation-model-roadmap.svg`, beside this file, tracks how
far each has got.

Written after reading all five download paths rather than from the ticket text, because the ticket
text said the paths were uniformly weak and they are not. Two of them are already where this
document wants everything to end up, and saying so changes what is left to do.

## Context

K2Go downloads very large things over links it does not control: a 2–4 GB rootfs image, ZIM files
in the tens to hundreds of GB, map layers in the tens of GB. A slow link, a network change
mid-transfer, or simply closing the app must not cost the user hours of data.

### The five paths, as they actually are

| What | Class | Tool | Resumes | Verifies | Mirrors |
|---|---|---|---|---|---|
| rootfs image | STOPPED, on the device | bundled `libaria2c.so` + `.meta4` | `.aria2` control file, per-piece | size + SHA-256 (`DownloadVerifier`), plus `--check-integrity` per piece | yes, from the Metalink |
| ZIM / Kiwix | LIVE, in-server | `/usr/bin/aria2c --follow-metalink=mem` | yes | `--check-integrity` | yes |
| Maps / FQR | LIVE, in-server | `tile-extract.py` under `sudo` | *unverified* — see below | *unverified* | no |
| Books / Gutenberg | LIVE, in-server | plain `fetch()` | no | no | no |
| Kolibri | LIVE, in-server | Kolibri's own `importcontent` | *unknown* | *unknown* | no |

**Where this table is a citation and where it is not.** The first two rows and the Books row were
read in this repository: `Aria2Manager.java` and `MetalinkFile.java` for the rootfs,
`sockets/kiwix.exec.ts` for ZIM, `sockets/books.exec.ts` for Books — five `fetch()` calls with no
`AbortController`, no timeout and no `Range` anywhere in the file.

The Maps row is **not** verified here. `maps.exec.ts` spawns `tile-extract.py` and parses its output;
whether a failed run resumes is a property of that script, which does not live in this repository.
"Restarts all three layers from zero" is ADFA-4894's claim, repeated here without confirmation.
Read the script before acting on it.

The Kolibri row is an **assumption**. `kolibri.exec.ts` spawns no binary and delegates to
`importcontent`; what that does on a failed transfer was not investigated.

The first two are the two largest transfers and they are already at the target: Metalink gives them
several servers, per-piece hashes, resume across a kill, and an integrity gate that runs before the
artifact is ever extracted or executed. **Nothing needs inventing for them.** The gap is entirely in
Books, Maps and Kolibri.

### The class distinction is the one we already have

These are not "two stages of downloading". They are the two **execution classes** ADR-5061 already
declared, and the download story falls exactly onto them:

- **STOPPED** — no rootfs yet, or the box is down. The app itself is the downloader, spawning the
  bundled binary. This is the rootfs image, and only this.
- **LIVE** — the rootfs exists and the services answer. The dashboard's job engine owns the work
  inside the container; the app only asks and observes.

In the LIVE class the work is owned by the dashboard inside the container, and `jobs.ts` gives it a
real substrate: SQLite, a `canceled` phase distinct from `error`, a `cancel()` that kills the
tracked children, and `reconcileOnBoot()` — all verified in the file.

**But the container is not independent of the app**, and an earlier draft of this document claimed
it was. The environment proot is a child of this app's process (`EnvironmentProcessMatcher`, and
`ServerController` reaches for `killall -9 proot`). So:

- The app **backgrounded**, or its Activity gone: the proot survives. Nothing kills it —
  ADFA-5103 documents that as a defect in the other direction.
- The app's **process killed**: its children go with it. Android 12's phantom-process killer does
  this deliberately, and `InstallService` already handles the resulting exit 137.

So a LIVE download survives being left alone and probably does not survive a process kill.
`reconcileOnBoot()` exists precisely because whoever wrote it expected interruption. **ADFA-4897 is
therefore not the small ticket a previous draft here said it was** — the device's memory of what it
asked for is one half, and the transfer's own survival is still the other. Which half dominates is
a device question, not a reading question: kill the app mid-ZIM and see whether aria2 is still
running.

In the STOPPED class the app *is* the downloader, so process death is unambiguously fatal, and that
is where Android's background-execution rules bite hardest.

### What is measured today and thrown away

Two figures already exist and neither survives its own function:

- `Aria2NetworkProfiler.runTimeBoxedTest` measures six seconds of real transfer, twice (IPv4 and
  default), in KB/s — and the result is used only to pick a flag, then discarded.
- `Aria2Manager` parses aria2's `DL:` field on every progress line and immediately concatenates a
  localized unit onto it, so what reaches the listener is a display string, not a number.

And a third, which is the one that matters most: **aria2 prints an `ETA:` on every progress line,
`Aria2Manager` parses it into the third argument of `onProgress(percentage, speed, eta)`, and all
three call sites in `InstallService` drop it on the floor** (`:330`, `:488`, `:655`).

ADFA-5118 (merged 12 Aug) built an estimate for the **write** side — verify and extract — with
`EtaSmoother` to stop the label flickering across a bucket boundary, a state that carries it
(`postVerifying(percent, message, eta)`), the layout, and the strings in all 34 locales. The
download leg has none of that, and it is the longest of the three and the one on the worst link.

**Reusing that machinery for the download is a proposal, not a wiring gap**, and the two signals
are not the same. The extract estimate is derived locally and wobbles because files decompress at
different speeds; a download estimate wobbles because the network does, which is noise of a
different kind and duration. The smoother's shape fits — it already keeps the last shown value
through a rate gap, which is exactly what a stalling transfer produces — but its five-second dwell
was tuned for the other signal and should not be inherited without a reason.

**We compute our own estimate rather than adopt aria2's.** aria2 prints one and we parse it, so
taking it would be free; it is rejected because this document makes the estimate a *decision input*
— the trigger for offering the user an action — and a black box is not a thing to decide on. With
Metalink across several mirrors, aria2's figure can jump when a mirror drops, and we would have no
way to tell that from the network degrading. Ours is `(total − completed) / rate`, from the figures
aria2 already prints on the same line, with the Metalink size as the authority for the total.

### What the industry does

Android now has an API for precisely this shape of work: **user-initiated data transfer** (UIDT),
API 34+, via `JobScheduler`, outside the standby-bucket job quotas. Google Maps reported a 10%
improvement in download reliability after adopting it, together with resumable downloads. There is
no Jetpack wrapper, so the documented approach is to gate on Android 14+ and fall back to a
WorkManager foreground service below it.

There is a deadline hiding in that: **from Android 16, long-running workers backed by foreground
services can exhaust the app's job quota.** `InstallService` is a foreground service running for
hours. This is not a theoretical concern for us.

The wider pattern across Play Store, Steam and torrent clients is consistent, and it is the
opposite of what a command-line downloader does: a slow transfer is never aborted. It is shown, and
the user is offered something — Steam offers a different download region, Play Store offers
"waiting for Wi-Fi" as a *state* rather than an error.

## Decision

### 1. Slowness is a condition, not an error

Errors are retried. Conditions are reported, and the user is offered an action. A transfer below
any speed we care to name is still a transfer, and on the links this product exists for, slow is
the normal case. Nothing in the app aborts a download for being slow.

Concretely: `--lowest-speed-limit` is **not** set on either aria2 invocation. It is the correct flag
for a script and the wrong one for a person.

### 2. Resume on an event, never on a timer

Retrying because a precondition changed — the network came back, the user joined a different
Wi-Fi — is correct and expected. Retrying on a clock against unchanged conditions is the loop that
burns a metered connection while achieving nothing.

This is a boundary, not a ban on all retries: aria2's own `--max-tries` / `--retry-wait`, bounded,
operate on a single connection inside one transfer and are kept. What is excluded is the
application relaunching a failed transfer by itself.

### 3. The trigger is estimated time, not speed

A rate is not actionable. "180 KiB/s" tells a user nothing; "at this rate this finishes in fourteen
hours" tells them everything, and it already folds in the size of what they asked for.

The estimate is read against the baseline the profiler measured at the start of this same transfer,
not against an absolute floor. Twenty KiB/s means nothing on its own; twenty KiB/s where we measured
three MiB/s ten minutes ago means the network changed under us, which is a different statement and
a different offer.

An absolute floor is kept only to avoid dividing by zero.

### 4. The user decides; the app offers

When the estimate crosses the threshold and stays there, the app offers — pause and switch network,
pause and continue later, carry on. It does not act. When a new validated network appears, a paused
transfer resumes by itself, because that is a changed precondition and the `.aria2` control file
makes resuming nearly free.

### 5. UIDT applies to the STOPPED class only

The LIVE downloads already survive the app; wrapping them in a device-side job would add a second
owner for work the dashboard already owns. The rootfs image download is the one that needs UIDT on
API 34+, a foreground-service fallback below it, and it is the one exposed to the Android 16 quota
change.

## Options considered

**A — Measure, advise, let the user act (chosen).** Matches how every consumer download product
behaves, keeps control where the person with the context is, and costs nothing to build because the
inputs are already computed.

**B — Abort and auto-retry on a speed floor.** One flag on aria2 and a loop in the app. Rejected:
on a genuinely slow link it turns a long download into an infinite sequence of short ones, and the
user is never told why.

**C — Do nothing; slowness is the user's problem.** The current behaviour. Rejected because the user
cannot even see the problem: the rate is a display string, there is no estimate, and the boot gate
gives them nothing to press.

## Consequences

- The boot gate stops needing to hold. A download that is pausable, has a visible estimate and
  survives being left alone is a background job with a surface, not a modal event — which dissolves
  state-spine findings 1 and 6 and half of 3 without touching any of them directly.
- Two downloaders reach the target by doing nothing, and it should be said out loud on the board so
  nobody "improves" them.
- Books is confirmed work and it is server-side, in ADFA-4894 — currently ranked below tickets that
  matter less. Maps probably belongs with it, pending someone reading `tile-extract.py`.
- Kolibri delegates to `importcontent` and we do not control its transfer. Whether we wrap it or
  accept it as opaque is an open decision with no ticket — and it cannot be taken before someone
  establishes what `importcontent` does on a failed transfer.
- A user who is offered "switch network" and does so must find the transfer where they left it.
  That requires the device-side memory of ADFA-4897 even in the STOPPED class.

## Action items

1. Carry aria2's `ETA:` through to the state, so the download leg shows the estimate that verify
   and extract already show. `postDownloading` gains the argument its caller already receives and
   discards; `EtaSmoother` and the display come from ADFA-5118 unchanged. Also stop destroying the
   numeric rate at `Aria2Manager:219`, and keep the profiler's baseline instead of discarding it
   after the IPv4 decision. **ADFA-4895.**
2. The control surface — pause, switch network, resume — and a `ConnectivityManager.NetworkCallback`
   that resumes on a new validated network. **ADFA-4895**, after 1.
3. Bring Books to a timeout, an abort wired to cancel, and a ranged resume; give Maps a per-layer
   checkpoint. **ADFA-4894.**
4. Device-side memory of a live run, and re-attachment on relaunch. **ADFA-4897**. Size it after the
   device test below, not before.
5. UIDT on API 34+ with a foreground-service fallback, for the rootfs download only. Not yet
   ticketed; carries the Android 16 quota exposure.
6. Decide Kolibri: wrap `importcontent` or accept it as opaque. Not yet ticketed.

### Open before any of this is acted on

Three claims above are not verified, and each one changes what its area ticket is worth:

- **Does a LIVE download survive the app's process being killed?** Kill the app mid-ZIM and check
  whether aria2 is still running inside the container. This sizes ADFA-4897 and nothing else can.
- **Does `tile-extract.py` resume?** It is not in this repository. This decides whether Maps is a
  checkpoint problem or already fine.
- **What does Kolibri's `importcontent` do on a failed transfer?** This decides whether there is
  anything to wrap.

None needs a large investigation. All three are cheap, and all three are load-bearing.

## References

- `controller/docs/ADR-5061-rest-vs-proot-operation-model.md` — the execution classes this reuses.
- `controller/docs/ADR-4832-live-content-channel.md` — the three aria2 flag sets that must stay
  aligned.
- `controller/docs/state-spine.svg` — findings 1, 3 and 6.
- `controller/docs/operation-model-roadmap.svg` — where each area ticket has got to.
- [User-initiated data transfer](https://developer.android.com/develop/background-work/background-tasks/uidt)
- [Google Maps improved download reliability by 10% using the UIDT API](https://android-developers.googleblog.com/2024/09/google-maps-improved-download-reliability-user-initiated-data-transfer-api.html)
- [Changes to foreground services](https://developer.android.com/develop/background-work/services/fgs/changes)
- [Data transfer background task options](https://developer.android.com/about/versions/15/changes/datasync-migration)
