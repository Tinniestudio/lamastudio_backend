package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.dto.RejectApplicationRequest;
import com.tinniestudio.api.modules.partner.dto.PartnerApplicationRequest;
import com.tinniestudio.api.modules.partner.repository.PartnerApplicationRepository;
import com.tinniestudio.api.modules.partner.service.PartnerPromotionService;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
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
    @Mock com.tinniestudio.api.modules.auth.service.AuthService authService;
    @InjectMocks PartnerApplicationServiceImpl applicationService;

    private PartnerApplication makePendingApp(UUID appId, UUID userId) {
        PartnerApplication app = new PartnerApplication();
        ReflectionTestUtils.setField(app, "id", appId);
        app.setUserId(userId);
        app.setCompanyName("Acme Corp");
        app.setStatus(PartnerApplicationStatus.PENDING);
        return app;
    }

    private org.springframework.security.core.userdetails.UserDetails principalFor(UUID userId) {
        org.springframework.security.core.userdetails.UserDetails principal =
            mock(org.springframework.security.core.userdetails.UserDetails.class);
        lenient().when(principal.getUsername()).thenReturn(userId.toString());
        return principal;
    }

    @Test
    void apply_authenticated_createsPendingApplication() {
        UUID userId = UUID.randomUUID();
        org.springframework.security.core.userdetails.UserDetails principal = principalFor(userId);
        when(applicationRepo.findByUserId(userId)).thenReturn(java.util.Optional.empty());
        when(applicationRepo.save(any())).thenAnswer(i -> {
            PartnerApplication saved = i.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("Acme Corp");
        req.setDescription("We make great content");

        PartnerApplicationResponse result = applicationService.apply(principal, req, mock(jakarta.servlet.http.HttpServletResponse.class));

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.companyName()).isEqualTo("Acme Corp");
        verify(authService, never()).register(any(), any());
    }

    @Test
    void apply_authenticated_alreadyPending_throwsBadRequest() {
        UUID userId = UUID.randomUUID();
        org.springframework.security.core.userdetails.UserDetails principal = principalFor(userId);
        PartnerApplication existing = makePendingApp(UUID.randomUUID(), userId);
        when(applicationRepo.findByUserId(userId)).thenReturn(java.util.Optional.of(existing));

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("Acme");

        assertThatThrownBy(() -> applicationService.apply(principal, req, mock(jakarta.servlet.http.HttpServletResponse.class)))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void apply_authenticated_alreadyApproved_throwsBadRequest() {
        UUID userId = UUID.randomUUID();
        org.springframework.security.core.userdetails.UserDetails principal = principalFor(userId);
        PartnerApplication existing = makePendingApp(UUID.randomUUID(), userId);
        existing.setStatus(PartnerApplicationStatus.APPROVED);
        when(applicationRepo.findByUserId(userId)).thenReturn(java.util.Optional.of(existing));

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("Acme");

        assertThatThrownBy(() -> applicationService.apply(principal, req, mock(jakarta.servlet.http.HttpServletResponse.class)))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("already a partner");
    }

    @Test
    void apply_authenticated_previouslyRejected_resetsAndReusesTheSameRow() {
        UUID userId = UUID.randomUUID();
        org.springframework.security.core.userdetails.UserDetails principal = principalFor(userId);
        UUID appId = UUID.randomUUID();
        PartnerApplication existing = makePendingApp(appId, userId);
        existing.setStatus(PartnerApplicationStatus.REJECTED);
        existing.setRejectionReason("Not enough detail");
        existing.setReviewedBy(UUID.randomUUID());
        existing.setReviewedAt(java.time.Instant.now());
        when(applicationRepo.findByUserId(userId)).thenReturn(java.util.Optional.of(existing));
        when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("Acme Corp Reapplied");

        PartnerApplicationResponse result = applicationService.apply(principal, req, mock(jakarta.servlet.http.HttpServletResponse.class));

        assertThat(result.id()).isEqualTo(appId);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.companyName()).isEqualTo("Acme Corp Reapplied");
        assertThat(result.rejectionReason()).isNull();
        assertThat(existing.getReviewedBy()).isNull();
        assertThat(existing.getReviewedAt()).isNull();
        verify(applicationRepo, times(1)).save(any());
    }

    @Test
    void apply_anonymous_createsAccountViaAuthServiceThenApplication() {
        UUID newUserId = UUID.randomUUID();
        var authResponse = com.tinniestudio.api.modules.auth.user.dto.AuthProfileResponse.builder()
            .userId(newUserId).email("new-partner@example.com").build();
        when(authService.register(any(), any())).thenReturn(authResponse);
        when(applicationRepo.findByUserId(newUserId)).thenReturn(java.util.Optional.empty());
        when(applicationRepo.save(any())).thenAnswer(i -> {
            PartnerApplication saved = i.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("New Co");
        req.setEmail("new-partner@example.com");
        req.setPassword("Str0ng!Pass");
        req.setFirstName("New");
        req.setLastName("Partner");

        PartnerApplicationResponse result = applicationService.apply(null, req, mock(jakarta.servlet.http.HttpServletResponse.class));

        assertThat(result.companyName()).isEqualTo("New Co");
        assertThat(result.userId()).isEqualTo(newUserId);
        verify(authService).register(argThat(r ->
            r.getEmail().equals("new-partner@example.com") && r.getPassword().equals("Str0ng!Pass")
        ), any());
    }

    @Test
    void apply_anonymous_duplicateEmail_bubblesUpEmailAlreadyExistsException() {
        when(authService.register(any(), any()))
            .thenThrow(new com.tinniestudio.api.modules.auth.exception.EmailAlreadyExistsException(
                "Email address is already registered"));

        PartnerApplicationRequest req = new PartnerApplicationRequest();
        req.setCompanyName("New Co");
        req.setEmail("existing@example.com");
        req.setPassword("Str0ng!Pass");
        req.setFirstName("New");
        req.setLastName("Partner");

        assertThatThrownBy(() -> applicationService.apply(null, req, mock(jakarta.servlet.http.HttpServletResponse.class)))
            .isInstanceOf(com.tinniestudio.api.modules.auth.exception.EmailAlreadyExistsException.class);

        verify(applicationRepo, never()).save(any());
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
    void reject_afterApproved_revokesPartnerRoleAndSetsRejected() {
        UUID appId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        PartnerApplication app = makePendingApp(appId, userId);
        app.setStatus(PartnerApplicationStatus.APPROVED); // previously approved

        when(applicationRepo.findById(appId)).thenReturn(Optional.of(app));
        when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        RejectApplicationRequest req = new RejectApplicationRequest();
        req.setReason("Violated content regulations");

        PartnerApplicationResponse result = applicationService.reject(appId, req, adminId);

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.rejectionReason()).isEqualTo("Violated content regulations");
        verify(partnerPromotionService).revokePartnerRole(userId);
        verify(auditLogService).log(
            eq("PARTNER_APPLICATION_REJECTED"), eq(adminId),
            eq("PARTNER_APPLICATION"), eq(appId),
            eq("Violated content regulations"), isNull()
        );
    }

    @Test
    void reject_fromPending_doesNotRevokePartnerRole() {
        UUID appId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        PartnerApplication app = makePendingApp(appId, userId); // still PENDING, never approved

        when(applicationRepo.findById(appId)).thenReturn(Optional.of(app));
        when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        RejectApplicationRequest req = new RejectApplicationRequest();
        req.setReason("Incomplete submission");

        applicationService.reject(appId, req, adminId);

        verify(partnerPromotionService, never()).revokePartnerRole(any());
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

    @Test
    void getByUserId_returnsApplication_whenOneExists() {
        UUID userId = UUID.randomUUID();
        PartnerApplication app = makePendingApp(UUID.randomUUID(), userId);
        when(applicationRepo.findByUserId(userId)).thenReturn(java.util.Optional.of(app));

        PartnerApplicationResponse result = applicationService.getByUserId(userId);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.userId()).isEqualTo(userId);
    }

    @Test
    void getByUserId_throwsResourceNotFound_whenNoneExists() {
        UUID userId = UUID.randomUUID();
        when(applicationRepo.findByUserId(userId)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> applicationService.getByUserId(userId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getByUserId_includesRejectionReason_whenRejected() {
        UUID userId = UUID.randomUUID();
        PartnerApplication app = makePendingApp(UUID.randomUUID(), userId);
        app.setStatus(PartnerApplicationStatus.REJECTED);
        app.setRejectionReason("Insufficient detail");
        when(applicationRepo.findByUserId(userId)).thenReturn(java.util.Optional.of(app));

        PartnerApplicationResponse result = applicationService.getByUserId(userId);

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.rejectionReason()).isEqualTo("Insufficient detail");
    }
}
