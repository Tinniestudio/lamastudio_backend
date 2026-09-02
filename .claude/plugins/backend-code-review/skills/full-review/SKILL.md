---
name: full-review
description: Full backend codebase or module review — correctness, security (especially authorization/IDOR), data integrity, performance, and test coverage, applied broadly rather than to a specific diff. Use this whenever the user asks for a backend audit, a security review of a service/module, "review this codebase", "how solid is our backend", onboarding-style architecture review, or wants a comprehensive pass before a release/handoff — not for reviewing a specific pull request or recent change (use the changes-review skill in this same plugin for that). Works on any backend stack: Java/Spring, Node/Express/Nest, Python/Django/FastAPI, Go, Ruby/Rails, etc. — make sure to consider this skill whenever backend review is requested even if the user doesn't name a specific framework.
---

# Backend Full Review

A broad audit of a backend module, service, or codebase — not tied to a
specific diff. Use this when the ask is "how solid is this", "audit our
backend", "security review this service", or similar open-scoped requests.

## Why this exists as a separate skill from changes-review

A full review and a diff review need different search strategies. A diff
review starts from a small set of changed lines and radiates outward. A
full review has no such anchor — it needs to systematically walk the
codebase's structure (modules, layers, entry points) rather than following
a change graph. Trying to do both with one prompt tends to produce either a
full review that's shallow (because it's trying to also handle diffs) or a
diff review that wastes time re-deriving the whole codebase's structure.

## Process

1. **Establish scope.** If the user named a specific module/directory,
   scope to it. If they said "the backend" or didn't specify, ask which
   module/service to start with rather than guessing at an entire large
   monorepo in one pass — a full review of everything at once produces a
   report too long to actually act on. A good default scope is one service,
   one module, or one bounded domain (e.g. "the auth module", "the payments
   service").

2. **Map the structure before reviewing.** Spend a first pass understanding:
   entry points (controllers/routes/handlers), the service/business-logic
   layer, the data-access layer, and where cross-cutting concerns
   (auth, validation, error handling) are centralized vs. reimplemented
   per-endpoint. This map is what makes an "established convention" finding
   possible later — you can't flag a one-off that ignores the pattern if
   you never established what the pattern is.

3. **Review through the checklist.** Read `references/checklist.md` for the
   full set of lenses (correctness, security, API contracts, data
   integrity, performance, testing). Work through the scoped code with
   these in mind — don't mechanically check every box against every file;
   use judgment about what's actually at stake in each piece of code.

4. **Prioritize by real-world impact, not by category count.** A single
   IDOR vulnerability on a public endpoint matters more than ten minor style
   observations. Order the report so the reader sees what actually needs
   fixing first.

5. **Verify before reporting — don't speculate.** For anything you're not
   fully sure is a real bug (a suspected race condition, a suspected
   missing auth check), trace the actual call path rather than asserting
   from pattern-matching alone. A finding that turns out to be wrong when
   the user checks it costs more trust than a shorter, fully-verified list.

## Report structure

Use this exact structure. Order findings most-severe-first within each
section; omit a section entirely if it has nothing in it (don't write
"None found" for every category — that's noise).

```markdown
# Backend Review: <scope>

## Summary
<2-4 sentences: what was reviewed, overall assessment, count of findings by severity>

## Critical
<Findings that are actively exploitable or will cause data loss/corruption/
outage. Each finding: **file:line** — one-sentence defect statement,
followed by the concrete failure scenario (specific input/state → specific
wrong outcome) and a suggested fix.>

## Important
<Real bugs or real security gaps that aren't immediately catastrophic but
should be fixed before this ships — same format as Critical.>

## Minor
<Worth fixing but low-risk — style/convention drift, missing test for an
edge case, a performance concern that only matters at high scale. Same
format, can be terser.>

## Positive notes (optional, brief)
<Only include if something is genuinely worth calling out as done well —
skip this section rather than manufacturing praise.>
```

Each finding should read like the ones in `references/checklist.md`'s own
descriptions — specific enough that the reader could reproduce the
scenario, not a vague "consider adding validation here."
