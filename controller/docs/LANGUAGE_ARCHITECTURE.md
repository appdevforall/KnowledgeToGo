# Language architecture — how K2Go resolves languages

> Companion to `ARCHITECTURE.md`. This is the single reference for how a *language* flows
> through K2Go: how the app UI, the downloaded content (Kiwix), the help/dashboard WebView
> and any future interface agree on **which language a user wants**. Read this before adding a
> new language, a new translation, a new content source, or a new app/interface that has to
> speak the same language codes.

## 1. The problem this solves

The same language is named differently by each subsystem:

| Subsystem | Code form | Example (Indonesian / Russian / Chinese) |
|-----------|-----------|-------------------------------------------|
| App UI (AppCompat per-app locale, `values-*`) | BCP-47, region-qualified | `id` / `ru-RU` / `zh-CN` |
| Kiwix content catalog | base ISO-639 (modern) | `id` / `ru` / `zh` |
| Help / dashboard (`.po` → `help.db`) | base, legacy-flavoured | `in` / `ru` / `zh` |

Historically each consumer resolved the language on its own (strip region here, fall back to
`en` there, use the legacy `in` in one place and modern `id` in another). That drift is the
bug class this architecture removes. **There is now one place that owns the mapping, and every
consumer asks it instead of resolving codes itself.**

Guiding principle: **Kiwix is just one consumer.** The model is content-source-agnostic, so a
new app or a new content backend plugs in without touching the others.

## 2. Components

All of this lives in `app/src/main/java/org/iiab/controller/applang/`.

- **`domain/SupportedAppLanguages.java`** — the *canonical table*. One row per shipped language:
  `{ tag, endonym, English name }` (e.g. `{"ru-RU", "Русский", "Russian"}`). This is the single
  source of truth for the set of languages and their display names. It builds the picker lists
  (`forPicker`, `all`, `indexOfTag`).
- **`domain/AppLanguage.java`** — a pure value type: `tag`, `label` (endonym shown), `searchName`
  (extra text the search box matches, e.g. the English name). No Android, JVM-testable.
- **`data/LanguageResolver.java`** — the **resolver**. Turns a chosen tag into the code each
  consumer needs, with a deterministic fallback chain `specific region → base → en`:
  - `forUi(tag)` — BCP-47 tag for the app UI (`""`/null = follow the system).
  - `forKiwix(tag)` — base content code for the Kiwix catalog (`ru-RU → ru`, legacy `iw → he`).
  - `forHelp(tag)` — base code the `.po`/`help.db` files are keyed by (`id → in`).
  - `contentCode(appTag, contentTag)` — the effective content code; an empty `contentTag`
    means "same as app language" and follows the app tag.
  This is where per-language and per-consumer exceptions live. **New consumers add a `forX()`
  here; they never re-derive codes themselves.**
- **`data/ContentLanguage.java`** — low-level helpers used by the resolver: `normalize()` (legacy
  ISO-639 → modern: `iw→he`, `in→id`, `ji→yi`) and `systemDefault()` (device language, else `en`).
- **`data/AppLocaleController.java`** — thin wrapper over AppCompat per-app locales
  (`apply(tag)` recreates activities; `currentTag()` reads the current override).
- **`redesign/WizardLanguagePickerActivity.java`** — the reusable searchable picker. Driven by
  the canonical table; parametrised by `EXTRA_TITLE` and `EXTRA_PINNED` (the neutral top option)
  and returns a tag via `EXTRA_TAG`. Backs the wizard step and both Settings selectors.

## 3. Where the choice is stored

- **App UI language** — persisted by AppCompat itself (`autoStoreLocales`); read via
  `AppLocaleController.currentTag()`. No app pref.
- **Content language** — `content_lang_tag` in the internal SharedPreferences (`""` = "same as
  app language"). The *resolved* content code is written to `selected_lang_minimal`, which the
  installer (`PlannerController`, `InstallService`) reads as the Kiwix target. `selected_lang_minimal`
  is recomputed whenever either the app or content choice changes, so "same as app" tracks the UI.

## 4. How to extend — the natural flow

**Add a new UI language** (the app is translated into it):
1. Add a row to `SupportedAppLanguages.LANGUAGES` (`{tag, endonym, English name}`).
2. Add the matching `values-<qualifier>/` translations (keep the tag ⇆ folder mapping, e.g.
   `zh-CN → values-zh-rCN`, `id → values-in`).
3. Add a `<locale>` entry to `res/xml/locales_config.xml`.
4. Add the help `.po` for it under `controller/app/tools/help/i18n/` if help should be localized.
5. If the tag needs a non-derivable code for some consumer, add the exception in `LanguageResolver`.

**Add a new translation of existing strings** (no new language): run the l10n sweep — flip the
strings from `translatable="false"` in `values/strings_k2go.xml`, then add them to every
`values-*/strings.xml`. Because lint runs `MissingTranslation` as an **error**, a translatable
key must exist in *all* locales or the build breaks — add all locales in the same change.

**Add a new consumer / interface / app** (e.g. a second content backend, or another app that
must understand the user's language): add a `forX(tag)` method to `LanguageResolver` that returns
that consumer's code form, and route everything through it. Do **not** parse tags or fall back to
`en` inside the consumer — that is exactly the drift this design removes. The resolver is the
contract other apps/interfaces read so they all agree on "general vs specific" language.

**rootfs / help.db updates:** the help DB is keyed by `LanguageResolver.forHelp(tag)`; a rootfs or
help refresh only has to keep those base codes (with the `id → in` exception) in sync — the app
side does not change.

## 5. The one rule

Never resolve a language code ad hoc. If you find yourself writing `tag.split("-")[0]`, a
hardcoded `"en"` fallback, or a `switch` over language codes outside `applang/`, route it through
`LanguageResolver` instead (or add the case there). One table, one resolver, every consumer asks.

See also: ADFA-4537 (per-app UI picker), ADFA-4797 (wizard picker), ADFA-4798 (Settings app/content
split + this resolver).
