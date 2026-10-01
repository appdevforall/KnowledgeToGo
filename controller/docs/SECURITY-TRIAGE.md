# Security triage: dependency scanner findings

How we handle findings from the dependency scanner (Snyk Open Source / SCA). The
goal is a correct, proportional response, plus a record another person can audit
and re-check later.

## Core principle: triage by reachability, then respond

A finding is not automatically a code change. For each finding, ask one question:
is the vulnerable code reachable in something we ship (the APK) or run? The answer
selects one of three dispositions.

| Disposition | When | Action |
|-------------|------|--------|
| Fix | Reachable in the shipped APK or at runtime | Change the version or the code. |
| Accept | Not reachable: host-only build/test tooling, dev-only, or a dead path | Do NOT change code. Record why it does not apply. |
| Defer | Real, but the fix is disproportionate now (for example a major-version jump) | Record it as a scoped follow-up. |

Two mistakes to avoid:

- Changing everything. Fixing a non-applicable finding adds churn and risk, and can
  break tooling, for zero user-facing security gain.
- Silent ignores. A finding dropped with no record makes the next person re-triage
  from zero, and lets a real finding hide in the noise.

If the code is not shipped or run, the correct action is Accept, not a code change.

## What makes an "Accept" legitimate (not hiding)

An accepted finding is a documented decision, not a silent mute. It must have all
of these:

1. Narrow scope. Ignore the specific finding on the specific path, not a blanket
   mute of a whole library. If that library later appears on a shipped path, that
   must still alert.
2. A real reason. State the applicability argument, not "false positive". For
   example: "host-only test tooling, never in the APK".
3. An owner and an expiry. An ignore with no expiry is permanent blindness. The
   expiry forces a re-check.
4. A versioned, auditable home. The ignore lives in the repo (the `.snyk` file),
   not only in a web dashboard. Git then records who, when, and why, and CI reads
   the same policy.
5. A review trigger. Name the event that should make us re-check, not only a date.

## Where each piece lives

- The suppression itself: the `.snyk` file in the repo. A scanner reads it; a person
  reads it. It is both the machine-readable and the auditable record.
- The reasoning: this document (the framework), plus the internal Snyk report when
  one exists.
- The visibility line: one sentence on the Jira issue that tracks the scan, so the
  team sees the decision without reading the repo.

## Worked example: the UTP / netty findings (K2GO-358)

The scan reported about 99 netty and gRPC findings, including "critical" entries.
Every one is reached only through the Android Unified Test Platform (UTP): the
packages `com.android.tools.utp:*`, which the Android Gradle Plugin (AGP 8.4.1)
bundles. UTP runs on the build host during instrumented tests. It is never packaged
in the APK and never runs on a device.

Disposition: Accept. We changed no dependency. We suppressed the 49 unique ids in
`controller/.snyk`, each with the reason above and an expiry of 2027-09-30. The
review trigger is the next AGP or UTP upgrade, whichever comes first, because AGP
controls these versions.

Two ids (commons-io and kotlin-stdlib) also appeared on a shipped path. We did NOT
suppress those: we fixed them with a version bump in the shipped modules. This is
why the suppression is scoped to the ids that are tooling-only.

The real long-term fix for the bundled netty is an AGP upgrade. That is toolchain
maintenance, with its own testing, tracked separately, not as a security patch.

## Maintaining this

- A new scan routes every finding through Fix / Accept / Defer. Already-triaged
  findings are not re-litigated, because the `.snyk` entries and this doc explain
  them.
- On an AGP or UTP upgrade, re-run the scan and re-check the `.snyk` entries. Remove
  the ones the new toolchain fixed. Renew or update the rest.
- Keep `.snyk` at the directory where `snyk test` runs (here: `controller/`). Move it
  if the scan root changes.

## Pocket rule

If it is not in what we ship or run, the correct action is not a code change. It is
an Accept with scope, a reason, and an expiry, recorded where it can be audited and
re-checked.
