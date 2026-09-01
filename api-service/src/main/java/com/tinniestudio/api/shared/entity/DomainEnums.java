package com.tinniestudio.api.shared.entity;

/**
 * Centralized enum definitions for TinnieStudio domain.
 *
 * Production Note:
 * - Keeping enums in a single utility class helps organization for
 * smaller/medium projects.
 * - For larger enterprise systems, consider splitting into separate files per
 * enum.
 */
public final class DomainEnums {

    private DomainEnums() {
        throw new UnsupportedOperationException("Utility class");
    }

    public enum ContentType {
        MOVIE,
        SERIES
    }

    /**
     * Fixed, non-admin-editable — unlike ContentType itself (see the ContentType entity), this
     * is what upload/season logic actually branches on. Adding LIVE here later (once live
     * streaming is built) is a one-line addition; the content_types.structural_kind column is
     * string-backed, so no migration is needed when that happens.
     */
    public enum StructuralKind {
        SINGLE_VIDEO,
        MULTI_EPISODE
    }

    public enum ContentStatus {
        DRAFT,
        REVIEW,
        PROCESSING,
        PUBLISHED,
        REJECTED,
        ARCHIVED,
        // Terminal state set only by ContentService.delete() (soft-delete). Not reachable through
        // transitionStatus()/validateTransition() — moderation delete is a distinct action from
        // the normal DRAFT→REVIEW→...→PUBLISHED lifecycle, not a transition within it.
        DELETED
    }

    public enum MaturityRating {
        G,
        PG,
        PG_13,
        R,
        NOT_RATED
    }

    public enum SectionType {
        TRENDING,
        FEATURED,
        CONTINUE_WATCHING,
        CATEGORY,
        NEW_RELEASES,
        COMING_SOON
    }

    public enum VideoAssetType {
        MAIN_VIDEO,
        TRAILER
    }

    public enum SourceFormat {
        MP4,
        MOV,
        MKV
    }

    public enum ProcessingStatus {
        PENDING,
        PROCESSING,
        READY,
        FAILED
    }

    public enum SubtitleFormat {
        VTT,
        SRT
    }

    public enum BillingCycle {
        MONTHLY,
        YEARLY
    }

    public enum VideoQuality {
        SD,
        HD,
        FULL_HD
    }

    public enum SubscriptionStatus {
        ACTIVE,
        EXPIRED,
        CANCELLED,
        PAST_DUE
    }

    public enum NotificationType {
        INFO,
        SUCCESS,
        WARNING
    }

    public enum UploadType {
        VIDEO,
        THUMBNAIL,
        SUBTITLE,
        TRAILER,
        RAW_VIDEO,
        PARTNER_LOGO
    }

    public enum TargetEntityType {
        CONTENT,
        SEASON,
        EPISODE,
        // For UploadType.SUBTITLE: targetEntityId is the VideoAsset the subtitle track attaches
        // to, not a Content/Season/Episode id.
        VIDEO_ASSET
    }

    public enum UploadStatus {
        PENDING,
        UPLOADING,
        COMPLETED,
        EXPIRED,
        FAILED
    }

    public enum AuthProvider {
        LOCAL,
        GOOGLE
    }

    public enum AccountStatus {
        ACTIVE,
        SUSPENDED,
        BAN,
        DELETED
    }

    public enum DiscountType {
        PERCENTAGE,
        FIXED
    }

    public enum PaymentStatus {
        PENDING,
        SUCCESSFUL,
        FAILED,
        REFUNDED
    }

    public enum ReviewStatus {
        PENDING,
        APPROVED,
        REJECTED
    }

    public enum PartnerApplicationStatus {
        PENDING, APPROVED, REJECTED
    }

    public enum AppealStatus {
        PENDING, APPROVED, REJECTED
    }

    public enum NotificationEventType {
        CONTENT_PROCESSED,
        CONTENT_APPROVED,
        CONTENT_REJECTED,
        APPLICATION_APPROVED,
        APPLICATION_REJECTED,
        ACCOUNT_SUSPENDED,
        ACCOUNT_BANNED
    }

    public enum NotificationChannel {
        IN_APP, EMAIL
    }

}
