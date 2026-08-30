# Material 3 styles — K2Go UI

Living reference for the shared Material 3 styling of the redesigned (K2Go) UI, and the decisions
behind it. Update this doc when a shared style or token is added or changed.

The K2Go theme (`res/values/themes_k2go.xml`, `Theme.K2Go`) is a **scoped** Material 3 theme
(`Theme.Material3.DayNight.NoActionBar`), applied per-Activity for the redesigned screens. It sets the
color roles, the Atkinson Hyperlegible font, the type scale, and — as of ADFA-5346 — the button styles.

## Buttons (ADFA-5346)

### Decision
- **One shape for every button: the official Material 3 fully-rounded (stadium / pill).** Primary,
  outlined, and text buttons share it, so a screen never mixes a pill and a square button.
- **Styles live in the theme, not per-button drawables.** Before ADFA-5346 buttons were shaped ad-hoc by
  custom shape drawables (`k2go_primary_bg`, `k2go_getmore_bg`, …) at 16dp corners, or fell through to
  the M3 default (pill). That produced a pill "Back" next to a square "Run in background" on one screen.
- **Colors from semantic tokens** (`k2go_teal` container / outline, `k2go_on_teal` label, `k2go_clay` for
  destructive), day + night.

### The shared styles (`themes_k2go.xml`)
- `ShapeAppearance.K2Go.Button` — parent `ShapeAppearance.Material3.Corner.Full`. The single source of the
  corner; change it here to restyle every button at once.
- `Widget.K2Go.Button` — parent `Widget.Material3.Button`. Filled primary (teal). **This is the app-wide
  default**: `Theme.K2Go` sets `<item name="materialButtonStyle">@style/Widget.K2Go.Button</item>`, so a
  bare `<Button>` in a K2Go activity inflates as a MaterialButton with this style — no per-button tint
  needed.
- `Widget.K2Go.Button.Outlined` — parent `Widget.Material3.Button.OutlinedButton`. Outlined secondary
  (teal outline + text, 2dp stroke).
- `Widget.K2Go.Button.Text` — parent `Widget.Material3.Button.TextButton`. Low-emphasis text action.
- `Widget.K2Go.Button.Destructive` — outlined in `k2go_clay` (error), e.g. "Turn off".

### How to use
- Primary (filled): just use `<Button …>` — no `style`, no `backgroundTint`, no `background`. The theme
  default makes it the teal pill.
- Secondary (outlined): `style="@style/Widget.K2Go.Button.Outlined"`.
- Text-only: `style="@style/Widget.K2Go.Button.Text"`.
- Destructive: `style="@style/Widget.K2Go.Button.Destructive"`.
- **Do NOT** set `android:background`, `android:backgroundTint`, or a custom shape drawable on a button —
  it fights MaterialButton's shape/tint. Let the style own the look. Keep only `layout_width`,
  `layout_margin*`, `android:text`, `android:id`, `android:gravity`, `android:visibility`.

### Size — also in the style (ADFA-5346)
The style owns size too: `minHeight 52dp`, zero insets, and one `TextAppearance.K2Go.Button` (16sp bold),
so every button reads and measures the same regardless of screen. **Do NOT** set `android:padding`,
`android:layout_height` (use `wrap_content`), `android:textSize`, `android:textStyle`, or
`android:textAppearance` on a button — those per-button overrides are exactly what made a tall Step-1
"Next" sit next to a shorter Step-2 "Continue". Tune the height/text once in the style if the whole app
needs to change. Complements ADFA-5019 (size consistency) — now enforced structurally.

### Code-built or role-switching buttons — use a ThemeOverlay, not a Java recipe
For a button created in code, or one that morphs between roles at runtime, do NOT reproduce the role look
in Java (`setBackgroundTintList` / `setStrokeColor` / `setTextColor` per role) — that duplicates the styles
and drifts. Point the button at a style via a ThemeOverlay instead:

```java
MaterialButton b = new MaterialButton(
        new ContextThemeWrapper(ctx, R.style.ThemeOverlay_K2Go_Button_Destructive), null);
```

`ThemeOverlay.K2Go.Button.Filled | Outlined | Destructive` only set `materialButtonStyle` to the matching
style, so the whole look (shape, size, colors) comes from the one style — nothing is spelled in Java. See
`SettingsFragment`'s "Turn off" and the DNS/auth `accept`/`save` for the code-built case.

**Two ways to morph, by how the click is wired (both keep the look in the styles):**
- *Rebuild per role* — when a button changes its whole role (filled ↔ outlined ↔ destructive) AND its
  click per state, rebuild it with the overlay for the current role. `CloneFragment.setStopRole(...)`
  swaps the single footer child and re-applies text + click each time (it re-sets the id so it stays
  stable across rebuilds).
- *Toggle emphasis in place* — when a button keeps its shape/size and only shifts emphasis (filled ↔
  teal-text) AND its click is set once / it sits among siblings, keep the same button and change only the
  fill + label via `K2GoButtons.setFilledEmphasis(button, filled)` (one shared helper, so the two-line
  recipe isn't copied across fragments). The Clone/Connect "advance" CTAs use this.

### Not every "button" is a full-width CTA — the taxonomy (why some are left alone)
The shared styles above are for **actions the user commits to**: the full-width (or near-full-width)
primary/secondary/destructive buttons — Continue, Next, Get more, Turn off, Save, "Installed? copy the
library". Those all take the one pill shape + 52dp size, on purpose.

Several tappable, drawable-styled elements are **deliberately NOT** those, and applying the 52dp CTA style
would make them wrong. They are different Material 3 components and belong to a separate, structured pass:

- **Segmented tabs / pills** — Hotspot | Wi-Fi and Send | Receive (`paintTab` in Clone/Connect, and the
  chip rows in Books / LibraryHome). These are a *selected-state* control, not a button; the correct M3
  component is a **segmented button / tab**, sized to the row, with a selected fill. A 52dp pill each would
  break the segmented look.
- **Compact / inline actions** — e.g. the `retry` chip inside a `ProvisioningChecklist` row. It sits beside
  text at row height; the 52dp CTA size would tower over the row. This wants a **compact/text button** or a
  small chip, not the CTA style.
- **Selectable rows / list items** — the language selector box (`lang_box`) and version rows
  (`WikiVersionPicker`). These are **list items with a selection background**, not buttons; they take a
  container/outline drawable, not a button style.
- **Toggles** — on/off state uses `MaterialSwitch`, not a button.

Rule of thumb: if it's a *commit-to-this* action the user reads and presses, it's a button → shared style.
If it *selects among options*, *toggles*, or is a *compact inline affordance in a row*, it is a different
component and stays out of the CTA styles until its own M3 treatment is chosen. Documented here so a later
analysis can pick the right component per case rather than forcing everything into one button.

### Retired / to retire
- `k2go_getmore_bg`, `k2go_primary_bg`, `k2go_turnoff_bg` — button shape drawables, replaced by the styles.
  Delete once no layout references them.
- `k2go_ok_bg` is NOT a button — it's a filled notice/banner background ("Fits: X of Y free"). Leave it.

### Done so far
- Real `<Button>` elements that used the shape drawables → migrated to the styles.
- **Static** "TextView-as-button" (clickable `TextView`s styled as buttons) → converted to `<Button>`
  (MaterialButton) with the styles, in `activity_k2go_wizard` (setup_download, setup_copy, wiz_primary),
  `fragment_k2go_connect` (conn_finish), `fragment_k2go_setup_step1` (step1_next), `fragment_k2go_backup_job`
  (bj_finish), `fragment_k2go_library` (get_more). Their Java fields are typed `TextView`, and MaterialButton
  is-a TextView, so no Java change was needed (they only call setVisibility).
- Size normalized: per-button `padding` / `textAppearance` / `height` were stripped from the converted
  buttons so they all take the style's size (52dp / 16sp). This is what fixes the Step-1-vs-Step-2 size
  mismatch on the K2Go screens.
- **"Turn off K2Go"** (`SettingsFragment`) — the code-built destructive button now uses the
  `ThemeOverlay.K2Go.Button.Destructive` overlay (no drawable, no Java color recipe).
- **`CloneFragment`** — the 8 static clone buttons converted to `<Button>` with the styles; the morphing
  footer action (`stop`) is now a `MaterialButton` rebuilt per role via `setStopRole(...)` + the overlays,
  so the recover/share-anyway/stop/start looks live only in the styles.

### Known follow-up (drawables not retired yet)
The three shape drawables (`k2go_primary_bg`, `k2go_getmore_bg`, `k2go_turnoff_bg`) can't be deleted yet —
these still reference them and need a device-verified pass:
- **Programmatic uses in other Java** — `BooksLandingFragment`, `LibraryHomeFragment`,
  `ProvisioningChecklist`, `ConnectFragment`, `SettingsSubFragment`, `WikiVersionPicker` set these
  drawables in code (and `CloneFragment`'s tab/`advance` selected-state fill toggles `k2go_primary_bg` on/
  off — a selection indicator, not a button; convert or give it its own selector drawable).
- **Container backgrounds** — the `lang_box` selector rows (`fragment_k2go_kolibri_browse`,
  `fragment_k2go_zim_category`, `fragment_k2go_zim_landing`) use `k2go_getmore_bg` as a `LinearLayout`
  background. Those are NOT buttons; give them their own outline drawable so the button drawable can retire.

Retire the three drawables once all of the above are migrated. (`k2go_ok_bg` is a notice/banner, unrelated.)

### Legacy screens on a non-Material 3 theme (can't adopt the styles yet)
Some older screens are hosted by activities without `Theme.K2Go` — e.g. `SetupActivity` (hosting
`SetupSectionFragment`) inherits `Theme.IIABController` (parent `Theme.AppCompat`, **not** Material 3).
Its `btn_setup_continue` uses the legacy `@drawable/rounded_button` (12dp, 60dp, 18sp), which is why the
Step-2 "Continue" doesn't match the Step-1 "Next" (a K2Go/M3 screen). The M3 button styles can't be
applied there without first moving the activity to `Theme.K2Go`. This is a separate migration (verify the
whole screen renders under M3), not a shared-style tweak.

## Color tokens
Semantic color tokens live in `res/values/colors_k2go.xml` (+ `values-night`). Reference by role
(`k2go_teal`, `k2go_on_teal`, `k2go_ink`, `k2go_muted`, `k2go_clay`, `k2go_leaf`, …), never a raw hex.
See the theming rules in the project guardrails (CLAUDE.md).

## Typography
Type scale in `themes_k2go.xml` (`TextAppearance.K2Go.*`, parented on `TextAppearance.Material3.*`), all
using Atkinson Hyperlegible. Use `setTextAppearance(TextAppearance_Material3_*)` / the theme's type-scale
attrs, not numeric `setTextSize(sp)` (guardrails).

## Reference precedent
`redesign/BackupRestoreFragment.java` and `redesign/FqrController.java` (map-overlay M3 standardization,
ADFA-5027) — code-built Material 3 UI done right (themed `ContextThemeWrapper`, `MaterialButton(ctx, null,
style)`, type scale, 4dp grid).
