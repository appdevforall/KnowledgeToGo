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
  it fights MaterialButton's shape/tint. Let the style own the look. Keep only layout attrs
  (width/height/margins), `android:text`, `android:id`, `android:visibility`.
- Height stays explicit where the app wants a chunkier control (52dp is common; button *size* consistency
  is ADFA-5019, done).

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
  is-a TextView, so no Java change was needed (they only call setVisibility). Prominent CTAs keep their
  `textAppearance` (e.g. TitleLarge) so the conversion changes shape, not prominence.

### Known follow-up (drawables not retired yet)
The three shape drawables (`k2go_primary_bg`, `k2go_getmore_bg`, `k2go_turnoff_bg`) can't be deleted yet —
these still use them and need a device-verified pass:
- **`CloneFragment`** — its `stop` button (and the clone screen) swaps the background drawable + text color
  at runtime by state (`setBackgroundResource(...)`), which is exactly what you must NOT do on a
  MaterialButton. Converting it means reworking that stateful styling via `setBackgroundTintList` /
  `setStrokeColor` / `setStrokeWidth` / `setTextColor` (the M3 shape is unaffected).
- **Programmatic uses in other Java** — `BooksLandingFragment`, `LibraryHomeFragment`,
  `ProvisioningChecklist`, `ConnectFragment`, `SettingsSubFragment`, `WikiVersionPicker`, `SettingsFragment`
  set these drawables in code.
- **Container backgrounds** — the `lang_box` selector rows (`fragment_k2go_kolibri_browse`,
  `fragment_k2go_zim_category`, `fragment_k2go_zim_landing`) use `k2go_getmore_bg` as a `LinearLayout`
  background. Those are NOT buttons; give them their own outline drawable so the button drawable can retire.

Retire the three drawables once all of the above are migrated. (`k2go_ok_bg` is a notice/banner, unrelated.)

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
