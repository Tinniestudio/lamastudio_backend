# Dynamic Content Type Management — Design

**Date:** 2026-09-01
**Status:** Approved, ready for planning
**Repo:** `server` (api-service)

## Context

Client-web currently mocks every content-browsing feature (movies, tv-shows,
live-shows, sermon, kids, home, search, my-list, watch); only auth, account,
and billing are integrated against the real backend. Bringing client-web onto
real endpoints started as a client-side integration effort, but surfaced a
blocking prerequisite: the frontend's `ContentType` enum
(`MOVIES | SHOWS | LIVES | KIDS | SERMONS`) doesn't map cleanly onto the
backend's `ContentType` (`MOVIE | SERIES`, a hardcoded Java enum column on
`Content`).

Working through the reconciliation surfaced two decisions:

1. **Kids / Sermons / Live Shows / Movies / TV Shows are all Category
   filters**, not content types. The existing `Category` system (admin-managed,
   many-to-many with `Content`) already supports this with zero backend
   changes — `GET /contents?category=<slug>` and `GET /discover/category/{slug}`
   already work. "TV Shows" as a category label is expected to correlate with
   series-structured content, but that's a naming convention, not an
   enforced constraint.
2. **`ContentType` itself needs to become admin-manageable**, like `Category`,
   instead of a hardcoded 2-value enum — but `type` today also drives (or is
   intended to drive) structural behavior: whether content has seasons/episodes,
   which upload flow partner-web shows. A fully open CRUD table with no
   behavioral metadata would just move the hardcoding problem down a layer —
   the app would have no way to know if a newly admin-created type behaves
   like a movie or a series.

This spec covers only #2. #1 requires no backend work and is handled entirely
in the client-web integration spec that depends on this one.

Live streaming is a known future feature. This spec deliberately does not
build anything live-streaming-related — it only makes sure the shape it
introduces doesn't have to be reworked when that spec starts.

## Goals

- Replace the hardcoded `ContentType` enum column on `Content` with an
  admin-manageable `ContentType` entity (name, slug, active/inactive, display
  order) — same shape and admin-CRUD pattern as the existing `Category`.
- Preserve the structural distinction between single-video and multi-episode
  content that today's `MOVIE`/`SERIES` split provides, via a fixed
  `structuralKind` field on each `ContentType` row, so upload/season logic
  has something reliable to branch on regardless of what an admin names or
  adds.
- Close a currently-unenforced integrity gap: nothing today stops a
  movie-kind content from having `Season` rows attached.
- Leave room for a `LIVE` structural kind later without requiring a schema
  migration when that day comes.

## Non-goals

- No `LIVE` structural kind or any live-streaming behavior — deferred to the
  future live-streaming spec.
- No partner-web/admin-web UI changes. Those apps currently branch
  upload/season UI on the old raw `type` value; they'll need their own
  follow-up work to consume `GET /content-types` and branch on
  `structuralKind` instead. Noted as a dependency, not built here.
- No retroactive data beyond backfilling existing `MOVIE`/`SERIES` rows to
  the two seeded `ContentType` rows.

## Data model

New table `content_type` (Flyway `V53__add_content_types.sql`):

| column | type | notes |
|---|---|---|
| `id` | UUID | PK |
| `name` | varchar, not null | e.g. "Movie" |
| `slug` | varchar, unique, not null | admin-supplied, same convention as `Category.slug` (not auto-derived) |
| `structural_kind` | varchar, not null | string-backed enum: `SINGLE_VIDEO` \| `MULTI_EPISODE` |
| `description` | varchar, nullable | |
| `is_active` | boolean, not null, default true | |
| `display_order` | int, not null, default 0 | |

`structuralKind` is a fixed, non-admin-editable Java enum
(`DomainEnums.StructuralKind`), currently 2 values. It is **not** a free-text
column — the whole point is that the app can trust it. Adding `LIVE` later is
a one-line enum addition; the column is already string-backed, so no
migration is needed when that happens.

Same migration:
1. Seeds two rows: `("Movie", "movie", SINGLE_VIDEO)`,
   `("Series", "series", MULTI_EPISODE)`.
2. Adds `content.content_type_id` (UUID, FK to `content_type`).
3. Backfills `content_type_id` from the existing `content.type` enum column
   (`MOVIE` → movie row, `SERIES` → series row).
4. Drops the old `content.type` column.

This is one atomic migration with no dual-read transition period — the
dataset is small enough that a single deploy-time migration is acceptable.

Entity changes:
- New `ContentType` JPA entity, same shape/conventions as `Category`
  (`BaseEntity`, `@Column(unique = true) slug`, etc.).
- `Content.type` (`@Enumerated(EnumType.STRING) ContentType type`) is
  replaced by `Content.contentType` (`@ManyToOne ContentType contentType`).

## API surface

**`AdminContentTypeController`** — `/admin/content-types`, `@PreAuthorize("hasRole('ADMIN')")`.
Mirrors `AdminCategoryController`'s JSON pattern (no poster/multipart variant
needed here):
- `GET /admin/content-types` — list all, including inactive
- `POST /admin/content-types` — create (name, slug, `structuralKind`,
  description, displayOrder, isActive)
- `PATCH /admin/content-types/{id}` — update
- `DELETE /admin/content-types/{id}` — delete

**`ContentTypeController`** (public) — `GET /content-types` — active types
only, for populating dropdowns (partner-web/admin-web content-creation
forms) and any client display needs.

**Existing endpoints, updated:**
- `ContentController.list`'s `type` query param moves from enum-equality to
  a slug-based join — same mechanism `ContentSpecifications.hasCategory`
  already uses for `category`. `?type=movie` instead of `?type=MOVIE`.
  `ContentSpecifications.hasType(ContentType type)` becomes
  `hasType(String contentTypeSlug)`.
- `CreateContentRequest` / `UpdateContentRequest`: `type: ContentType` →
  `contentTypeId: UUID` (consistent with the existing
  `categoryIds: List<UUID>` field).
- `ContentResponse` / `ContentSummaryResponse` / `PartnerContentResponse`:
  expose a nested `contentType: { id, name, slug, structuralKind }` object
  instead of a raw `type` string.
- `SearchRequest.type` (`ContentType`) → `String typeSlug`, filtered the
  same way as `ContentController.list`.

## Season integrity check

`SeasonService.create` (called from `AdminSeasonController`, roles
`ADMIN`/`PARTNER`) gains a check: reject with 409/422 if the target
content's `contentType.structuralKind != MULTI_EPISODE`. This closes the
current gap where a movie-kind content can silently accumulate `Season`
rows — nothing enforces the relationship today.

## Migration/rollout risk

Single Flyway migration, single deploy. No feature flag, no dual-write
period — acceptable given the small dataset size and that this is a schema
correction, not a live traffic-sensitive change. `Content.getType()` callers
across the codebase are minimal by design (verified: `ContentSpecifications`,
`ContentService.create`, DTO mapping — no deep business-logic branching on
the enum value elsewhere), so the blast radius of the FK swap is small and
contained to this module.

## Downstream dependencies (not built here)

- Client-web content-model-reconciliation spec (Spec B) can only integrate
  against `GET /content-types` and the updated `ContentController`/`SearchController`
  contracts once this ships.
- Partner-web/admin-web content-creation and season/episode UI need a
  follow-up to swap their raw-type checks for `structuralKind` checks, and to
  populate content-type pickers from `GET /content-types` instead of a
  hardcoded dropdown.
