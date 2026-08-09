package com.tinniestudio.api.modules.partner.service;

import com.tinniestudio.api.modules.admin.repository.AuditLogRepository;
import com.tinniestudio.api.modules.admin.service.AuditLogService;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.partner.dto.AdminUpdatePartnerProfileRequest;
import com.tinniestudio.api.modules.partner.dto.PartnerDashboardResponse;
import com.tinniestudio.api.modules.partner.dto.PartnerProfileResponse;
import com.tinniestudio.api.modules.partner.dto.UpdatePartnerProfileRequest;
import com.tinniestudio.api.modules.partner.repository.PartnerProfileRepository;
import com.tinniestudio.api.modules.upload.repository.UploadSessionRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.ProcessingStatus;
import com.tinniestudio.api.shared.entity.PartnerProfile;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import com.tinniestudio.api.shared.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartnerServiceTest {

    @Mock PartnerProfileRepository profileRepo;
    @Mock ContentRepository contentRepo;
    @Mock VideoAssetRepository videoAssetRepo;
    @Mock AuditLogRepository auditLogRepo;
    @Mock StorageService storageService;
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
    void uploadLogo_storesFileAndUpdatesProfile() throws Exception {
        UUID userId = UUID.randomUUID();
        PartnerProfile profile = makeProfile(userId);
        when(profileRepo.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(storageService.uploadFile(contains("partner-logos"), any(byte[].class), anyString()))
            .thenReturn("https://cdn.test/logo.jpg");
        when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "logo.jpg", "image/jpeg", new byte[]{1, 2, 3});
        String url = partnerService.uploadLogo(userId, file);

        assertThat(url).isEqualTo("https://cdn.test/logo.jpg");
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
        when(auditLogRepo.findByActorIdOrderByCreatedAtDesc(eq(userId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        PartnerDashboardResponse result = partnerService.getDashboard(userId);

        assertThat(result.publishedContentCount()).isEqualTo(5L);
        assertThat(result.contentInReview()).isEqualTo(2L);
        assertThat(result.activeUploads()).isEqualTo(1L);
        assertThat(result.totalViewCount()).isEqualTo(1000L);
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
}
