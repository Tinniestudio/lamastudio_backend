````markdown
# TinnieStudio Media Pipeline Technical Architecture Specification

---

## Document Purpose

This document defines the technical architecture for TinnieStudio's media upload, processing, storage, and streaming pipeline.

It serves as the implementation reference for backend, worker, and infrastructure development.

---

# 1. Architecture Overview

## Runtime Topology

```text
Client/Admin
   ↓
Spring Boot Backend API
   ↓
PostgreSQL + RabbitMQ + Object Storage
   ↓
Media Worker(s)
   ↓
CDN
   ↓
Streaming Client Playback
````

---

# 2. Core Responsibilities

## Backend API Responsibilities

* Authentication / Authorization
* Upload Session Management
* Signed Upload URL Generation
* Upload Verification
* Content / VideoAsset Creation
* Queue Publishing
* Playback Metadata Delivery
* Admin / CMS Management

---

## Worker Responsibilities

* Consume Media Processing Jobs
* Download Raw Uploaded Video
* Validate Uploaded Media
* Extract Metadata via FFprobe
* Generate Resolution Ladder
* Run FFmpeg Transcoding Pipeline
* Upload Processed HLS Assets
* Persist Processed Metadata / Variants
* Update Processing Status

---

## CDN Responsibilities

* Cache and Deliver HLS Assets
* Cache and Deliver Subtitles
* Cache and Deliver Thumbnails

---

# 3. Upload Architecture

## Direct-to-Bucket Upload Flow

```text
1. Authenticated User Requests Upload Session
2. Backend Validates User Permission
3. Backend Creates UploadSession Record
4. Backend Generates Presigned Upload URL
5. Client Uploads Directly To Storage Bucket
6. Client Calls Upload Completion Endpoint
7. Backend Verifies Uploaded Object Exists
8. Backend Creates VideoAsset
9. Backend Publishes Media Processing Job
```

---

# 4. Upload Security Model

## Authentication Flow

```text
JWT Cookie Authentication
   ↓
Protected Upload Session Endpoint
   ↓
Authorization Validation
   ↓
Presigned Upload Authorization Generated
```

---

## Security Rules

* Upload URLs MUST be short-lived (5–15 min)
* Upload URLs MUST be restricted to specific storage key
* Upload URLs MUST enforce MIME type restrictions
* Upload URLs MUST enforce size restrictions
* Clients MUST NOT receive permanent bucket credentials

---

# 5. Storage Structure

```text
/raw/
   {uploadSessionId}/original.mp4

/processed/
   {videoAssetId}/
      master.m3u8
      1080/
      720/
      480/
      360/

/subtitles/
   {videoAssetId}/en.vtt

/thumbnails/
   {contentId}/poster.jpg
```

---

# 6. Upload Session Entity

## UploadSession

| Field                | Type    | Description                                |
| -------------------- | ------- | ------------------------------------------ |
| id                   | UUID    | Upload session identifier                  |
| userId               | UUID    | Uploader                                   |
| uploadType           | ENUM    | RAW_VIDEO / THUMBNAIL / SUBTITLE / TRAILER |
| targetEntityType     | ENUM    | CONTENT / SEASON / EPISODE                 |
| targetEntityId       | UUID    | Related domain entity                      |
| storageKey           | String  | Bucket key                                 |
| originalFilename     | String  | Client filename                            |
| mimeType             | String  | Uploaded MIME type                         |
| expectedMaxSizeBytes | Long    | Upload limit                               |
| uploadStatus         | ENUM    | PENDING / COMPLETED / FAILED / EXPIRED     |
| expiresAt            | Instant | Expiry timestamp                           |
| completedAt          | Instant | Completion timestamp                       |
| createdAt            | Instant | Audit timestamp                            |

---

# 7. Queue Architecture

## Queue Technology

Recommended: **RabbitMQ**

---

## Queue Names

```text
media.video.process
notification.email.send
analytics.event.ingest
```

---

## Media Processing Job Schema

```json
{
  "jobId": "uuid",
  "videoAssetId": "uuid",
  "uploadSessionId": "uuid",
  "rawStorageKey": "raw/upload-123/original.mp4",
  "requestedBy": "uuid",
  "requestedAt": "timestamp",
  "attempt": 1
}
```

---

# 8. Worker-Service Architecture

## Worker Deployment Model

```text
Spring Boot API App
    ↓ Publishes Jobs
RabbitMQ Queue
    ↓ Consumed By
Media Worker App(s)
```

---

## Worker Shared Infrastructure Access

Worker has direct access to:

* PostgreSQL
* RabbitMQ
* Object Storage

---

# 9. Worker Processing Lifecycle

## Processing State Machine

### VideoAsset.processingStatus

```text
PENDING
QUEUED
PROCESSING
UPLOADING_OUTPUT
READY
FAILED
```

---

## Internal Worker Stage Tracking

```text
VALIDATING
DOWNLOADING
TRANSCODING
UPLOADING
FINALIZING
```

---

# 10. Worker Temp Storage Lifecycle

## Workspace Pattern

```text
/tmp/tinniestudio/jobs/{jobId}/
```

---

## Temp Directory Rules

* Create per job
* Delete after success
* Delete after failure
* Scheduled stale cleanup required

---

# 11. FFprobe Validation

## Metadata Extracted

* Duration
* Width / Height
* Codec
* Bitrate
* FPS
* Container Format
* Audio Presence

---

## Validation Rules

* Allowed Containers: MP4 / MOV / MKV
* Must Have Video Stream
* Must Have Audio Stream
* Must Not Exceed Max Duration
* Must Not Exceed Max File Size

---

# 12. Resolution Ladder Strategy

## Dynamic Resolution Generation

```text
If Source >= 1080p:
  Generate 1080 / 720 / 480 / 360

If Source >= 720p:
  Generate 720 / 480 / 360

If Source >= 480p:
  Generate 480 / 360
```

---

## Rule

**Never Upscale Source Video**

---

# 13. HLS Packaging Strategy

## Output Format

```text
Adaptive HLS Streaming Package
```

---

## Generated Output

```text
master.m3u8
1080p.m3u8
720p.m3u8
480p.m3u8
360p.m3u8
segments/*.ts
```

---

## HLS Settings

| Setting          | Value   |
| ---------------- | ------- |
| Segment Duration | 6s      |
| Playlist Type    | VOD     |
| Segment Format   | MPEG-TS |
| Audio Codec      | AAC     |
| Video Codec      | H.264   |

---

# 14. Bitrate Ladder

| Resolution | Video Bitrate | Audio Bitrate |
| ---------- | ------------- | ------------- |
| 1080p      | 5000k         | 192k          |
| 720p       | 2800k         | 128k          |
| 480p       | 1400k         | 128k          |
| 360p       | 800k          | 96k           |

---

# 15. Processed Asset Persistence

## VideoAsset

Stores:

* Raw Storage Key
* Manifest Key
* Duration
* Width / Height
* Codec
* Processing Status

---

## VideoVariant

Stores per generated resolution:

* Resolution
* Bitrate
* Width / Height
* Manifest Key
* Segment Metadata

---

# 16. CDN Delivery Model

## Delivery Path

```text
Client
   ↓
CDN
   ↓
Object Storage
```

---

## Database Storage Strategy

Store:

```text
manifestKey = processed/{videoAssetId}/master.m3u8
```

Do NOT store full CDN URL.

---

## Playback URL Construction

```text
playbackUrl = cdnBaseUrl + "/" + manifestKey
```

---

# 17. Playback Flow

```text
1. Client Requests Playback Metadata
2. Backend Validates Subscription / Access
3. Backend Loads VideoAsset
4. Backend Ensures Status = READY
5. Backend Returns Manifest URL + Subtitles
```

---

# 18. Subtitle Handling

## Subtitle Upload

Manual user upload.

---

## Backend Responsibility

* Validate subtitle file format
* Persist subtitle metadata
* Return subtitle URLs in playback DTO

---

## Player Responsibility

* Synchronize subtitle timing automatically

---

# 19. Retry / Failure Strategy

## Retryable Failures

* Network Errors
* Storage Upload Failures
* Worker Crashes
* Temporary Infrastructure Issues

---

## Non-Retryable Failures

* Corrupt File
* Invalid Codec
* Unsupported Format
* Validation Failures

---

## Retry Policy

```text
Max Attempts: 3
Backoff Strategy:
  Attempt 2 → 1 min
  Attempt 3 → 5 min
Dead Letter Queue After Final Failure
```

---

# 20. Infrastructure Recommendations

## MVP Deployment

### App Server

* Spring Boot API
* PostgreSQL
* RabbitMQ

---

### Worker Server

* Spring Boot Worker App
* FFmpeg Installed

---

### Storage

* AWS S3 / Cloudflare R2

---

### CDN

* Cloudflare CDN / Bunny CDN

---

# 21. Future Enhancements

* Signed CDN Playback URLs
* GPU Hardware Encoding
* Multi-Language Subtitles
* Audio Track Support
* Downloadable Offline Assets
* Bucket Event Webhook Upload Completion
* Event-Driven Worker Completion Notifications

---

# 22. Implementation Order

```text
1. UploadSession Module
2. Storage Signing Service
3. Upload Completion Verification
4. VideoAsset Creation Flow
5. Queue Publishing
6. Worker Foundation
7. FFmpeg Pipeline
8. Processed Asset Persistence
9. Playback API
10. Admin Monitoring / Status UI
```

---

# 23. Architectural Principles

* Backend coordinates workflow
* Worker handles heavy media processing
* Queue provides async decoupling
* Storage stores immutable media assets
* CDN handles delivery optimization
* Database remains source of truth

---

# End of Specification

```
```
