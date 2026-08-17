package com.tinniestudio.api.modules.partner.service;

import com.tinniestudio.api.modules.admin.service.AuditLogService;
import com.tinniestudio.api.modules.content.dto.ContentResponse;
import com.tinniestudio.api.modules.content.dto.CreateContentRequest;
import com.tinniestudio.api.modules.content.dto.UpdateContentRequest;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.content.service.ContentService;
import com.tinniestudio.api.modules.partner.dto.AdminUpdatePartnerProfileRequest;
import com.tinniestudio.api.modules.partner.dto.PartnerContentResponse;
import com.tinniestudio.api.modules.partner.dto.PartnerDashboardResponse;
import com.tinniestudio.api.modules.partner.dto.PartnerProfileResponse;
import com.tinniestudio.api.modules.partner.dto.UpdatePartnerProfileRequest;
import com.tinniestudio.api.modules.partner.repository.PartnerProfileRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.PartnerProfile;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartnerServiceTest {

    @Mock PartnerProfileRepository profileRepo;
    @Mock ContentRepository contentRepo;
    @Mock ContentService contentService;
    @Mock VideoAssetRepository videoAssetRepo;
    @Mock UploadSessionRepository uploadSessionRepo;
    @Mock AuditLogService auditLogService;
    @InjectMocks PartnerServiceImpl partnerService;

    private PartnerProfile makeProfile(UUID userId) {
        PartnerProfile p = new PartnerProfile();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setUserId(userId);
        p.setCompanyName("Acme");
        p.setIsVerified(true);
        p.setRevenueSharePercentage(BigDecimal.valueOf(70));
        return p;
    }

    @Test
    void getProfile_returnsPartnerProfile() {
        UUID userId = UUID.randomUUID();
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.of(makeProfile(userId)));

        PartnerProfileResponse result = partnerService.getProfile(userId);

        assertThat(result.companyName()).isEqualTo("Acme");
        assertThat(result.isVerified()).isTrue();
    }

    @Test
    void getProfile_notFound_throwsResourceNotFound() {
        UUID userId = UUID.randomUUID();
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> partnerService.getProfile(userId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateProfile_appliesNonNullFieldsOnly() {
        UUID userId = UUID.randomUUID();
        PartnerProfile profile = makeProfile(userId);
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdatePartnerProfileRequest req = new UpdatePartnerProfileRequest();
        req.setBio("We make great content");

        PartnerProfileResponse result = partnerService.updateProfile(userId, req);

        assertThat(profile.getBio()).isEqualTo("We make great content");
        assertThat(profile.getCompanyName()).isEqualTo("Acme"); // unchanged
    }

    @Test
    void updateProfile_setsLogoUrl_whenPresent() {
        // Logo upload no longer has its own endpoint/method — it goes through the standard
        // presigned-upload flow (uploadType=PARTNER_LOGO), and the resulting URL is set via
        // updateProfile() exactly like Content.posterUrl/thumbnailUrl.
        UUID userId = UUID.randomUUID();
        PartnerProfile profile = makeProfile(userId);
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        UpdatePartnerProfileRequest req = new UpdatePartnerProfileRequest();
        req.setLogoUrl("https://cdn.test/logo.jpg");

        PartnerProfileResponse result = partnerService.updateProfile(userId, req);

        assertThat(result.logoUrl()).isEqualTo("https://cdn.test/logo.jpg");
        assertThat(profile.getLogoUrl()).isEqualTo("https://cdn.test/logo.jpg");
    }

    @Test
    void getDashboard_returnsAggregatedPartnerStats() {
        UUID userId = UUID.randomUUID();
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.of(makeProfile(userId)));
        when(contentRepo.countByCreatedByAndStatus(userId, ContentStatus.PUBLISHED)).thenReturn(5L);
        when(contentRepo.countByCreatedByAndStatus(userId, ContentStatus.REVIEW)).thenReturn(2L);
        when(videoAssetRepo.countByContent_CreatedByAndProcessingStatus(userId, ProcessingStatus.PROCESSING)).thenReturn(1L);
        when(contentRepo.sumViewCountByCreatedBy(userId)).thenReturn(1000L);

        PartnerDashboardResponse result = partnerService.getDashboard(userId);

        assertThat(result.publishedContentCount()).isEqualTo(5L);
        assertThat(result.contentInReview()).isEqualTo(2L);
        assertThat(result.activeUploads()).isEqualTo(1L);
        assertThat(result.totalViewCount()).isEqualTo(1000L);
    }

    @Test
    void getDashboard_neverTouchesAuditLog() {
        // Regression guard for the audit_log leak: PartnerServiceImpl must not depend on
        // AuditLogRepository at all (CLAUDE.md Batch 13 #9 — audit_log must never surface
        // in a partner-facing response).
        boolean hasAuditLogRepoField = java.util.Arrays.stream(PartnerServiceImpl.class.getDeclaredFields())
            .anyMatch(f -> f.getType().getSimpleName().equals("AuditLogRepository"));
        assertThat(hasAuditLogRepoField).isFalse();
    }

    @Test
    void adminUpdateProfile_unverifiesPartnerAndLogsAudit() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        PartnerProfile profile = makeProfile(userId);
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        AdminUpdatePartnerProfileRequest req = new AdminUpdatePartnerProfileRequest();
        req.setIsVerified(false);

        PartnerProfileResponse result = partnerService.adminUpdateProfile(userId, req, adminId);

        assertThat(result.isVerified()).isFalse();
        assertThat(profile.getIsVerified()).isFalse();
        verify(auditLogService).log(
            eq("PARTNER_PROFILE_UPDATED_BY_ADMIN"), eq(adminId),
            eq("PARTNER_PROFILE"), eq(userId), isNull(), isNull()
        );
    }

    @Test
    void adminUpdateProfile_updatesRevenueShareOnly() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        PartnerProfile profile = makeProfile(userId);
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        AdminUpdatePartnerProfileRequest req = new AdminUpdatePartnerProfileRequest();
        req.setRevenueSharePercentage(BigDecimal.valueOf(55));

        PartnerProfileResponse result = partnerService.adminUpdateProfile(userId, req, adminId);

        assertThat(result.revenueSharePercentage()).isEqualByComparingTo(BigDecimal.valueOf(55));
        assertThat(profile.getIsVerified()).isTrue(); // unchanged
    }

    @Test
    void adminUpdateProfile_notFound_throwsResourceNotFound() {
        UUID userId = UUID.randomUUID();
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.empty());

        AdminUpdatePartnerProfileRequest req = new AdminUpdatePartnerProfileRequest();
        req.setIsVerified(false);

        assertThatThrownBy(() -> partnerService.adminUpdateProfile(userId, req, UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Content: merged listing + management (Batch 13 #7/#8/#10) ──────────────

    private Content makeContent(UUID id, UUID createdBy) {
        Content c = new Content();
        ReflectionTestUtils.setField(c, "id", id);
        c.setTitle("My Movie");
        c.setSlug("my-movie");
        c.setType(ContentType.MOVIE);
        c.setStatus(ContentStatus.DRAFT);
        c.setMaturityRating(MaturityRating.NOT_RATED);
        c.setCreatedBy(createdBy);
        c.setViewCount(0L);
        c.setFeatured(false);
        c.setComingSoon(false);
        c.setAverageRating(BigDecimal.ZERO);
        c.setReviewCount(0);
        return c;
    }

    @Test
    void listContents_scopesToCallingPartner() {
        UUID partnerId = UUID.randomUUID();
        Content owned = makeContent(UUID.randomUUID(), partnerId);
        when(contentRepo.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(java.util.List.of(owned)));

        Page<PartnerContentResponse> result = partnerService.listContents(
            partnerId, null, null, Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).title()).isEqualTo("My Movie");
    }

    @Test
    void createContent_delegatesToContentServiceWithCallerAsOwner() {
        UUID partnerId = UUID.randomUUID();
        CreateContentRequest req = new CreateContentRequest(
            "New Show", ContentType.SERIES, null, null, null, null, null, null, null);
        ContentResponse created = new ContentResponse(
            UUID.randomUUID(), "New Show", "new-show", null, null,
            "SERIES", "DRAFT", "NOT_RATED", null, null, null,
            false, false, 0L, null, null, null,
            BigDecimal.ZERO, 0, java.util.List.of(), null, null);
        when(contentService.create(req, partnerId)).thenReturn(created);

        PartnerContentResponse result = partnerService.createContent(partnerId, req);

        assertThat(result.title()).isEqualTo("New Show");
        verify(contentService).create(req, partnerId);
    }

    @Test
    void updateContent_ownedByCaller_delegatesToContentService() {
        UUID partnerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Content owned = makeContent(contentId, partnerId);
        when(contentRepo.findById(contentId)).thenReturn(Optional.of(owned));
        UpdateContentRequest req = new UpdateContentRequest(
            "Renamed", null, null, null, null, null, null, null, null, null, null, null);
        ContentResponse updated = new ContentResponse(
            contentId, "Renamed", "my-movie", null, null,
            "MOVIE", "DRAFT", "NOT_RATED", null, null, null,
            false, false, 0L, null, null, null,
            BigDecimal.ZERO, 0, java.util.List.of(), null, null);
        when(contentService.update(contentId, req)).thenReturn(updated);

        PartnerContentResponse result = partnerService.updateContent(partnerId, contentId, req);

        assertThat(result.title()).isEqualTo("Renamed");
    }

    @Test
    void updateContent_notOwnedByCaller_throws404_notFoundNot403() {
        // Enumeration-safe: a partner probing another partner's content id gets 404, the same
        // response as a genuinely nonexistent id — never a 403 that would confirm existence.
        UUID partnerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Content ownedBySomeoneElse = makeContent(contentId, UUID.randomUUID());
        when(contentRepo.findById(contentId)).thenReturn(Optional.of(ownedBySomeoneElse));
        UpdateContentRequest req = new UpdateContentRequest(
            "Hijacked", null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> partnerService.updateContent(partnerId, contentId, req))
            .isInstanceOf(ResponseStatusException.class)
            .extracting("status").isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        verify(contentService, never()).update(any(), any());
    }

    @Test
    void updateContent_contentNotFound_throws404() {
        UUID partnerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        when(contentRepo.findById(contentId)).thenReturn(Optional.empty());
        UpdateContentRequest req = new UpdateContentRequest(
            "X", null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> partnerService.updateContent(partnerId, contentId, req))
            .isInstanceOf(ResponseStatusException.class)
            .extracting("status").isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    void getContent_ownedByCaller_returnsIt() {
        UUID partnerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Content owned = makeContent(contentId, partnerId);
        when(contentRepo.findById(contentId)).thenReturn(Optional.of(owned));

        PartnerContentResponse result = partnerService.getContent(partnerId, contentId);

        assertThat(result.id()).isEqualTo(contentId);
    }

    @Test
    void getContent_notOwnedByCaller_throws404_notFoundNot403() {
        UUID partnerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        Content ownedBySomeoneElse = makeContent(contentId, UUID.randomUUID());
        when(contentRepo.findById(contentId)).thenReturn(Optional.of(ownedBySomeoneElse));

        assertThatThrownBy(() -> partnerService.getContent(partnerId, contentId))
            .isInstanceOf(ResponseStatusException.class)
            .extracting("status").isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    void getContent_contentNotFound_throws404() {
        UUID partnerId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        when(contentRepo.findById(contentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> partnerService.getContent(partnerId, contentId))
            .isInstanceOf(ResponseStatusException.class)
            .extracting("status").isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
    }
}
