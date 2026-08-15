package com.tinniestudio.api.modules.partner.service;

import com.tinniestudio.api.modules.content.dto.CreateContentRequest;
import com.tinniestudio.api.modules.content.dto.UpdateContentRequest;
import com.tinniestudio.api.modules.partner.dto.*;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

public interface PartnerService {
    PartnerProfileResponse getProfile(UUID userId);
    // Logo updates go through updateProfile() with logoUrl set (from the standard presigned-
    // upload flow, uploadType=PARTNER_LOGO) — no separate direct-multipart upload method.
    PartnerProfileResponse updateProfile(UUID userId, UpdatePartnerProfileRequest req);
    PartnerDashboardResponse getDashboard(UUID userId);
    Page<PartnerUploadSummaryResponse> getUploads(UUID userId, Pageable pageable);

    /**
     * Admin-only write path: toggle verification and/or revenue share on an existing
     * partner's profile (e.g. un-verify a partner who violates regulation, per Batch 13 #4).
     */
    PartnerProfileResponse adminUpdateProfile(UUID userId, AdminUpdatePartnerProfileRequest req, UUID adminId);

    // ── Content: merged listing + management (Batch 13 #7/#8/#10) ──────────────
    Page<PartnerContentResponse> listContents(UUID partnerId, ContentStatus status, String q, Pageable pageable);
    PartnerContentResponse createContent(UUID partnerId, CreateContentRequest req);
    PartnerContentResponse updateContent(UUID partnerId, UUID contentId, UpdateContentRequest req);
}
