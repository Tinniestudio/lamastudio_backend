package com.tinniestudio.api.modules.partner.dto;

import com.tinniestudio.api.modules.admin.dto.AuditLogResponse;
import java.util.List;

public record PartnerDashboardResponse(
    long publishedContentCount,
    long contentInReview,
    long activeUploads,
    long totalViewCount,
    List<AuditLogResponse> recentActivity
) {}
