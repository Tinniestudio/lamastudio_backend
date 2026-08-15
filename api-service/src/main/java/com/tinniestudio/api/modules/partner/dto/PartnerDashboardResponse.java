package com.tinniestudio.api.modules.partner.dto;

// Deliberately no audit_log-derived field here (e.g. recentActivity) — CLAUDE.md Batch 13 #9
// (clarified 2026-08-01): audit_log data must never appear in any partner-facing response/DTO,
// not even a partner's own actorId slice of it. Admin keeps its own AuditLogResponse/dashboard
// for that (see AdminDashboardServiceImpl).
public record PartnerDashboardResponse(
    long publishedContentCount,
    long contentInReview,
    long activeUploads,
    long totalViewCount
) {}
