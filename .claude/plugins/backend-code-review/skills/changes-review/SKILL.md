---
name: changes-review
description: Review backend changes — a diff, a branch, a PR, or "what I just changed" — not the whole codebase. Pulls in and reviews the files those changes are actually connected to (callers of a changed method, tests for a changed class, migrations for a changed entity, DTOs consumed by a changed service) so the review catches breakage the diff alone can't show, not just the changed lines in isolation. Use this whenever the user asks to review a diff, a PR, "my changes", "before I commit", "check what I just did", or names a branch/commit range — for backend code. Use the full-review skill in this same plugin instead when the ask is a broad audit with no specific change as the anchor. Works on any backend stack.
---

# Backend Changes Review

Reviews a specific set of changes — not the whole codebase — but reviews
them **in context**, not in isolation. A diff that looks correct on its own
can still break a caller it doesn't show, leave a test asserting on old
behavior, or leave a migration referencing a column a code change just
renamed. This skill's whole reason to exist is closing that gap.

## Why "just read the diff" isn't enough

The most common failure mode of a changes-only review is approving a diff
that's internally consistent but externally wrong — a method signature
changed and every call site was updated except one the diff tool happened
to order last, or a DTO field renamed with the test file for it left
un-updated. Catching this requires deliberately looking *outside* the
changed lines, which is exactly what most "review this diff" requests skip.

## Process

### 1. Determine the change set

Figure out what's actually being reviewed, in order of how the user phrased
it:
- A specific PR/branch/commit range they named → use that.
- "What I just changed" / "before I commit" with no range given → diff
  against the working tree's base (uncommitted changes plus, if relevant,
  commits not yet on the base branch). Ask which if genuinely ambiguous
  (e.g. multiple unmerged commits and unclear how far back to look) rather
  than guessing.
- A list of specific files → treat those as the change set directly, no
  diff needed.

Get the actual diff content (not just filenames) — you need to see what
changed, not only where.

### 2. Discover connected files

For each changed file, find what depends on it and what it depends on. This
codebase has no assumed language server, so do this by grep/search, not by
assuming any particular tool exists:

- **Reverse dependencies (callers).** For each changed public
  class/function/method, search the codebase for references to its name —
  who imports this file, who calls this method, who constructs this class.
  A renamed or resignatured method needs every call site checked, not
  trusted to have been updated correctly.
- **Tests.** Find the test file(s) for each changed file — common
  conventions are a mirrored path under a test directory, or a
  `*Test`/`*.test.*`/`*.spec.*`/`test_*` naming pattern matching the
  changed file's name. If a changed file has no discoverable test at all,
  that's itself worth noting, not just silently skipped.
- **Schema/migration connections.** If an entity/model file changed (new
  field, renamed field, changed type), check whether a corresponding
  migration exists for it, and whether that migration is consistent with
  the code change (right column name, right type, right nullability).
- **DTOs and contracts.** If a changed file is a response/request type
  (or the codebase's equivalent — a serializer, a schema, an API contract
  file), find what constructs it and what consumes it, and check the
  change didn't silently drop a field a consumer relies on.
- **Sibling changes in the same diff.** If file A imports file B and both
  changed in this same diff, check they're consistent with each other —
  this is the cheapest connected-file check since both are already in the
  change set.

Don't chase this graph indefinitely — one hop out from each changed file
(direct callers/dependents, direct tests, direct schema/contract
counterparts) covers the overwhelming majority of real breakage. If a
first-hop file itself looks suspicious or clearly needs another hop to
understand (e.g. a caller that itself passes the changed value further
down an unclear path), follow it — but don't systematically expand to two
hops on everything as a default.

### 3. Review the change against the checklist

Read `references/checklist.md` for the full set of lenses. Apply it to:
- the changed lines themselves, and
- the connected files, specifically for *consistency with the change* —
  did the connected file actually get updated correctly, does it still
  make sense given what changed, does a test still test real behavior or
  did it get updated to just match whatever the new code happens to do
  (a test that was "fixed" to match a bug isn't coverage).

### 4. Report

Use this exact structure:

```markdown
# Changes Review: <branch/PR/range/description>

## Summary
<what changed, at a glance — 2-3 sentences>

## Connected files checked
<Brief list: which callers/tests/migrations/contracts you traced to, and
what you found there — even a one-line "checked X, consistent" per file is
useful so the reader knows the connected-file check actually happened, not
just the diff.>

## Critical
<Findings that are actively exploitable, will break at runtime, or corrupt
data. Same finding format as full-review: file:line, one-sentence defect,
concrete failure scenario, suggested fix.>

## Important
<Real bugs/gaps that should be fixed before merge.>

## Minor
<Worth fixing but non-blocking.>
```

Omit a section with nothing in it. If the connected-file check surfaced
nothing wrong, still keep the "Connected files checked" section — showing
what was checked (and found fine) is part of what makes this review
trustworthy, distinct from a review that only looked at the diff.
