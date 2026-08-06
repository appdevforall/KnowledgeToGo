# Catalog browse — reusable content-type template (conventions)

Status: living reference · Epic ADFA-1028 · first implemented in ADFA-5033 (ZIM/Wikipedia).

## Why this document

The "Get more content" catalog for **Wikipedia & ZIM** is not a one-off screen — it is the
**template** for every content type that ships a large, browsable catalog: **Kolibri next, then Books
and others**. We are investing in the details now so that when a new wave of content arrives, the visual
and interaction language is already mature and agreed. New content types will hit new problems, but they
must **reuse this structure** rather than reinvent it — so the app stays integral and standardized.

Treat this as the checklist to respect when copying the pattern to a new content type. The design boards
(`k2go-catalog-browse-spec`, `k2go-language-control-v1`, `k2go-count-pill-v1`, `k2go-count-overflow-v1`,
`k2go-sort-vs-group-v1`, `k2go-catalog-list-options-v1`) are the source of truth for pixels; this file is
the source of truth for the **agreements**.

## Reference implementation (copy these)

- Category index: `redesign/ZimLandingFragment.java` + `layout/fragment_k2go_zim_landing.xml`
- Item list (inside a category): `redesign/ZimCategoryFragment.java` + `layout/fragment_k2go_zim_category.xml`
- Static theme grouping: `redesign/KiwixGroups.java` (category → group map + chip/header labels)

A new content type (Kolibri, Books) is a **new instance of the same shape**: provide its taxonomy, its
counts, and its own `*Groups` map — do not redesign the screen.

## The agreements

### Header — compact, one control per row
- **Language selector = one whole-row control.** Two lines: `Language: <value>` (value bold) + the
  **source** in Title-Case, no middot — `From System` (default) / `Manually Selected`. Trailing caret
  (`arrow_drop_down`/`ic_expand_more`); **the whole box opens the picker — never an inner "Change"
  button** (that is the button-in-button we removed). Same control in the index and in the detail.
- **No redundant language pill.** The language lives only in the selector. Do not repeat it next to the
  title.
- **Storage = one line + thin bar**: `used · free · selection N`. Context, not a hero.

### Category index — show less, then search / see-all
- **Default:** a `MOST CONTENT` section with the **top ~6 categories** by count, then a
  `See all N categories ›` row. Everything else is one tap (See all) or a search away.
- **Flat, light rows** — no per-row card. 24dp leading icon, name + one-line subtitle, **right-aligned
  count column** (teal, redundant with the number) + chevron, a **hairline between rows**. Air comes from
  alignment and whitespace, not chrome. Row height ≥ 56dp.
- **See all = grouped by theme** with section headers (teal caps), rows sorted by count within a group.
  Grouping is a **static category→group map** shipped with the app (`KiwixGroups`); same idea for Kolibri.
- **Unavailable collapses out of the flow.** Categories with 0 items in the current language are **not**
  greyed rows. Default: one muted line `N categories aren't available in <language> · Change language`.
  See-all: a single collapsed row `Not available in <language>  N ›`. Never show an empty section header.

### Filter chips — one line, never wrap
- **One horizontally-scrollable row** (`HorizontalScrollView`), never wraps to a 2nd line (keeps the
  header light on small screens / long locales like es, de). `All` + one chip per group; filters
  client-side; `All` resets.
- **Tap target ≥ 48dp** (even if the pill looks smaller).

### Item list (inside a category) — a selectable list with a budget
- **Flat rows, not cards.** Whole-row tap toggles selection; leading checkbox shows state; name +
  **right-aligned size** in a tabular column; hairline between rows. The **only** rounded/filled shape is
  the **selected** row's highlight (teal ~20%, live on tap) — no ovals on every row.
- **Sort vs. group is one axis at a time** (mutually exclusive toggles): `By size` / `A–Z` are **flat**
  (one item per row, no collapse); `Grouped` is **by subject** (language-dependent) and is the only place
  variant-collapse lives (editions/sizes as chips).
- **Storage guard**: disable/grey items that don't fit and say why (`Not enough space — needs X, Y free`).
- **Count = list metadata, on its own line.** Right-aligned above the list, **not** at the end of the
  scrollable chip row (it would wrap in es/de — see `k2go-count-overflow-v1`). Live: `N items` →
  `N results` while searching. No language in it.

### Forward action
- **One fixed bottom bar** — `Review selection · <size>` (index) / `Add to selection · <size>` (detail),
  pinned, never scrolls off; content scrolls under it.

### Cross-cutting
- **Icons:** Material Symbols **Outlined, weight 400** — same family as the nav.
- **Colours from theme/tokens** (`colors_k2go.xml`); count colour is redundant with the number, never
  colour-only.
- **Accessibility & offline:** tap targets ≥ 48dp; high contrast; everything resolves offline; respect
  Reduce Motion.
- **l10n per PR:** new user-facing strings are localized in the PR that adds them (or kept
  `translatable="false"` only while wording is under design review, then flipped + translated at sign-off).

## When copying to a new content type

1. Reuse the two layouts + the two fragments as the shape; swap the data source (its catalog/counts).
2. Author a `*Groups` map (category → theme group, with chip + header labels).
3. Keep every agreement above. If a new content type needs something new, extend the pattern **here**
   (update this doc) so the next type inherits it — do not fork a divergent design.
