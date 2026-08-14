# ADR — Offline topic-tree depth: box-served tree, local-first with Studio fallback

Status: **Draft, for discussion.** Ticket ADFA-5094 (parent ADFA-1028). Sibling of
ADR-5094 (catalog service, the *freshness* half); this is the *depth* half. Builds on
ADR-4954 (Kolibri content selection) and **amends one consequence of it** — that topic
selection needs connectivity — while leaving the layering intact.

## Context

Channel selection is offline: the channel list is a bundled asset (ADR-4954 D1). Topic
selection is not. `CatalogRepository.fetchTree(nodeId)` is served by `StudioTreeSource`,
which reads `studio.learningequality.org/api/public/v2/contentnode_tree/<id>` over the
internet. With no network the picker cannot show what a channel contains, so a user
cannot choose subtrees to download — the one step that still requires the very
connectivity this product is built to do without.

The box already runs a Kolibri REST core on localhost (`KolibriRestClient`,
`InstalledChannelsSource` read `…:8085/k2go-api/kolibri/*`). Kolibri can import a
channel's **metadata** — its topic tree DB — separately from the (large) content files.
So the tree can live on the box at a fraction of the content's size.

## Decision

**D1 — Metadata-only import (box, PR3).** A dashboard task imports the channel's tree DB
onto the box without downloading content. The box then knows the whole tree offline; the
content is fetched later, subtree by subtree, as it is today.

**D2 — Box serves the tree, Studio-shaped (PR3).** The box answers
`GET /k2go-api/kolibri/tree/:nodeId` with the **same JSON as Studio's `contentnode_tree`**.
Not a new shape: `StudioCatalogMapper.tree(JSONObject)` then parses box and Studio
responses with no branching, and the device side stays a thin read. The box owns the
translation from Kolibri's own model into that shape.

**D3 — App routes local-first (app, PR2, this PR).** `fetchTree` prefers the box and falls
back to Studio. Concretely: a `TreeSource` seam with two implementations — `LocalTreeSource`
(box, short localhost timeouts) and `StudioTreeSource` (internet, generous timeouts) —
composed by `FallbackTreeSource` (primary, else secondary) behind `CatalogRepository`.
Every source keeps the "never throws, null on failure" contract, so a box miss — down, or
channel not imported — falls through to Studio silently.

**D4 — Two independent PRs.** PR2 is the app side (this). PR3 is the box/dashboard side
(D1, D2). They are independent: with only PR2 merged the box has no `/tree` endpoint, so
`LocalTreeSource` always misses and behaviour is exactly what it was before — the tree
comes from Studio. Nothing to gate, nothing to break; PR3 lights the local path up.

## Consequences

Amends ADR-4954's consequence "topic selection therefore needs connectivity even though
channel selection does not": it needs connectivity **unless the channel's metadata has
been imported on the box**, after which the whole tree browses offline and instantly.

A local-first fetch pays one localhost attempt before each Studio fetch when the box
cannot serve the channel. On localhost a miss is near-instant (connection refused or a
404), so the cost is negligible next to the Studio round-trip it guards.

## Rejected

**App-side cache of the Studio tree.** Prefetch and cache the Studio JSON as the user
browses, then replay it offline. Lighter — no box work — but it only ever holds what was
already opened (partial), is a side-cache that drifts from the channel, and duplicates,
in the app, knowledge Kolibri's own channel DB already keeps authoritatively. The
metadata-only import reuses Kolibri's mechanism and yields the *whole* tree, which is what
"browse a channel offline" actually requires.
