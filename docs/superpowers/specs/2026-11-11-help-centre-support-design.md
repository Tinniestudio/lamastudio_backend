# Help Centre & Customer Support — Design

## Background

CLAUDE.md (new feature): "Help Centre and customer support" — live chat, support
ticket submission, a verified support email and phone number, a company-mail alert
on every new ticket, and a dedicated page where a user can see their conversation and
track their ticket. Admin-web is noted as "just started," planned to eventually have
a chat page.

Current state, confirmed via exploration: the "Help Centre" is a static Contact Us
page with hardcoded, unverified emails, a dead `/help-center` footer link, and
non-functional stub buttons/links. No ticket, chat, or internal-notification
infrastructure exists anywhere in `server`, `tinniestudio-client-web`, or
`tinniestudio-admin-web` — not even partially. No `SUPPORT`/`AGENT` admin role
exists; only `SUPER_ADMIN`/`MODERATOR`. No WebSocket/real-time transport exists in
any of the three repos.

This is decomposed into three parts, decided during brainstorming.

## Part A — Help Centre contact info fixes

Smallest, mostly cleanup on `tinniestudio-client-web`:

- Replace the hardcoded `DEPARTMENTS` emails in `ContactUsClient.tsx:20-39` with the
  confirmed-verified, monitored support address (confirm which address is actually
  live before shipping — CLAUDE.md explicitly says not to display an unverified one).
- Fix the dead `/help-center` footer link (`Footer.tsx:33`) to point at wherever the
  new Help Centre page (ticket submission + live chat entry point) ends up living.
- Remove the non-functional "Open Knowledge Base" button (`ContactUsClient.tsx:127-129`,
  no `onClick`/`href`) and the dead `href="#"` social links (`:41-46`), or wire them to
  real destinations if available.
- Display a phone number only if one genuinely exists — otherwise the acceptance
  criterion ("if one is available") is satisfied by omitting it, not by a placeholder.

## Part B — Live chat via Tawk.to

Per decision during brainstorming: live chat is a **third-party embed (Tawk.to)**,
not an in-house build — no WebSocket/real-time infrastructure exists in any of the
three repos, and building one would be a separate, much larger effort on its own.

- Add the Tawk.to embed script (account/widget ID to be supplied at implementation
  time — not something generated here) to the Help Centre/Contact page, or the root
  layout if it should be available site-wide.
- This alone satisfies "Live Chat is accessible and routes to the support team" —
  Tawk.to's own dashboard handles agent-side routing; no backend work needed for this
  part.

## Part C — Support ticket system

The substantial new build. Entities, APIs, and UI are new from scratch in all three
repos.

### Entities (server, new)

- **`SupportTicket`**: `userId`, `subject`, `status`, a short human-friendly
  `ticketNumber` (shown to the user as their confirmation reference — cosmetic only,
  not used for internal lookups, which use the real id; must be generated with a
  uniqueness/collision check), timestamps.
- **`TicketMessage`**: `ticketId`, sender type (`USER`/`ADMIN`) + sender id, `body`,
  optional attachments, timestamps.

**Status lifecycle**: `OPEN` (just submitted) → `IN_PROGRESS` (an agent has replied —
auto-transitions on the first admin reply, no separate "pick up" action) →
`RESOLVED` (agent marks it done) → `CLOSED` (terminal). A user reply on a `RESOLVED`
ticket auto-reopens it to `IN_PROGRESS`. A reply on a `CLOSED` ticket is rejected with
a clear message directing the user to open a new ticket, rather than silently
reopening an archived case.

**Assignment model**: no per-agent assignment/routing, kept deliberately simple — any
admin account (any role) can view and reply to any ticket, matching CLAUDE.md's
"admin or support can login and reply." The new `SUPPORT` role exists for staff whose
only job is tickets, scoped away from other admin areas — it is additive, not a
replacement for `MODERATOR`/`SUPER_ADMIN` access to tickets.

### Access control (new role)

- Backend: add `SUPPORT` to `AdminRoleName` (`modules/auth/admin/entity/AdminRoleName.java`,
  alongside existing `SUPER_ADMIN`, `MODERATOR`). `@PreAuthorize` on new ticket
  controller endpoints permits any of the three admin roles.
- `tinniestudio-admin-web`: add `SUPPORT: ["tickets:manage"]` to `ROLE_PERMISSIONS`
  in `rbac.ts:12-15`, alongside the existing entries. Existing roles
  (`SUPER_ADMIN`'s wildcard already covers it; `MODERATOR` needs `tickets:manage`
  added explicitly to its permission list too, so existing staff aren't locked out of
  a capability they're expected to have per CLAUDE.md's "admin or support" wording).

### Attachments

Reuse the existing `StorageService` presign → direct-upload → confirm pattern
(`UserProfileServiceImpl.initiateAvatarUpload`/`confirmAvatarUpload` is the template
to follow) rather than building a new upload pipeline: client requests a presigned
URL scoped to a ticket/message, uploads directly to storage, confirms with the
backend, which attaches the resulting storage key to the message. Mime/size limits
follow the same validate-server-side-not-client-declared pattern as the avatar flow,
sized for ticket attachments (e.g. images + PDF, a size ceiling to be set at
implementation time).

### Company-email alert

New `AppProperties` field for the internal support-alert recipient address (no
existing field for an internal/ops recipient — only a sender address exists today).
New `EmailService.sendNewTicketAlert(ticket)`, modeled on the existing
`sendAdminPasswordResetAlert` (the nearest precedent for "email internal staff about
an event," `EmailService.java:53-58`) — called on ticket creation, wrapped so an
email-provider failure never blocks ticket submission itself (log and continue).
Kept intentionally simpler than the async-worker pattern used in the other two specs
in this backlog pass (new-content-notifications, device-login-limit): this is a
single fixed recipient, not a fan-out, so the latency/decoupling concern that
motivated the worker pattern there doesn't apply here in the same way.

### User-facing reply notification

New `TICKET_REPLIED` event type, delivered through the already-generalized
`sendNotification()` (IN_APP + EMAIL per the user's own preference, from the
new-content-notifications spec's schema change) when an admin replies. Requires a
new `TICKET` case in `NotificationBell.tsx`'s `resolveHref()` (the documented
extension point at `:24-25`), linking to the ticket thread page.

### Client-web UI

- Ticket submission form (subject + message + optional attachment), reachable from
  the Help Centre/Contact page alongside the Tawk.to live-chat entry point and the
  verified email/phone from Part A. Shows the `ticketNumber` as an immediate
  confirmation on submit.
- A new "My Tickets" list + thread/chat page under the account area — this is the
  "dedicated chat page where user can see their chat conversation and also keep
  track of ticket" from CLAUDE.md. Message thread view (user and admin messages),
  a reply box, attachment display.

### Admin-web UI

- New "Tickets" navigation section: a list view (filterable by status) and a
  detail/reply page — message thread, reply box, a status control
  (`RESOLVED`/`CLOSED`).

## Error handling & edge cases

- Attachment mime/size validated server-side, not trusted from client-declared
  values — same principle as the existing avatar upload flow.
- Ownership check on every client-web ticket endpoint (a user can only view/reply to
  their own tickets); admin endpoints are role-gated instead (not ownership-based —
  any admin can act on any ticket, per the no-assignment decision above).
- `CLOSED` tickets reject new replies with a clear "open a new ticket" message.
- Email-alert failure (provider down, bad config) never blocks ticket creation —
  logged and swallowed, not propagated as a request failure.

## Testing

**Backend:**
- Ticket creation + ownership enforcement (a user cannot fetch/reply to another
  user's ticket).
- Status-transition tests: `OPEN`→`IN_PROGRESS` on first admin reply,
  `RESOLVED`→`IN_PROGRESS` on user reply, `CLOSED` rejects new replies.
- Role-gating on admin endpoints: `SUPPORT`/`MODERATOR`/`SUPER_ADMIN` all permitted; a
  plain user token rejected.
- Email-alert failure (mocked `EmailService` throwing) does not block ticket
  creation.
- `TICKET_REPLIED` notification fires correctly through `sendNotification()`.

**Frontend** (type-check + lint + manual — no test framework in either repo):
- Client-web: submit a ticket, see the confirmation/reference number, see it listed
  under "My Tickets," receive a notification on admin reply, reply back, confirm a
  `RESOLVED` ticket reopens on reply and a `CLOSED` one does not accept a reply.
- Admin-web: ticket list, reply, status change; confirm a `SUPPORT`-role-only account
  can reach tickets but not other admin sections (role-gating check).
