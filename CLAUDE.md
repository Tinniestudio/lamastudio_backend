I  have a clear picture of the landscape. Before proceeding, I have several questions that will shape the Batch 3 implementation significantly.

What exists already:

Category entity in shared/entity/ — but missing poster_url and display_order fields from the spec
Content entity with @ManyToMany categories already wired
No CategoryRepository, CategoryService, CategoryController, or any category/ or discover/ module
No homepage_sections table
Next available migration: V15
Here are the questions I need answered before writing a line of code:

1. Category entity gaps
The existing Category.java is missing poster_url and display_order from the BATCH-PLAN spec. Should both be added in the V15 migration, or is poster_url deferred until the upload system (Batch 6) exists?

2. Trending algorithm — no view data exists yet
The plan says TRENDING = "most-viewed content last 7 days", but there's no view count column and no analytics events table until Batch 16. For Batch 3, what should trending sort by?

published_at DESC (newest = trending proxy)
Add a view_count column to contents now
Return empty results with the correct schema (Batch 4/16 will populate it)
3. Homepage sections — hardcoded or DB-driven?
The homepage_sections table lets admins configure section order and types. But there are no admin endpoints for managing it in the Batch 3 spec. Should we:

Create the homepage_sections table and seed it with default sections (no admin CRUD)
Hardcode section types in the service (simpler, no migration for sections table)
Build the full admin section management now (even though it's not in the spec)
4. CONTINUE_WATCHING section
The watch_progress table doesn't exist until Batch 8. For GET /discover/home, should the CONTINUE_WATCHING section:

Be included in the response with an empty array in Batch 3 (correct schema now, real data in Batch 8)
Be excluded entirely from Batch 3 (added when Batch 8 adds watch_progress)
5. Discover endpoints with no content
/discover/featured, /discover/trending, /discover/new-releases all need content data that won't exist until Batch 4. Should we build these endpoints now (returning empty arrays with correct structure), or defer them to Batch 4?

6. Category poster_url — upload or URL string?
When an admin sets a category image, should they:

Paste a direct URL (simple string field, no upload system dependency)
Use the presigned upload flow (not available until Batch 6)
7. Admin homepage section management
The BATCH-PLAN doesn't define endpoints to manage homepage_sections. Should we add them to Batch 3 scope (POST/PATCH/DELETE /admin/homepage-sections), or is section config purely seeded via migration?

8. Slug collision handling
Auto-generate slug from name. For collisions ("action" already exists → "action-2", "action-3"), should this logic live in the service layer or database trigger?

1. both should be added 
2. included trending algorithm, and content should have vast filter, simliar to nextflx, e.g (coming soon, tredning, action, scific, kids, sermons, tvshows, action, movies, and many more)
3. create homepage section
4. cretae watch progress table
5. included batch 4 together with this bacth
6. use the presigned upload flow
7. add it to the bacth
8. slug should be handle in database for efficency and reduce query time
note: task.md still remiain source of truth


Good. I have a clear picture of what exists. The entities are all pre-built, but no DB migrations exist yet for content/categories/seasons/episodes/watch_progress. Last migration is V14. Let me ask the critical follow-ups before writing the spec:

A. ContentStatus enum — needs expansion
The existing DomainEnums.ContentStatus has: DRAFT, PROCESSING, PUBLISHED, ARCHIVED. The plan defines a DRAFT → REVIEW → PUBLISHED/REJECTED → ARCHIVED workflow. You also want "coming soon". Which statuses should exist?

Should COMING_SOON be a separate status, or a boolean is_coming_soon flag on content (so it can be PUBLISHED but flagged as coming soon)?
Should I add REVIEW and REJECTED to the enum now, or does the current workflow skip the review step?
B. Category poster_url — Batch 6 isn't built yet
You said use the presigned upload flow, but the upload session system (Batch 6) doesn't exist in this combined batch. Two options:

Store poster_url as a direct URL string in this batch (admin pastes a CDN/storage URL) — wire to presigned upload when Batch 6 is built
Build a minimal single-purpose image upload endpoint for categories now (simplifies things, no upload session tracking)
C. Trending algorithm — what's the source data?
"Most viewed in last 7 days" requires view tracking. Options:

Add a view_count BIGINT DEFAULT 0 column to contents (incremented by the playback system in Batch 8) — trending = highest view_count DESC
Just sort by published_at DESC as a proxy for trending in MVP (real trending recalculated when analytics is live in Batch 16)
D. Netflix-style filters — how many categories should be seeded?
You mentioned: action, sci-fi, kids, sermons, TV shows, movies, coming soon, trending + more. Should I seed a default set of categories via a Flyway migration, or leave it to admin to create via the admin API?

E. "Sermons" as content type?
ContentType currently has MOVIE and SERIES. "Sermons" sounds like a category (tag applied to content), not a type. Should the content type remain MOVIE | SERIES, with sermons just being a category name? Or do you want a third type like SPECIAL?

F. Combined batch — Batch 5 (Episodes + Series) inclusion?
You said combine Batch 3 + 4. Batch 4 includes Series content type which directly requires Season and Episode entities (Batch 5). Should I include Batch 5's season/episode controllers as well, so SERIES content is fully usable in this batch? Or stop at Batch 4 (content CRUD) and add season/episode controllers in a follow-up?


A. make the comming soon boolean, and added Review and reject
B. build a single purposer imgae upload for category poster or make use for formdata, that send the image together witht the other data and pass the image upload to storage, and get the url from storage and save the url together with the data
C. use view count for trending
D. seed categorires and also allow admin to create categoey via endpoint with CRUD operation
E. content type remain sermon is just a category and it content type will be select by admin/partner to eaith movie or series (but what better name can we user for movies that are not series)
F. combine





On your question about MOVIE naming — keep MOVIE. Netflix uses it, users understand it universally. Standalone films = MOVIE, ongoing shows = SERIES. Sermon content just uses the MOVIE type with the "Sermons" category. Clean and simple.

Now confirming the full combined scope before writing the spec:

Combined Batch 3 + 4 + 5 — Full Scope

Area	What's included
DB	V15–V24 migrations: categories update, homepage_sections, contents, content_categories, content_cast, seasons, episodes, watch_progress, category seeds, section seeds
Enums	Add REVIEW, REJECTED to ContentStatus; add MaturityRating; add SectionType; add coming_soon boolean + view_count to Content
StorageService	Add uploadFile(key, bytes, contentType) for multipart category poster upload
Category module	CRUD (admin) + public list/detail, multipart image upload to MinIO, Redis cache (10 min), slug via DB trigger
Homepage Sections	homepage_sections table, admin CRUD, Redis cache (5 min)
Content module	Full CRUD (MOVIE/SERIES), status workflow (DRAFT→REVIEW→PUBLISHED/REJECTED/ARCHIVED), view_count, coming_soon, maturity_rating, content_cast, Netflix-style filter query params, partner + admin + public endpoints
Discovery module	/discover/home, /discover/trending, /discover/featured, /discover/new-releases, /discover/coming-soon + category-based rows, Redis caching per section
Season module	Full partner CRUD for seasons
Episode module	Full partner CRUD + auto-numbering + reorder
Watch progress	Migration + entity (endpoints wired in Batch 8)
Now writing the spec doc.

superpowers:writing-plans skill
