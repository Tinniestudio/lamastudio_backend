# TinnieStudio Data Entity Blueprint (Revised)

---

# Core Domain Decisions

- Supports Movies + Series + Episodes
- Multiple trailers allowed
- Trailer can belong to Content OR Season
- Single native audio only
- Subtitle designed for multilingual expansion later
- Streaming only (No offline/download)
- No parental control / maturity restriction logic for MVP

---

# 1. Content Entity
Represents a Movie or Series

## Fields
- id: UUID
- title: String
- slug: String
- description: Text
- shortDescription: String
- type: ENUM(MOVIE, SERIES)
- status: ENUM(DRAFT, PROCESSING, PUBLISHED, ARCHIVED)
- releaseDate: LocalDate
- language: String
- country: String
- featured: Boolean
- posterUrl: String
- thumbnailUrl: String
- createdBy: UUID
- publishedAt: Instant (nullable)
- createdAt: Instant
- updatedAt: Instant

## Relationships
- Many Categories
- Many VideoAssets (Main Videos / Trailers for Movie)
- Many Seasons (If Series)

# 2. Season Entity
Only for Series

## Fields
- id: UUID
- contentId: UUID
- seasonNumber: Integer
- title: String
- description: Text
- releaseDate: LocalDate
- posterUrl: String
- thumbnailUrl: String
- createdAt: Instant
- updatedAt: Instant

## Relationships
- Belongs To Content
- Has Many Episodes
- Has Many Trailer VideoAssets

# 2. Season Entity
Only for Series

## Fields
- id: UUID
- contentId: UUID
- seasonNumber: Integer
- title: String
- description: Text
- releaseDate: LocalDate
- posterUrl: String
- thumbnailUrl: String
- createdAt: Instant
- updatedAt: Instant

## Relationships
- Belongs To Content
- Has Many Episodes
- Has Many Trailer VideoAssets

# 4. VideoAsset Entity
Unified Video Storage Model

## Purpose
Stores:
- Main Movie Video
- Episode Video
- Trailer Video

## Fields
- id: UUID
- contentId: UUID (nullable)
- seasonId: UUID (nullable)
- episodeId: UUID (nullable)

- assetType: ENUM(
    MAIN_VIDEO,
    TRAILER
  )

- originalFilename: String
- sourceFormat: ENUM(MP4, MOV, MKV)

- storageKey: String
- manifestUrl: String

- durationSeconds: Integer
- width: Integer
- height: Integer
- bitrate: Long
- codec: String
- fileSizeBytes: Long

- processingStatus: ENUM(
    PENDING,
    PROCESSING,
    READY,
    FAILED
  )

- uploadedBy: UUID
- createdAt: Instant
- updatedAt: Instant

## Rules
- MAIN_VIDEO:
  - Belongs to Content (Movie) OR Episode
- TRAILER:
  - Belongs to Content OR Season

# 5. VideoVariant Entity
Processed HLS Variants

## Fields
- id: UUID
- videoAssetId: UUID
- resolution: String
- width: Integer
- height: Integer
- bitrate: Long
- storageKey: String
- manifestUrl: String
- createdAt: Instant

# 6. Subtitle Entity

## Fields
- id: UUID
- videoAssetId: UUID
- languageCode: String
- label: String
- fileUrl: String
- format: ENUM(VTT, SRT)
- isDefault: Boolean
- createdAt: Instant

## Notes
- MVP: Only English Uploaded
- Future Ready For Multi-Language Expansion

# 7. Category Entity

## Fields
- id: UUID
- name: String
- slug: String
- description: String
- isActive: Boolean
- createdAt: Instant

# 8. ContentCategory Entity

## Fields
- contentId: UUID
- categoryId: UUID

# 9. SubscriptionPlan Entity

## Fields
- id: UUID
- name: String
- description: String
- price: Decimal
- currency: String
- billingCycle: ENUM(MONTHLY, YEARLY)
- maxDevices: Integer
- videoQuality: ENUM(SD, HD, FULL_HD)
- isActive: Boolean
- createdAt: Instant
- updatedAt: Instant

# 10. UserSubscription Entity

## Fields
- id: UUID
- userId: UUID
- planId: UUID
- status: ENUM(ACTIVE, EXPIRED, CANCELLED, PAST_DUE)
- startDate: Instant
- endDate: Instant
- autoRenew: Boolean
- createdAt: Instant
- updatedAt: Instant

# 11. WatchProgress Entity

## Fields
- id: UUID
- userId: UUID
- contentId: UUID (nullable)
- episodeId: UUID (nullable)
- progressSeconds: Integer
- durationSeconds: Integer
- completed: Boolean
- lastWatchedAt: Instant

# 12. Notification Entity

## Fields
- id: UUID
- userId: UUID
- type: ENUM(INFO, SUCCESS, WARNING)
- title: String
- message: Text
- read: Boolean
- createdAt: Instant

# 13. UploadSession Entity

## Fields
- id: UUID
- uploadType: ENUM(
    VIDEO,
    THUMBNAIL,
    SUBTITLE,
    TRAILER
  )

- targetEntityType: ENUM(
    CONTENT,
    SEASON,
    EPISODE
  )

- targetEntityId: UUID

- storageKey: String
- status: ENUM(
    PENDING,
    UPLOADING,
    COMPLETE,
    FAILED
  )

- uploadedBy: UUID
- expiresAt: Instant
- createdAt: Instant

## UploadSession

- id (UUID)
- userId
- uploadType (RAW_VIDEO / THUMBNAIL / SUBTITLE / TRAILER)
- targetEntityType (CONTENT / SEASON / EPISODE)
- targetEntityId (nullable until linked)
- storageKey
- originalFilename
- mimeType
- expectedMaxSizeBytes
- uploadStatus (PENDING / UPLOADING / COMPLETED / EXPIRED / FAILED)
- expiresAt
- completedAt
- createdAt