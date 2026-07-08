# K2Go three-tier help — tiers 1 & 2 (ADFA-4593)

In-APK contextual help. Long-press a wired control → **tier 1** (short summary)
→ *See more* → **tier 2** (detail) → **tier 3** links (soft pointers to the
rootfs-served help system defined in ADFA-4594; they degrade gracefully when
that system is absent).

## Pieces
- `org.iiab.controller.help.TooltipManager` — reads a prebuilt read-only SQLite
  DB (`assets/help.db`, copied to files dir on first use), shows the popup.
- `TooltipItem` / `HelpLink` — value objects. `TooltipTag` / `TooltipCategory` —
  stable keys.
- `ViewTooltips.attachLongPress(view, category, tag)` — one-line wiring per control.
- `res/layout/tooltip_window.xml` — popup layout (WebView + See more + links).

## Wiring a control
```java
ViewTooltips.attachLongPress(myButton, TooltipCategory.K2GO, TooltipTag.MAIN_SETTINGS);
```
This scaffold wires the Main screen only. The remaining anchor points are listed
in `ADFA-4536-three-tier-help-analysis.md` (§8 inventory). Note: `fragment_usage`
wiring is intentionally deferred to avoid conflicts with the dead-code cleanup.

## Help content
Content lives in the DB, not in code. Edit `tools/help/tooltips.csv` and
`tools/help/links.csv`, then rebuild:
```
python3 tools/help/build_help_db.py \
    tools/help/tooltips.csv tools/help/links.csv \
    controller/app/src/main/assets/help.db
```
Schema (mirrors Code On the Go): `TooltipCategories`, `Tooltips`
(`categoryId, tag, summary, detail`), `TooltipButtons` (`tooltipId,
buttonNumberId, description, uri`). Lookup is case-insensitive on tag + category.

## Scope
Native UI only. Tier-3 serving is ADFA-4594. Estimated APK cost is small
(text-only DB + a layout; no new dependencies).
