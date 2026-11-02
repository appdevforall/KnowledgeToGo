# ADR: K2Go UI type scale, shared kit, and a build-time guard

Status: Proposed (2026-11-01). Tracked by K2GO-529.

## Context

The app has no shared UI kit. Each screen builds its own text styles, header, and rows.
The Settings area alone expressed the same facts in about five places: how a role maps to a
size, which style carries the app font (Atkinson Hyperlegible), and how the header is built.
Those five owners are:

- `redesign/SettingsUi`
- `redesign/SettingsSubFragment` (`dnsText` plus `k2goScale`)
- `redesign/ModuleHubFragment` (card text helpers)
- `contentexchange/presentation/ContentManagerAdapter` (`text`)
- `redesign/BackupRestoreFragment` (`heading`, `card`)

Five owners of one truth drift apart. A 2026-10-31 device audit (Language to Advanced) found:
the back affordance spelled four ways; the screen title built seven ways in four sizes; the app
font missing wherever code used a raw Material3 style or a non-remapped theme attribute; padding
14dp off the 4dp grid; and the 12sp scale used for text a reader must strain to read. This is the
same accumulation failure mode ADR-5343 describes: many small additions, no single owner.

## Decision

### 1. One type scale, four roles

The roles are tokens. They live once in `res/values/themes_k2go.xml`:

- 28 Display : screen title (pinned header, one per screen).
- 16 Body : screen intro, row or item title, the "what it is" context line.
- 14 Label : supporting detail, values, hints, section group-label (teal, caps).
- 12 Caption : RESERVED for one terse per-control helper only.

The app font reaches these roles only through the remapped theme attributes
(`HeadlineMedium`, `TitleLarge`, `BodyLarge`, `LabelLarge`, `BodySmall`) or the
`TextAppearance.K2Go.*` styles. `TitleMedium` and `BodyMedium` are NOT remapped and render in the
stock font, so they must not be used in app UI.

### 2. One shared text kit (`K2GoText`), the only sanctioned consumer of the scale from code

`K2GoText` (package `org.appdevforall.k2go.ui`) is the single owner of the scale for code-built UI.
It exposes the five scale steps as factory methods that take a colour token: `display`,
`title(..., bold)`, `body`, `label`, `caption`; plus `sectionLabel` (the Label step in teal, all-caps
and tracked); plus an `apply(TextView, styleRes, colorRes, bold)` primitive for a view that already
exists (an XML-inflated view, an adapter row). Every method applies the appearance, then the colour,
then the weight, in that order, because `setTextAppearance` resets colour and weight. A call site
asks for a scale step plus a colour token, never for a literal size or a raw Material3 style. This
replaces the per-screen text builders (`SettingsUi.title/sub`, `ModuleHubFragment.cardName/...`,
`BackupRestoreFragment.heading/card`, `SettingsSubFragment.dnsText/k2goScale`), which each re-stated
the scale-to-style mapping.

Adapter and binding code that styles an empty view and sets the text later may instead resolve a
remapped theme attribute (`?attr/textAppearanceBodyLarge` and the others that `themes_k2go.xml` maps
to the `TextAppearance.K2Go.*` styles). That path reads the same single source (the theme), so it is
equally sanctioned; `ContentManagerAdapter` uses it.

Deferred (not blocking): renaming the row builder `SettingsUi` to a host-neutral `K2GoList`, and
extracting the pinned header (back glyph plus title), today repeated per screen, into one scaffold so
a screen keeps its header when it moves (for example if Content manager leaves Settings for a more
reachable place).

### 3. A build-time guard is the forcing function

A Gradle check fails the build when app UI reaches for an escape hatch instead of the kit or the
tokens:

- a raw `TextAppearance_Material3_*` style,
- a non-remapped attribute (`?attr/textAppearanceTitleMedium` or `...BodyMedium`),
- a literal text size (`android:textSize`, or `setTextSize` with a numeric `sp`),
- a hardcoded colour in UI (a `#RRGGBB` in a layout, or `Color.parseColor` / `Color.WHITE` in
  code). The CLAUDE.md theming rule already forbids this, but nothing enforced it.

The guard runs with a BASELINE: it records the current violations once and fails only on NEW ones.
This is the strangler-fig path. New code is forced through the kit. Legacy is grandfathered and
migrated boy-scout, which shrinks the baseline over time. Precedent: the `verifyManifestPrivacy`
CI guard already fails the build on a privacy-policy regression.

### 4. This ADR is the contract the guard cites

The guard's error message points here and names the role to use. So a new screen almost cannot be
built without learning the scale first. That is the point: make the clean path the only path.

## Consequences

- A new screen composes header plus roles plus rows. It does not invent typography.
- The four roles plus the guard remove the drift mode the audit found.
- Rollout is incremental, each step reviewable on its own:
  1. This ADR (the contract).
  2. The guard with a baseline (the teeth).
  3. The kit consolidation that migrates the five Settings-area call sites and shrinks the baseline.
- Open question: whether Content manager moves out of Settings is undecided. The host-agnostic
  scaffold covers either outcome.

## References

- 2026-10-31 Settings UI coherence audit and the v2 prototype (type scale plus pinned headers),
  already applied on the `spike/settings-ui-standard` branch and device-verified.
- ADR-5343 (server-lifecycle): the "add a state per screen, no single owner" failure mode this
  guard prevents for UI.
- `verifyManifestPrivacy`: the precedent for a build-time guard that fails on a policy regression.
