# Multi-Category Content Filtering — Design

**Date:** 2026-09-01
**Status:** Approved, ready for planning
**Repo:** `server` (api-service)
**Depends on:** none (independent of [Dynamic Content Type Management](2026-09-01-dynamic-content-type-design.md) — touches the same `ContentController`/`ContentSpecifications` files but a different field, so implement/rebase carefully against that spec if both land around the same time)
**Blocks:** client-web Content Model & Browse Integration spec (Sermons/Kids sub-category filter dropdowns)

## Context

Surfaced while brainstorming client-web's browse integration: the mocked
Sermons and Kids pages have a sub-category filter dropdown on top of their
top-level category (e.g. Sermons page, narrowed further to "Bible Study
Series"). The decision was to support this for real rather than drop it.

`ContentController.list` today accepts a single `category` slug, filtered
via `ContentSpecifications.hasCategory(String)` (one join, one equality
check). Narrowing "Sermons" down to "Bible Study Series" requires content
tagged with **both** categories to match (AND semantics) — a piece of
content tagged only "Bible Study" but not "Sermons" should not appear when
browsing the Sermons page, even with that sub-filter selected.

## Goal

Let `ContentController.list` filter content that belongs to multiple
categories at once (AND — must have all given tags), without breaking the
existing single-category behavior or adding a new query parameter name.

## Design

- `category` keeps its existing type and name (`String`, optional query
  param) but is now interpreted as a **comma-separated list of slugs**.
  `category=sermons` behaves exactly as it does today (single slug, no
  behavior change, fully backward compatible). `category=sermons,bible-study`
  now means "has both."
- `ContentService.list` splits the raw string on `,`, trims/drops blanks,
  and passes the resulting list down.
- New `ContentSpecifications.hasCategories(List<String> slugs)` replaces the
  `hasCategory(String)` call site in `ContentService.list`. Implementation:
  one `root.join("categories", JoinType.INNER)` per slug (each loop
  iteration produces its own join alias in Hibernate's Criteria API, so N
  slugs correctly requires N distinct category memberships — the standard
  "tag AND filter" pattern), each joined alias constrained to its one slug,
  ANDed together. `query.distinct(true)` still applies, same as the existing
  `hasCategory`/`hasAnyCategory` methods.
- `hasCategory(String)` and `hasAnyCategory(Collection<UUID>)` (existing,
  OR-semantics-by-id, used elsewhere) are left as-is — this adds a new
  method alongside them rather than changing their behavior.

## Non-goals

- `DiscoverController`'s single-slug `/category/{slug}` (used for
  admin-curated homepage shelves, not the paginated browse-with-filters UX)
  is untouched.
- `SearchController`/`SearchRequest.categorySlug` is untouched — Search is
  its own separate spec; revisit multi-category search then if needed.
- No new admin UI for defining "this category is a sub-category of that
  one" — sub-categories are just ordinary `Category` rows an admin tags
  content with alongside the parent category. No hierarchy is modeled or
  enforced.
