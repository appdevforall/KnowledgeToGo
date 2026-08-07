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
- **One content language for the whole wizard, inherited — never "all languages".** The selector opens
  on the language the wizard already settled on (`SetupLibraryActivity.getContentLang()`, backed by the
  `selected_lang_minimal` preference the install path reads), so the list arrives already narrowed to
  what the user reads. Changing it here changes it for every catalog; the pinned reset row is
  `Follow system language`, not an "all languages" catch-all — "all" is not a language, and a mixed-
  language list is the thing the selector exists to prevent. A content type whose catalog is empty in
  the current language says so on one tappable line that opens the picker; it does not fall back to
  showing everything.
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
- **The groups are ours, not the catalog's.** They are an *artificial* grouping we author — kitchen,
  living room, bedroom — so a long list can be crossed in big jumps: **five groups plus `All`**, never
  a long tail. They do not have to exist in the source data, and a content type whose catalog ships no
  taxonomy at all (Kolibri: Studio returns `categories: []`) groups its **items** instead. Aim for five
  so nothing has to fall into an "Other".
- **The language is never a chip.** It filters from the selector in the header; putting it in both
  places duplicates one axis and steals the row from the groups.
- **A row that would hold only `All` ships hidden**, not visible. While the groups are still being
  authored the chip row filters nothing, and a second line of pills competing with the sort toggles for
  the same weight is cost without a job — so the row is `gone` in the layout (one attribute to flip) while
  the code that fills it stays live. The language selector, the search field, the sorts and the storage
  bar all stay. What is never allowed is filling the row with some other axis to make it look populated.

### Item list (inside a category) — a selectable list with a budget
- **Flat rows, not cards.** Whole-row tap toggles selection; leading checkbox shows state; name +
  **right-aligned size** in a tabular column; hairline between rows. The **only** rounded/filled shape is
  the **selected** row's highlight (teal ~20%, live on tap) — no ovals on every row.
- **Sort vs. group is one axis at a time** (mutually exclusive toggles): `By size` / `A–Z` are **flat**
  (one item per row, no collapse); `Grouped` is **by subject** (language-dependent) and is the only place
  variant-collapse lives (editions/sizes as chips).
- **The sort toggles belong to this screen, and only to this screen.** They are part of the item list
  because this is where rows carry a size and a checkbox; the category index has neither and must not
  grow them (it orders itself by count and groups by theme). Within the item list they are **required**,
  not a nicety: this is the screen the user has to read row by row. `Grouped` is the one toggle that
  drops out when there is nothing to collapse — ZIM hides it outside Wikipedia, Kolibri never shows it,
  because a channel has no editions. Tapping the active toggle reverses it (`▲`/`▼`, `A–Z` ⇄ `Z–A`);
  tapping the other switches axis and starts at its natural direction. Items whose size is unpublished
  sort **last** in both size directions — they have no position on that axis, and first would read as
  "smallest".
- **`By size`, largest first, is the default.** This screen spends a budget, so the rows that decide
  whether the budget survives belong at the top. `A–Z` is for finding a title you already know, which is
  what the search field is for.
- **Sort pills are thinner than filter chips** — ZIM's metrics (12dp/8dp padding, `bodySmall`, no
  minimum height), which fall under the 48dp target the chips keep. Copied deliberately so Kolibri
  matches ZIM today; if the target is raised it must be raised for both at once, as one styling pass, not
  drifted into on one screen.
- **A content type with no category index lands straight here**, so this screen must be complete on its
  own: selector, search, chip row, sorts, count, storage and the fixed action. What it must *not* do is
  borrow the index's furniture — storage stays at the **bottom** in the item list (it is at the top in the
  index), and the theme chips stay a filter rather than becoming a hierarchy.
- **Storage guard**: disable/grey items that don't fit and say why (`Not enough space — needs X, Y free`).
- **Count = list metadata, on its own line.** Right-aligned above the list, **not** at the end of the
  scrollable chip row (it would wrap in es/de — see `k2go-count-overflow-v1`). Live: `N items` →
  `N results` while searching. No language in it.

### Drilling into an item (content types with a tree)
Some content types have structure *inside* an item — a Kolibri channel is a tree of topics, and a
school wants two units of a 60 GB course. That is a **third screen**, reached from the item list, and it
is the item list again with four differences:

- **A chevron at the end of the row opens it; the row tap still selects the whole item.** One gesture,
  one meaning. Never a long-press: nobody discovers it.
- **No sort toggles.** The children arrive in the order the item's author arranged them (unit 1, unit 2,
  week 3). That order is content, not presentation — re-sorting it makes a curriculum unreadable. Sorts
  belong where nobody authored an order.
- **A muted breadcrumb line** under the title says where you are; going up is the Back affordance, not a
  tappable crumb.
- **What an ancestor already covers shows as ticked and disabled**, with a one-line note. Selecting a
  parent brings its whole subtree, so a child inside it is coming but is not itself a member — and a
  checkbox must not offer to un-tick something that was never set.

Back in the item list, a narrowed item must **quote what will actually download**, not its full size, and
say so on its own line. A row that reads 60 GB after the user picked two units is a lie the storage bar
then repeats.

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
