# Backend Review Checklist

This is a set of lenses to look through, not a form to fill out mechanically.
Not every item applies to every file — use judgment about what's actually at
stake in the code you're looking at. Skip categories that plainly don't
apply (e.g. no migration-related findings on a file with no schema change)
rather than padding the report to look thorough.

## Correctness

- **Race conditions on unique constraints.** A `existsBy...()` check followed
  by a `save()` is racy under concurrent requests — the real guard has to be
  the database constraint plus a caught `DataIntegrityViolationException` (or
  equivalent), not the existence check alone. Flag any create/upsert path
  that only has the check and no fallback.
- **Transaction boundaries.** Does a method that should be atomic actually
  run inside one transaction? Look for a read followed by a conditional
  write where another request could interleave — check-then-act without a
  transaction or row lock is a real bug, not a style nit.
- **N+1 queries.** A loop that calls a repository/ORM method per iteration
  instead of a single batched fetch. This is a correctness-adjacent
  performance bug, not just slow — it can also silently change semantics
  under isolation levels that allow phantom reads between iterations.
- **Null / empty handling at boundaries.** Does every public method that
  takes an id, a list, or an optional field handle `null`/empty explicitly,
  or does it get to a `NullPointerException`/equivalent unhandled at
  runtime? Pay special attention to fields that used to be required and
  became optional (or vice versa) in this change.
- **Off-by-one and boundary conditions** in pagination, date ranges, and
  numeric thresholds (`>` vs `>=`, inclusive vs exclusive ranges).
- **Error handling that silently swallows real failures.** A broad
  `catch (Exception e) { log.warn(...) }` is correct for genuinely
  best-effort side effects (an analytics beacon, a cache warm) but wrong
  around anything the caller needs to know failed.

## Security

- **Authorization, not just authentication.** An endpoint requiring login is
  not the same as one that checks the caller owns the resource being
  accessed/mutated. For any endpoint taking a resource id, ask: could a
  different authenticated user pass a different id and touch someone else's
  data? This is the single most common real backend vulnerability
  (IDOR) — treat every `{id}` path/body parameter as a potential one until
  proven otherwise.
- **SQL/query injection.** Any raw/native query built via string
  concatenation instead of parameter binding. ORMs mostly prevent this by
  default — the risk concentrates in native queries, raw SQL builders, and
  anywhere a sort column or table name is taken from user input.
- **Mass assignment.** Does a request DTO map 1:1 onto every field a client
  should be allowed to set, or can a client sneak in a field like `role`,
  `status`, `isAdmin`, `userId` that should only ever be set server-side?
- **Secrets and credentials.** Hardcoded keys/tokens/passwords, credentials
  logged at info/debug level, secrets embedded in error messages returned
  to the client.
- **Rate limiting on public/expensive endpoints.** Anything unauthenticated,
  or authenticated but cheap-to-call-and-expensive-to-process (search,
  export, anything hitting an external API), should have some throttle.
- **Public read endpoints leaking non-public data.** Does a "public" list/get
  endpoint's response object accidentally include internal-only fields
  (cost, margin, another user's PII, moderation notes) because it reuses an
  internal DTO instead of a dedicated public-facing one?

## API contracts & compatibility

- **Breaking changes to existing response shapes** — a field renamed,
  retyped, or removed on a response type that other services/clients
  already consume, without a version bump or a compatibility note.
- **Status codes that don't match what actually happened** — 200 on a
  partial failure, 500 on a client input error that should be 400/422, 200
  with an empty body instead of 404.
- **Validation gaps** — is every field with real constraints (length, range,
  format, required-ness) actually annotated/validated, or does invalid
  input only get caught downstream (or not at all)?

## Data integrity & migrations

- **Migrations that lose data irreversibly** — a dropped column/table with
  no backfill of its data elsewhere first, in a single migration with no
  chance to verify the backfill before the drop.
- **Missing indexes for new query patterns** — a new filter/sort/join
  introduced in this change against a column with no supporting index.
- **NOT NULL added to a populated table** without a safe add-then-backfill-
  then-constrain sequence.
- **Soft-delete vs hard-delete consistency** — does this change respect
  whatever the codebase's established convention already is for the entity
  it touches? Introducing a hard delete next to existing soft-deletes (or
  vice versa) is usually a bug, not a stylistic choice.

## Performance

- **Unbounded input driving unbounded work** — anything where a request
  parameter (a list length, a page size, a date range) directly controls
  how much backend work happens, with no cap. A comma-separated filter
  list that becomes one join per entry, with no limit on entries, is a
  concrete example of this class of bug.
- **Cache invalidation correctness** — does a write path actually evict/
  update every cache entry a read path could have populated, including
  list-level caches invalidated by a single-item write?
- **Synchronous work that should be async** — a request handler doing slow,
  non-essential work (sending an email, publishing an analytics event)
  inline instead of via a queue/background job, especially if failure of
  that side work shouldn't fail the whole request.

## Testing

- **New branches without a test.** Every new `if`/`switch` branch, new
  exception path, and new endpoint should have at least one test exercising
  it — not just the happy path.
- **Tests that would pass even if the implementation were wrong.** A test
  that mocks the thing it's supposed to be verifying, or asserts on a
  trivial property instead of the actual behavior change, isn't real
  coverage even though it's green.
- **Fixture drift.** When a shared entity/type changes shape, do all the
  tests that build fixtures of it still construct valid instances, or did a
  required field get missed somewhere?

## Reading the code, not just the diff

A change can be locally correct and still wrong in context. Before
finishing, sanity-check: does this change match how the rest of the
codebase already does this kind of thing (naming, error handling, DTO
separation between internal/external audiences, existing validation
patterns)? A one-off that works but ignores an established convention is
worth flagging even when nothing is technically broken — it's exactly the
kind of thing that causes real bugs down the line when someone assumes the
convention was followed.
