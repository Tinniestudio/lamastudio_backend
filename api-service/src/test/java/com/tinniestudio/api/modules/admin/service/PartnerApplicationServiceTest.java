package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.dto.RejectApplicationRequest;
import com.tinniestudio.api.modules.partner.dto.PartnerApplicationRequest;
import com.tinniestudio.api.modules.partner.repository.PartnerApplicationRepository;
import com.tinniestudio.api.modules.partner.service.PartnerPromotionService;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import com.tinniestudio.api.shared.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartnerApplicationServiceTest {

    @Mock PartnerApplicationRepository applicationRepo;
    @Mock PartnerPromotionService partnerPromotionService;
    @Mock AuditLogService auditLogService;
    @InjectMocks PartnerApplicationServiceImpl applicationService;

    private PartnerApplication makePendingApp(UUID appId, UUID userId) {
        PartnerApplication app = new PartnerApplication();
        ReflectionTestUtils.setField(app, "id", appId);
        app.setUserId(userId);
        app.setCompanyName("Acme Corp");
        app.setStatus(PartnerApplicationStatus.PENDING);
        return app;
    }

    @Test
    void apply_createsPendingApplication() {
        UUID userId = UUID.randomUUID();
        when(applicationRepo.existsByUserIdAndStatus(userId, PartnerApplicationStatus.PENDING)).thenReturn(false);
        when(applicationRepo.save(any())).thenAnswer(i -> {
            PartnerApplication saved = i.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("Acme Corp");
        req.setDescription("We make great content");

        PartnerApplicationResponse result = applicationService.apply(userId, req);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.companyName()).isEqualTo("Acme Corp");
    }

    @Test
    void apply_alreadyPending_throwsBadRequest() {
        UUID userId = UUID.randomUUID();
        when(applicationRepo.existsByUserIdAndStatus(userId, PartnerApplicationStatus.PENDING)).thenReturn(true);

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("Acme");

        assertThatThrownBy(() -> applicationService.apply(userId, req))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void approve_delegatesRoleAndProfileCreationToPromotionService() {
        UUID appId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        PartnerApplication app = makePendingApp(appId, userId);
        app.setCompanyName("Acme");
        app.setWebsiteUrl("https://acme.com");

        when(applicationRepo.findById(appId)).thenReturn(Optional.of(app));
        when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(partnerPromotionService.grantPartnerRoleAndProfile(userId, "Acme", "https://acme.com"))
            .thenReturn(new PartnerProfile());

        PartnerApplicationResponse result = applicationService.approve(appId, adminId);

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(app.getReviewedBy()).isEqualTo(adminId);
        verify(partnerPromotionService).grantPartnerRoleAndProfile(userId, "Acme", "https://acme.com");
        verify(auditLogService).log(
            eq("PARTNER_APPLICATION_APPROVED"), eq(adminId),
            eq("PARTNER_APPLICATION"), eq(appId),
            isNull(), isNull()
        );
    }

    @Test
    void approve_alreadyReviewed_throwsBadRequestAndDoesNotReRunSideEffects() {
        UUID appId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        PartnerApplication app = makePendingApp(appId, UUID.randomUUID());
        app.setStatus(PartnerApplicationStatus.APPROVED); // already reviewed

        when(applicationRepo.findById(appId)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> applicationService.approve(appId, adminId))
            .isInstanceOf(BadRequestException.class);

        verify(partnerPromotionService, never()).grantPartnerRoleAndProfile(any(), any(), any());
        verify(auditLogService, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void reject_alreadyReviewed_throwsBadRequestAndDoesNotReRunSideEffects() {
        UUID appId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        PartnerApplication app = makePendingApp(appId, UUID.randomUUID());
        app.setStatus(PartnerApplicationStatus.REJECTED); // already reviewed

        when(applicationRepo.findById(appId)).thenReturn(Optional.of(app));

        RejectApplicationRequest req = new RejectApplicationRequest();
        req.setReason("second attempt");

        assertThatThrownBy(() -> applicationService.reject(appId, req, adminId))
            .isInstanceOf(BadRequestException.class);

        verify(auditLogService, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void reject_setsRejectedStatusWithReason() {
        UUID appId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        PartnerApplication app = makePendingApp(appId, UUID.randomUUID());

        when(applicationRepo.findById(appId)).thenReturn(Optional.of(app));
        when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        RejectApplicationRequest req = new RejectApplicationRequest();
        req.setReason("Incomplete submission");

        PartnerApplicationResponse result = applicationService.reject(appId, req, adminId);

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.rejectionReason()).isEqualTo("Incomplete submission");
        assertThat(app.getReviewedBy()).isEqualTo(adminId);
        verify(auditLogService).log(
            eq("PARTNER_APPLICATION_REJECTED"), eq(adminId),
            eq("PARTNER_APPLICATION"), eq(appId),
            eq("Incomplete submission"), isNull()
        );
    }
}
