package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.dto.RejectApplicationRequest;
import com.tinniestudio.api.modules.partner.dto.PartnerApplicationRequest;
import com.tinniestudio.api.modules.partner.repository.PartnerApplicationRepository;
import com.tinniestudio.api.modules.partner.repository.PartnerProfileRepository;
import com.tinniestudio.api.modules.role.repository.RoleRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.*;
import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import com.tinniestudio.api.shared.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PartnerApplicationServiceTest {

    @Mock PartnerApplicationRepository applicationRepo;
    @Mock PartnerProfileRepository profileRepo;
    @Mock UserRepository userRepo;
    @Mock RoleRepository roleRepo;
    @Mock AuditLogService auditLogService;
    @InjectMocks PartnerApplicationServiceImpl applicationService;

    private User makeUser(UUID id) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setEmail("user@test.com");
        u.setRoles(new HashSet<>());
        return u;
    }

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
    void approve_assignsPartnerRoleAndCreatesProfile() {
        UUID appId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User user = makeUser(userId);
        PartnerApplication app = makePendingApp(appId, userId);
        app.setCompanyName("Acme");

        Role partnerRole = new Role(RoleName.ROLE_PARTNER);

        when(applicationRepo.findById(appId)).thenReturn(Optional.of(app));
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepo.findByName(RoleName.ROLE_PARTNER)).thenReturn(Optional.of(partnerRole));
        when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepo.save(any())).thenReturn(user);
        when(profileRepo.existsByUserId(userId)).thenReturn(false);
        when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PartnerApplicationResponse result = applicationService.approve(appId, adminId);

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(app.getReviewedBy()).isEqualTo(adminId);
        verify(profileRepo).save(any());
        verify(auditLogService).log(
            eq("PARTNER_APPLICATION_APPROVED"), eq(adminId),
            eq("PARTNER_APPLICATION"), eq(appId),
            isNull(), isNull()
        );
    }

    @Test
    void approve_profileAlreadyExists_doesNotCreateDuplicate() {
        UUID appId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        PartnerApplication app = makePendingApp(appId, userId);
        User user = makeUser(userId);
        Role partnerRole = new Role(RoleName.ROLE_PARTNER);

        when(applicationRepo.findById(appId)).thenReturn(Optional.of(app));
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(roleRepo.findByName(RoleName.ROLE_PARTNER)).thenReturn(Optional.of(partnerRole));
        when(applicationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepo.save(any())).thenReturn(user);
        when(profileRepo.existsByUserId(userId)).thenReturn(true); // already exists

        applicationService.approve(appId, adminId);

        verify(profileRepo, never()).save(any()); // must not create duplicate
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
