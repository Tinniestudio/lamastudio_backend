package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AdminUserResponse;
import com.tinniestudio.api.modules.admin.dto.PromoteToPartnerRequest;
import com.tinniestudio.api.modules.admin.dto.UpdateUserStatusRequest;
import com.tinniestudio.api.modules.auth.user.service.SessionService;
import com.tinniestudio.api.modules.partner.dto.PartnerProfileResponse;
import com.tinniestudio.api.modules.partner.service.PartnerPromotionService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import com.tinniestudio.api.shared.entity.PartnerProfile;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock UserRepository userRepo;
    @Mock SessionService sessionService;
    @Mock AuditLogService auditLogService;
    @Mock PartnerPromotionService partnerPromotionService;
    @InjectMocks AdminUserServiceImpl adminUserService;

    private User makeUser(UUID id, AccountStatus status) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setEmail("test@example.com");
        u.setAccountStatus(status);
        u.setRoles(new HashSet<>());
        return u;
    }

    @Test
    void listUsers_returnsPagedUsers() {
        UUID id = UUID.randomUUID();
        when(userRepo.findByFilters(isNull(), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(makeUser(id, AccountStatus.ACTIVE))));

        var result = adminUserService.listUsers(null, null, Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).accountStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void updateStatus_suspended_revokesTokens() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User user = makeUser(userId, AccountStatus.ACTIVE);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(userRepo.save(any())).thenReturn(user);

        UpdateUserStatusRequest req = new UpdateUserStatusRequest();
        req.setStatus(AccountStatus.SUSPENDED);
        req.setReason("TOS violation");

        adminUserService.updateStatus(userId, req, adminId);

        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.SUSPENDED);
        verify(sessionService).revokeAllUserSessions(userId, adminId);
        verify(auditLogService).log(eq("USER_SUSPENDED"), eq(adminId), eq("USER"), eq(userId), eq("TOS violation"), isNull());
    }

    @Test
    void updateStatus_ban_revokesTokens() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User user = makeUser(userId, AccountStatus.ACTIVE);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(userRepo.save(any())).thenReturn(user);

        UpdateUserStatusRequest req = new UpdateUserStatusRequest();
        req.setStatus(AccountStatus.BAN);

        adminUserService.updateStatus(userId, req, adminId);

        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.BAN);
        verify(sessionService).revokeAllUserSessions(userId, adminId);
    }

    @Test
    void updateStatus_active_doesNotRevokeTokens() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User user = makeUser(userId, AccountStatus.SUSPENDED);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(userRepo.save(any())).thenReturn(user);

        UpdateUserStatusRequest req = new UpdateUserStatusRequest();
        req.setStatus(AccountStatus.ACTIVE);

        adminUserService.updateStatus(userId, req, adminId);

        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(sessionService, never()).revokeAllUserSessions(any(), any());
    }

    @Test
    void updateStatus_deleted_rejectedAsBadRequest() {
        // BUG 3: DELETED must not be settable via PATCH /admin/users/{id}/status — the only
        // correct path for deletion is DELETE /admin/users/{id} (AdminUserServiceImpl#softDelete),
        // which sets deletedAt AND is covered by TOKEN_REVOKE_STATUSES-equivalent handling.
        // Setting DELETED here previously flipped accountStatus without ever setting deletedAt
        // and without revoking sessions (DELETED was missing from TOKEN_REVOKE_STATUSES).
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        UpdateUserStatusRequest req = new UpdateUserStatusRequest();
        req.setStatus(AccountStatus.DELETED);

        assertThatThrownBy(() -> adminUserService.updateStatus(userId, req, adminId))
            .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(userRepo, sessionService, auditLogService);
    }

    @Test
    void softDelete_callsUserSoftDeleteAndLogs() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        User user = makeUser(userId, AccountStatus.ACTIVE);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(userRepo.save(any())).thenReturn(user);

        adminUserService.softDelete(userId, adminId);

        // User.softDelete() sets AccountStatus.DELETED
        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.DELETED);
        verify(auditLogService).log(eq("USER_DELETED"), eq(adminId), eq("USER"), eq(userId), isNull(), isNull());
    }

    @Test
    void getById_notFound_throwsException() {
        UUID userId = UUID.randomUUID();
        when(userRepo.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserService.getById(userId))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void promoteToPartner_delegatesToPromotionServiceAndLogsAudit() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        PartnerProfile profile = new PartnerProfile();
        profile.setUserId(userId);
        profile.setCompanyName("Acme");

        when(partnerPromotionService.grantPartnerRoleAndProfile(userId, "Acme", "https://acme.com"))
            .thenReturn(profile);

        PromoteToPartnerRequest req = new PromoteToPartnerRequest();
        req.setCompanyName("Acme");
        req.setWebsiteUrl("https://acme.com");

        PartnerProfileResponse result = adminUserService.promoteToPartner(userId, req, adminId);

        assertThat(result.companyName()).isEqualTo("Acme");
        verify(partnerPromotionService).grantPartnerRoleAndProfile(userId, "Acme", "https://acme.com");
        verify(auditLogService).log(eq("USER_PROMOTED_TO_PARTNER"), eq(adminId), eq("USER"), eq(userId), isNull(), isNull());
    }

    @Test
    void promoteToPartner_nullRequest_passesNullFields() {
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        PartnerProfile profile = new PartnerProfile();
        profile.setUserId(userId);

        when(partnerPromotionService.grantPartnerRoleAndProfile(userId, null, null))
            .thenReturn(profile);

        adminUserService.promoteToPartner(userId, null, adminId);

        verify(partnerPromotionService).grantPartnerRoleAndProfile(userId, null, null);
    }
}
