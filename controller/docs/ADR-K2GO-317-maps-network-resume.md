# ADR-K2GO-317: Resume maps downloads after a network drop

## Status

Accepted (2026-11). Builds on ADR-4893 (download execution and user control)
and ADR-K2GO-394 (base-map downloads run through dash-node).

## Context

A Full Quality Region (FQR) download runs on the box through dash-node: the
`maps` job spawns `tile-extract.py`, which runs the custom go-pmtiles build
(`1.31.2-m16`) once per layer (vector, satellite, terrain). go-pmtiles supports
resumable downloads: on an interruption it keeps the partial file and a
`<output>.resume` journal, and a re-run of the same command continues from the
checkpoint.

Phone networks drop often (Wi-Fi, router, cell handoff). On a drop the download
restarted from 0 instead of resuming. This made large regions almost impossible
to finish on a flaky link.

Root cause, confirmed on device (OnePlus box, m16 build): on a mid-download
Wi-Fi cut, go-pmtiles behaves correctly. It prints a terminal JSON line
`{"phase":"error", ..., "exit_code":2}` ("network gave up"), keeps the partial
plus the `.resume` journal, and logs "run the same command again to resume".
But `tile-extract.py` raises on any non-zero layer exit, so the Python PROCESS
exits 1 (the real code 2 is only in the JSON line and in the stderr text). The
dash-node runner (`maps.exec.ts`) did not read the JSON `exit_code`. It saw a
non-engine error and ran `purgeRegion`, which DELETED the journal:

    [maps] pruned partial ...full-region.<name>.pmtiles.resume

So the checkpoint go-pmtiles saved was thrown away, and the next attempt started
from 0.

The responsibility split is clear:
* go-pmtiles: correct (reports `exit_code` 2, keeps the journal).
* tile-extract.py: masks the process exit to 1, but the real code is in the JSON
  line it forwards on stdout.
* maps.exec.ts: the defect. It never read `exit_code` and purged a resumable
  partial.

The box already has the full resumable state machine (dash-node, ADR-4893):
`pause` stops with SIGTERM and keeps the partial, `resume` relaunches the runner
(which continues from the on-disk checkpoint), and the `paused` phase is defined
as "stopped but resumable, partial kept". The device panel (K2GO-541) already
renders `paused` as a Pause/Resume control. None of that needed to change.

## Decision

Read the resumable signal from the JSON stream, where it already arrives, and
reuse the existing `paused` state instead of adding a new one. No rootfs rebake,
no new job phase, and no device change for the resume path.

In `maps.exec.ts`:

1. Capture the last JSON line's `exit_code` (it is present on each layer's final
   line).
2. On a stop that is NOT an engine pause or cancel, if `exit_code === 2`
   (resumable network drop), surface `PausedError` so the job becomes `paused`
   and the journal is kept. Any other non-engine exit stays a hard error and
   purges, as before.

A network drop is then, by the engine's own definition, exactly a `paused`
state: stopped, partial kept, resumable. The device poll already maps `paused`
to the Resume control, and `resume` relaunches the runner, which re-runs the
same command, and go-pmtiles continues from the journal.

## Retry and resume behavior (the state model)

This is the part that was undefined before. The panel's stopped states and their
actions:

* RESUME (from `paused`, which now includes a network drop): re-run the same
  command; go-pmtiles continues from the journal. It does NOT count as a
  failure, and it does NOT restart from 0.
* RETRY (from a hard `error`): the partial was purged, so this restarts from 0.
* A DIFFERENT rectangle: always a new region, from 0.

So "from the checkpoint" and "from 0" are distinct and user-visible. A network
drop takes the Resume (continue) path; only a genuine hard failure, or a new
rectangle, starts over.

## Cancel pauses at the cancel point

Cancel is destructive (it discards the partial), so the X first PAUSES the
download, then asks for confirmation. This gives the user time to decide while
the partial and journal are safe:

* Tap X: the download pauses at that point (partial and journal kept).
* Confirm Cancel: cancel from the paused point (purge).
* Dismiss the dialog: the download stays paused; the user can Resume.

This lives on the device (the confirm dialog orchestration), uses the same
pause/cancel endpoints, and also removes the small window where the old confirm
dialog could outlive a download that finished underneath it.

## Consequences

* A network drop resumes from the checkpoint. Large regions finish on a flaky
  link.
* One source of truth: a resumable stop is the existing `paused` state, not a
  new phase or flag. `paused` already means "stopped but resumable".
* The panel shows "Paused" for a network drop. That is honest (stopped,
  resumable) and is not shown as a failure. A network-specific wording is a
  possible later refinement (pass a reason in `detail`); it is not needed for
  correctness.
* Hard failures still purge and surface as `error` with the Retry (from 0) path.
* No rootfs rebake: the fix reads data the baked build already emits.

## Alternatives considered

* Change `tile-extract.py` to `sys.exit(returncode)` so the process exit carries
  2/3. Rejected: it needs a rootfs rebake, and the real code is already in the
  JSON line on stdout.
* Never purge on error; always offer Resume plus Cancel. Rejected: a genuine
  hard failure would loop on Resume forever until the user cancels, which is poor
  UX for a non-technical user.
* Add a new `stalled` phase for a network drop. Rejected: it duplicates the
  meaning of `paused` (stopped but resumable) and spreads one fact across two
  states.

## Evidence

Device test (OnePlus box, m16 build), mid-download Wi-Fi cut, terminal line:

    {"phase":"error","bytes_done":9412101,"bytes_total":74185937,
     "resumed_bytes":0,"retries":32,"exit_code":2}

go-pmtiles: "Failed to extract, network gave up after 5 tries" and "Download
stopped; run the same command again to resume from ...<name>.pmtiles.resume".
tile-extract.py then raised ("error code 2"), the process exited 1, and the old
maps.exec.ts pruned the partial and the `.resume` journal. With this change it
keeps them and surfaces `paused`.
