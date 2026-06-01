# [PROJECT_NAME] Constitution
<!-- Replace [PROJECT_NAME] with the actual project name, e.g. "TinnieStudio Server" -->

## Core Principles

### I. Feature Lifecycle Governance (NON-NEGOTIABLE)
<!-- Define the mandatory lifecycle stages: IDEA → SPECIFY → PLAN → IMPLEMENT → VALIDATE → COMPLETE -->
<!-- State rules about skipping stages, implementation gates, and independent testability -->
[FEATURE_LIFECYCLE_RULES]

### II. Batch Boundary Rules
<!-- Define what constitutes a batch, ordering constraints, and dependency declaration requirements -->
<!-- Specify batch classification categories for your project -->
[BATCH_BOUNDARY_RULES]

### III. API ↔ Worker Governance (if applicable)
<!-- Define ownership boundaries between API service and any async worker services -->
<!-- State which service owns which data and which writes to what -->
[API_WORKER_GOVERNANCE]
<!-- Remove this section if there is no worker service in your architecture -->

### IV. Domain Ownership (NON-NEGOTIABLE)
<!-- Map each domain to exactly what it owns -->
<!-- State the rule: no domain may inject another domain's repository -->
[DOMAIN_OWNERSHIP_TABLE]

### V. Scalability Governance
<!-- Define what must NOT require rewrites when the system grows -->
<!-- Examples: new roles, new plan tiers, new content types, new worker instances -->
[SCALABILITY_RULES]

### VI. Infrastructure Governance
<!-- Define the abstraction layer rules for storage, cache, queue, mail, config -->
<!-- State the key namespacing convention for cache/session stores -->
<!-- Define idempotency requirements for async consumers -->
[INFRASTRUCTURE_RULES]

### VII. Completion Gate Governance
<!-- Define the mandatory gates every batch must pass before COMPLETE -->
<!-- Include: functional, security, integration, performance, rollback readiness -->
[COMPLETION_GATES_TABLE]

### VIII. Architecture Drift Prevention
<!-- List specific rules and their corresponding violation examples -->
<!-- These are enforced by code review and your CI/Speckit tooling -->
[DRIFT_PREVENTION_TABLE]

### IX. Shared Contract Governance
<!-- Define response envelope standard, pagination standard, error code standard -->
<!-- Define versioning rules for DTOs and queue payloads that cross service boundaries -->
[SHARED_CONTRACT_RULES]

### X. Security Boundaries
<!-- Define auth isolation rules, token separation, session policies -->
<!-- Define what actor types exist and how each is isolated from the others -->
[SECURITY_BOUNDARY_RULES]

---

## Architecture Boundaries

### [PRIMARY_SERVICE_NAME] Responsibilities
<!-- List what the primary service owns: business logic, entity state, endpoints, etc. -->
[PRIMARY_SERVICE_RESPONSIBILITIES]

### [WORKER_SERVICE_NAME] Responsibilities (if applicable)
<!-- List what the worker service is limited to -->
<!-- State horizontal scaling requirements and statelessness requirements -->
[WORKER_SERVICE_RESPONSIBILITIES]

### Infrastructure Abstraction Layers
<!-- Show the layering diagram: Domain → Interface → Implementation -->
[ABSTRACTION_LAYER_DIAGRAM]

---

## Execution Standards

### Speckit Process Order
<!-- List the Speckit skill invocation order for this project -->
<!-- Include: specify → plan → tasks → implement → analyze -->
[SPECKIT_PROCESS_STEPS]

### Test-Driven Development (NON-NEGOTIABLE)
<!-- State the TDD rule: failing test first, confirm fail, implement, confirm pass, commit -->
[TDD_RULE]

### Quality Gates
<!-- List pre-commit requirements: tests pass, no placeholders, OpenAPI docs, migrations -->
[QUALITY_GATE_CHECKLIST]

---

## Governance

<!-- State that this constitution supersedes other development practices -->
<!-- Define the amendment process: rationale, impact review, version increment -->
<!-- State how drift is handled: blocking issue, not a comment -->
[GOVERNANCE_RULES]

**Version**: [CONSTITUTION_VERSION] | **Ratified**: [RATIFICATION_DATE] | **Last Amended**: [LAST_AMENDED_DATE]
<!-- Example: Version: 1.0.0 | Ratified: 2026-05-29 | Last Amended: 2026-05-29 -->
