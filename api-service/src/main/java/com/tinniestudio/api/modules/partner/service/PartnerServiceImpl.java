package com.tinniestudio.api.modules.partner.service;

import com.tinniestudio.api.modules.admin.dto.AuditLogResponse;
import com.tinniestudio.api.modules.admin.repository.AuditLogRepository;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.partner.dto.*;
import com.tinniestudio.api.modules.partner.repository.PartnerProfileRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.*;
import com.tinniestudio.api.shared.entity.PartnerProfile;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import com.tinniestudio.api.shared.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PartnerServiceImpl implements PartnerService {

    private final PartnerProfileRepository profileRepo;
    private final ContentRepository contentRepo;
    private final VideoAssetRepository videoAssetRepo;
    private final AuditLogRepository auditLogRepo;
    private final StorageService storageService;
    private final UploadSessionRepository uploadSessionRepo;

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
        return PartnerProfileResponse.from(profileRepo.save(profile));
    }

    @Override
    @Transactional
    public String uploadLogo(UUID userId, MultipartFile file) throws IOException {
        PartnerProfile profile = requireProfile(userId);
        String ext = StringUtils.getFilenameExtension(file.getOriginalFilename());
        String key = "partner-logos/" + profile.getId() + "/logo." + (ext != null ? ext : "bin");
        String url = storageService.uploadFile(key, file.getBytes(), file.getContentType());
        profile.setLogoUrl(url);
        profileRepo.save(profile);
        return url;
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

        List<AuditLogResponse> activity = auditLogRepo
            .findByActorIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 10))
            .map(AuditLogResponse::from)
            .getContent();

        return new PartnerDashboardResponse(published, inReview, processing, totalViews, activity);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PartnerUploadSummaryResponse> getUploads(UUID userId, Pageable pageable) {
        return uploadSessionRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable)
            .map(PartnerUploadSummaryResponse::from);
    }
}
