# TinnieStudio MVP API Specification

## Base URL

`/api/v1`

---

# Auth / Users

POST /auth/register
POST /auth/login
POST /auth/logout
POST /auth/refresh
POST /auth/verify-email
POST /auth/resend-verification
POST /auth/forgot-password
POST /auth/reset-password
GET /auth/me

---

# User Profile / Settings

GET /users/me
PATCH /users/me
PATCH /users/me/preferences
PATCH /users/me/notifications
PATCH /users/me/password

---

# Admin User Management

GET /admin/users
Query Params:
* page
* limit
* role
* status
* search

GET /admin/users/:id
PATCH /admin/users/:id
PATCH /admin/users/:id/status
DELETE /admin/users/:id

---

# Categories

GET /categories
Query Params:
* page
* limit
* search
* featured

GET /categories/:slug
POST /admin/categories
PATCH /admin/categories/:id
DELETE /admin/categories/:id

---

# Content Management

GET /contents
Query Params:
* page
* limit
* categorySlug
* type
* country
* language
* sort
* featured
* status

GET /contents/:slug
POST /partner/contents
PATCH /partner/contents/:id
DELETE /partner/contents/:id
PATCH /partner/contents/:id/publish
PATCH /partner/contents/:id/unpublish

---

# Episodes / Nested Content

GET /contents/:contentId/episodes
POST /partner/contents/:contentId/episodes
PATCH /partner/episodes/:id
DELETE /partner/episodes/:id

---

# Media Upload

POST /uploads/sessions
POST /uploads/:sessionId/complete
GET /uploads/:sessionId/status
POST /media/thumbnails
POST /media/subtitles
DELETE /media/subtitles/:id

---

# Playback / Streaming

GET /playback/:contentId/manifest
GET /playback/:contentId/access
POST /playback/progress
GET /playback/progress/:contentId
GET /playback/continue-watching

---

# Watch History

GET /history
DELETE /history/:id
DELETE /history

---

# Favorites / Bookmarks

GET /favorites
POST /favorites/:contentId
DELETE /favorites/:contentId

---

# Ratings / Reviews

GET /contents/:contentId/reviews
POST /contents/:contentId/reviews
PATCH /reviews/:id
DELETE /reviews/:id

---

# Discovery / Homepage

GET /discover/home
GET /discover/featured
GET /discover/trending
GET /discover/recommended
GET /discover/new-releases

---

# Search

GET /search
Query Params:
* q
* type
* category
* language
* country
* sort
* page
* limit

---

# Subscription / Billing

GET /subscriptions/plans
POST /subscriptions/checkout
GET /subscriptions/me
PATCH /subscriptions/cancel
POST /subscriptions/apply-coupon

---

# Partner / Creator Portal

GET /partner/dashboard
GET /partner/analytics
GET /partner/uploads
GET /partner/contents
GET /partner/revenue

---

# Admin Dashboard / Moderation

GET /admin/dashboard
GET /admin/analytics/platform
GET /admin/analytics/content
GET /admin/analytics/users
GET /admin/uploads/processing
PATCH /admin/contents/:id/approve
PATCH /admin/contents/:id/reject
PATCH /admin/contents/:id/feature

---

# Notifications

GET /notifications
PATCH /notifications/:id/read
PATCH /notifications/read-all

---

# Webhooks

POST /webhooks/payment
POST /webhooks/storage

---

# Suggested Query Standards

Common Pagination:

* page
* limit

Common Sorting:

* sortBy
* sortOrder

Common Filters:

* search
* status
* createdAtFrom
* createdAtTo

---

# Suggested Role Guards

* Public
* Authenticated User
* Partner
* Admin

---1

# Notes

* All list endpoints should support pagination.
* All admin endpoints require admin role.
* Partner endpoints require partner role and ownership validation.
* Playback endpoints must enforce subscription/content access rules.
* Upload session endpoints integrate with direct-to-bucket upload pipeline.
