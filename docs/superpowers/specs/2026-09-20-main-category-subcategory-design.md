# Main Category / Subcategory Redesign — Design

**Date:** 2026-09-20
**Status:** Approved, ready for planning
**Repos:** `server`, `tinniestudio-client-web`, `tinniestudio-partner-web`, `tinniestudio-admin-web` (combined design, one source of truth for all four; per-repo implementation plans follow separately)
**Depends on:** none
**Blocks:** client-web's `ROUTE_CATEGORY_SLUGS` guess-mapping (`src/lib/content-category-slugs.ts`) — this spec is its replacement

## Context

Earlier this session, client-web's browse routing (`/movies`, `/shows`, `/kids`, `/sermons`) was wired against the backend's existing flat `Category` table via `ROUTE_CATEGORY_SLUGS`. Investigation at the time confirmed `kids` and `sermons` are real seeded category slugs, but **`movies` and `tv-shows` are not** — the seed data (`V23__seed_categories.sql`) contains only genre-style categories (Action, Drama, Comedy, Thriller, Sci-Fi, Horror, Romance, Documentary, Kids, Animation, Fantasy, Crime, Sports, Sermons, Reality, History, Music, Travel). This was flagged as an unconfirmed guess requiring either new categories or a design fix.

The underlying problem: `Category` today conflates two different concepts — **which section of the app content belongs to** (Movies/TV Shows/Kids/Sermons) and **what genre it is** (Action/Horror/Sci-Fi/etc.), with no structural distinction between them. This spec introduces that distinction as two independent fields on `Content`.

This is explicitly a two-tier model, not a category tree: **main category** determines the section a title lives in (exactly one, always); **subcategory** is the existing flat genre-tag list, usable for filtering within any main category (multiple allowed, unchanged from today's behavior).

**"Lives" is explicitly excluded from this spec** — no live-streaming backend exists yet (`DomainEnums.StructuralKind` only has `SINGLE_VIDEO`/`MULTI_EPISODE`, with a comment noting `LIVE` will be added once live streaming is built). Main categories for this spec are the original four: Movies, TV Shows, Kids, Sermons.

## Data Model

**New fixed Java enum** (not an admin-editable table — unlike `Category`/`ContentType`, this is a small, stable, non-admin-editable set, matching the existing precedent set by `StructuralKind`):

```java
public enum MainCategory {
    MOVIES,
    TV_SHOWS,
    KIDS,
    SERMONS
}
```

External representation (JSON responses, query param values) uses lowercase/hyphenated slugs, not the Java constant names:

| Enum constant | External value |
|---|---|
| `MOVIES` | `movies` |
| `TV_SHOWS` | `tv-shows` |
| `KIDS` | `kids` |
| `SERMONS` | `sermons` |

**New column:** `content.main_category` — `NOT NULL` enum column (via `@Enumerated(EnumType.STRING)`, same pattern as `structuralKind`), required on every content record after backfill.

**Relationship to `structuralKind`:** fully independent, no coupling or validation between them. A `MULTI_EPISODE` title is typically `mainCategory = TV_SHOWS` in practice, but nothing enforces this — e.g. a `SINGLE_VIDEO` kids film is `mainCategory = KIDS`, not `MOVIES`, matching the original framing ("movies... all movies except from series and kids movies"). Admins/partners choose both fields independently.

**Relationship to `Category` (subcategory/genre):** no structural relationship at all. `Content.categories` (the existing many-to-many) is completely unchanged — still a flat, admin-managed, multi-select genre-tag list, still AND-filtered via `ContentSpecifications.hasCategories`. The only change to `Category` itself: the existing **"Kids" and "Sermons" rows are retired** (`isActive = false`, not hard-deleted, to preserve historical `content_categories` join rows) since their meaning is now fully covered by `mainCategory` and keeping both would let a title be simultaneously `mainCategory=KIDS` and subcategory-tagged "Kids," which is redundant and confusing.

## Migration / Backfill Policy

A single data migration backfills `main_category` for all existing content, using this priority order (best-effort inference, not perfect — admins/partners can correct individual items afterward via the normal content-update endpoints):

1. If content is tagged with the (about-to-be-retired) **"Sermons"** category → `SERMONS`.
2. Else if tagged **"Kids"** → `KIDS`.
3. Else if `structuralKind = MULTI_EPISODE` → `TV_SHOWS`.
4. Else → `MOVIES` (default fallback).

This runs as one Flyway migration: backfill `main_category` using the above SQL-expressible logic, then retire the "Kids"/"Sermons" category rows, then add the `NOT NULL` constraint.

## API Contract

**Query param:** `mainCategory` (external slug value, e.g. `mainCategory=tv-shows`), added alongside the existing `type`/`category`/`maturityRating`/`comingSoon` params on:
- `GET /contents` (general list) — **optional**. Omitting it returns content across all main categories mixed together, preserving today's behavior for homepage/trending/discover-style sections that intentionally span categories. Browse pages (`/movies`, `/kids`, etc.) pass it explicitly.
- `GET /search` — **optional**, same reasoning, enables in-section search (e.g. a search box scoped to the Kids section).

Existing `category` param (subcategory AND-filter) is used together with `mainCategory` unchanged — e.g. `GET /contents?mainCategory=kids&category=animation`.

**No path-segment routing** (`/contents/{mainCategory}`) — considered and rejected due to collision with the existing `GET /contents/{slug}` route; staying with query params avoids that class of bug entirely (this session already hit and fixed an identical route-collision bug once, for playback manifest routes).

**Response shape:** `mainCategory` added as a **plain string field** (e.g. `"mainCategory": "tv-shows"`) to `ContentResponse`, `ContentSummaryResponse`, and the partner-facing `PartnerContentResponse` — not a nested object like `contentType`, since there's no additional metadata (id/description/etc.) worth nesting for a 4-value fixed enum.

**Content creation (`CreateContentRequest`, both admin and partner-facing):** `mainCategory` becomes a **required field**, same treatment as the existing required `structuralKind`/`contentType` choice.

**Content update (existing per-repo endpoints):**
- Admin content-edit endpoint: `mainCategory` and subcategories become editable fields, same as any other content attribute, usable to correct migration mistakes or partner errors during moderation.
- Partner self-service `/partners/contents` (PATCH, per Batch 13 #8's merged endpoint): partners can also edit `mainCategory`/subcategories on their own already-published content — consistent with that endpoint's existing "partner owns and can edit their own content" model. Ownership enforcement already exists there and needs no change.

## Per-Repo Impact

**`server`:**
- New `MainCategory` enum, `content.main_category` column + migration (see above).
- `ContentSpecifications` gains `hasMainCategory(MainCategory)`.
- `ContentService.list()`/`SearchService.search()` gain the new filter param, composed with existing specifications the same way `hasType`/`hasCategories` are today.
- `ContentResponse`/`ContentSummaryResponse`/`PartnerContentResponse` gain the `mainCategory` string field.
- `CreateContentRequest` (both admin and partner creation paths) gains required `mainCategory`.
- Admin content-update and partner `/partners/contents` PATCH both accept `mainCategory`/category changes.
- Category retirement migration for "Kids"/"Sermons" rows (`isActive = false`).

**`tinniestudio-client-web`:**
- `src/lib/content-category-slugs.ts` and its guess-based `ROUTE_CATEGORY_SLUGS`/`resolveContentRouteSegment` fallback logic are replaced — browse routes (`/movies`, `/shows`, `/kids`, `/sermons`) now filter by the real `mainCategory` query param directly, no more slug-guessing or structural-kind fallback.
- Each browse page gains a **visible genre/subcategory filter control** (dropdown or chips, backed by the existing `Category` list) — this is the actual user-facing payoff of the two-tier model ("filter on sci-fi, or any subcategory available" within a main category).
- Content types gain `mainCategory: string`.

**`tinniestudio-partner-web`:**
- Content creation form gains a required **Main Category** selector (4 fixed options) alongside the existing `CategoryCheckboxList.tsx` (subcategory multi-select, unchanged).
- Content edit form gains the same, wired to the `/partners/contents` PATCH endpoint.

**`tinniestudio-admin-web`:**
- **New subcategory (genre) management screen** — list/create/edit/deactivate — since this is the natural moment to finally expose the CRUD that's existed backend-only (`AdminCategoryController`) this whole time, and retiring "Kids"/"Sermons" is a concrete first use of it.
- Content moderation/edit views gain `mainCategory` + subcategory editing, wired to the existing admin content-update endpoint.

## Non-Goals

- **No "Lives" main category** — explicitly deferred until live-streaming infrastructure exists; the enum has exactly 4 values.
- **No path-segment/route-based main-category API design** — query param only, to avoid slug-lookup route collisions.
- **No admin CRUD for main category itself** — it's a fixed 4-value enum, not a manageable entity; only subcategories (existing `Category` table) get admin CRUD.
- **No coupling/validation between `mainCategory` and `structuralKind`** — both are independently chosen; no rule ever forces one from the other.
- **No change to subcategory cardinality or the existing AND-filter semantics** — multiple subcategories per content remain allowed, unchanged from today.
