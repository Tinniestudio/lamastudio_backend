package com.tinniestudio.api.modules.partner.service;

import com.tinniestudio.api.modules.partner.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.UUID;

public interface PartnerService {
    PartnerProfileResponse getProfile(UUID userId);
    PartnerProfileResponse updateProfile(UUID userId, UpdatePartnerProfileRequest req);
    String uploadLogo(UUID userId, MultipartFile file) throws IOException;
    PartnerDashboardResponse getDashboard(UUID userId);
    Page<PartnerUploadSummaryResponse> getUploads(UUID userId, Pageable pageable);

    /**
     * Admin-only write path: toggle verification and/or revenue share on an existing
     * partner's profile (e.g. un-verify a partner who violates regulation, per Batch 13 #4).
     */
    PartnerProfileResponse adminUpdateProfile(UUID userId, AdminUpdatePartnerProfileRequest req, UUID adminId);
}
