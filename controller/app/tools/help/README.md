# K2Go three-tier help — tiers 1 & 2 (ADFA-4593)

In-APK contextual help. Long-press a wired control → **tier 1** (summary) →
*See more* → **tier 2** (detail) → **tier 3** links (soft pointers to the
rootfs help system, ADFA-4594; they degrade gracefully when absent).

## Localization model (Option B: gettext .po → SQLite)
The database is a **build output**, never edited by hand. Translators work on
standard `.po` files (Crowdin/Transifex/Weblate/Poedit — no AI required).

Sources in `i18n/`:
- `source_en.csv` — English source content (`tag, field, text`). English is the msgid.
- `structure.csv` — skeleton (`category, tag, detail?, link uris`); uris are not translatable.
- `strings.pot` — template (regenerated from the English source).
- `<lang>.po` — one per language (`es, fr, hi, pt, ru`, …). msgctxt = `<tag>.<field>`.

Build pipeline (pure Python stdlib — no external packages; run by Gradle):
1. `extract_help_pot.py` — regenerate `strings.pot` from English (on demand only).
2. `validate_help.py` — fail if required languages are incomplete. Strict by default;
   `-PhelpAllowMissing=true` downgrades to a warning for local debugging (CI stays strict).
3. `build_help_db.py` — compile `i18n/` → `../../src/main/assets/help.db`
   (one DB, `lang` column). Missing strings fall back to English.

Required languages (build fails if incomplete): `en, es, fr, hi, pt, ru`.
Adding languages toward ~50 is "correction, not construction": drop in a new
`<lang>.po`, translate, then add it to the required set.

Gradle wires `validateHelpTranslations` + `buildHelpDb` into `preBuild` **only if
Python is on PATH**; otherwise the committed `help.db` is used and checks are skipped
(local builds never break). CI only needs Python (3.x); no pip packages required.

## Schema (mirrors Code On the Go, plus `lang`)
`TooltipCategories(category)` · `Tooltips(categoryId, tag, lang, summary, detail)` ·
`TooltipButtons(tooltipId, buttonNumberId, description, uri)`. Lookup is
case-insensitive on tag + category, filtered by `lang`, with English fallback at runtime.

## Wiring a control
```java
ViewTooltips.attachLongPress(myButton, TooltipCategory.K2GO, TooltipTag.MAIN_SETTINGS);
```
This scaffold wires the Main screen. `fragment_usage` wiring is deferred to avoid
conflicts with the dead-code cleanup. Remaining anchors: see the ADFA-4536 analysis (§8).

## App-chrome strings
`See more` / help-unavailable are genuine Android UI strings and live in `res/values*/`
(localized the normal way, TMS-ready) — separate from tooltip content.

Prereq: Python 3 on PATH (a tiny bundled `.po` parser, `pohelper.py`, avoids any pip dependency).
