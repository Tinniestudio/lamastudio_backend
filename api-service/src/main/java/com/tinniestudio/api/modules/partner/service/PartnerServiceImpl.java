package com.tinniestudio.api.modules.partner.service;

import com.tinniestudio.api.modules.admin.service.AuditLogService;
import com.tinniestudio.api.modules.content.dto.ContentResponse;
import com.tinniestudio.api.modules.content.dto.CreateContentRequest;
import com.tinniestudio.api.modules.content.dto.UpdateContentRequest;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.content.repository.ContentSpecifications;
import com.tinniestudio.api.modules.content.service.ContentService;
import com.tinniestudio.api.modules.partner.dto.*;
import com.tinniestudio.api.modules.partner.repository.PartnerProfileRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.entity.PartnerProfile;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PartnerServiceImpl implements PartnerService {

    private final PartnerProfileRepository profileRepo;
    private final ContentRepository contentRepo;
    private final ContentService contentService;
    private final VideoAssetRepository videoAssetRepo;
    private final UploadSessionRepository uploadSessionRepo;
    private final AuditLogService auditLogService;

    private PartnerProfile requireProfile(UUID userId) {
        return profileRepo.findByUserId(userId)
            .orElseThrow(() -> new ResourceNotFoundException("Partner profile not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public PartnerProfileResponse getProfile(UUID userId) {
        return PartnerProfileResponse.from(requireProfile(userId));
    }

    @Override
    @Transactional
    public PartnerProfileResponse updateProfile(UUID userId, UpdatePartnerProfileRequest req) {
        PartnerProfile profile = requireProfile(userId);
        if (req.getCompanyName() != null) profile.setCompanyName(req.getCompanyName());
        if (req.getWebsiteUrl() != null) profile.setWebsiteUrl(req.getWebsiteUrl());
        if (req.getBio() != null) profile.setBio(req.getBio());
        // Set after the caller completes a presigned upload (uploadType=PARTNER_LOGO) — same
        // pattern as Content.posterUrl/thumbnailUrl. No separate multipart upload path.
        if (req.getLogoUrl() != null) profile.setLogoUrl(req.getLogoUrl());
        return PartnerProfileResponse.from(profileRepo.save(profile));
    }

    @Override
    @Transactional(readOnly = true)
    public PartnerDashboardResponse getDashboard(UUID userId) {
        requireProfile(userId);
        long published  = contentRepo.countByCreatedByAndStatus(userId, ContentStatus.PUBLISHED);
        long inReview   = contentRepo.countByCreatedByAndStatus(userId, ContentStatus.REVIEW);
        long processing = videoAssetRepo.countByContent_CreatedByAndProcessingStatus(userId, ProcessingStatus.PROCESSING);
        Long viewsLong  = contentRepo.sumViewCountByCreatedBy(userId);
        long totalViews = viewsLong != null ? viewsLong : 0L;

        return new PartnerDashboardResponse(published, inReview, processing, totalViews);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PartnerUploadSummaryResponse> getUploads(UUID userId, Pageable pageable) {
        return uploadSessionRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable)
            .map(PartnerUploadSummaryResponse::from);
    }

    @Override
    @Transactional
    public PartnerProfileResponse adminUpdateProfile(UUID userId, AdminUpdatePartnerProfileRequest req, UUID adminId) {
        PartnerProfile profile = requireProfile(userId);
        if (req.getIsVerified() != null) profile.setIsVerified(req.getIsVerified());
        if (req.getRevenueSharePercentage() != null) profile.setRevenueSharePercentage(req.getRevenueSharePercentage());
        PartnerProfileResponse response = PartnerProfileResponse.from(profileRepo.save(profile));
        auditLogService.log("PARTNER_PROFILE_UPDATED_BY_ADMIN", adminId, "PARTNER_PROFILE", userId, null, null);
        return response;
    }

    // -----------------------------------------------------------------------
    // Content — merged listing + management (CLAUDE.md Batch 13 #7/#8/#10):
    // filter/search/sort via one flexible GET, POST to create, PATCH to update, always scoped to
    // the calling partner's own content, mapped through the partner-only PartnerContentResponse.
    // -----------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<PartnerContentResponse> listContents(UUID partnerId, ContentStatus status, String q, Pageable pageable) {
        Specification<Content> spec = ContentSpecifications.hasCreatedBy(partnerId)
            .and(ContentSpecifications.hasStatus(status))
            .and(ContentSpecifications.titleContains(q));
        return contentRepo.findAll(spec, pageable).map(PartnerContentResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public PartnerContentResponse getContent(UUID partnerId, UUID contentId) {
        return PartnerContentResponse.from(requireOwnedContent(partnerId, contentId));
    }

    @Override
    @Transactional
    public PartnerContentResponse createContent(UUID partnerId, CreateContentRequest req) {
        ContentResponse created = contentService.create(req, partnerId);
        return PartnerContentResponse.from(created);
    }

    @Override
    @Transactional
    public PartnerContentResponse updateContent(UUID partnerId, UUID contentId, UpdateContentRequest req) {
        requireOwnedContent(partnerId, contentId);
        ContentResponse updated = contentService.update(contentId, req);
        return PartnerContentResponse.from(updated);
    }

    /**
     * 404 (not 403) on a content id the caller doesn't own — same enumeration-safe pattern as
     * AdminContentController.assertOwnedByCallerOrAdmin and AnalyticsServiceImpl's ownership
     * checks: a partner probing other partners' content ids can't distinguish "doesn't exist"
     * from "exists but isn't yours".
     */
    private Content requireOwnedContent(UUID partnerId, UUID contentId) {
        Content content = contentRepo.findById(contentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + contentId));
        if (!partnerId.equals(content.getCreatedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + contentId);
        }
        return content;
    }
}
