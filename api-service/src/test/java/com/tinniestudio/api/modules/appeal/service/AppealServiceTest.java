package com.tinniestudio.api.modules.appeal.service;

import com.tinniestudio.api.modules.admin.dto.RejectAppealRequest;
import com.tinniestudio.api.modules.admin.dto.UpdateUserStatusRequest;
import com.tinniestudio.api.modules.admin.service.AdminUserService;
import com.tinniestudio.api.modules.admin.service.AuditLogService;
import com.tinniestudio.api.modules.appeal.dto.AppealResponse;
import com.tinniestudio.api.modules.appeal.dto.SubmitAppealRequest;
import com.tinniestudio.api.modules.appeal.repository.AccountAppealRepository;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.AccountAppeal;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.AppealStatus;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class AppealServiceTest {

    @Mock AccountAppealRepository appealRepo;
    @Mock UserRepository userRepo;
    @Mock AdminUserService adminUserService;
    @Mock AuditLogService auditLogService;
    @InjectMocks AppealServiceImpl appealService;

    private User makeUser(UUID id, AccountStatus status) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setEmail("user@test.com");
        u.setAccountStatus(status);
        return u;
    }

    private AccountAppeal makePendingAppeal(UUID appealId, UUID userId) {
        AccountAppeal appeal = new AccountAppeal();
        ReflectionTestUtils.setField(appeal, "id", appealId);
        appeal.setUserId(userId);
        appeal.setReason("I was wrongly suspended");
        appeal.setStatus(AppealStatus.PENDING);
        return appeal;
    }

    @Test
    void submit_suspendedUser_createsPendingAppeal() {
        UUID userId = UUID.randomUUID();
        User user = makeUser(userId, AccountStatus.SUSPENDED);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(appealRepo.existsByUserIdAndStatus(userId, AppealStatus.PENDING)).thenReturn(false);
        when(appealRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        SubmitAppealRequest req = new SubmitAppealRequest();
        req.setReason("I was wrongly suspended");

        AppealResponse result = appealService.submit(userId, req);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.reason()).isEqualTo("I was wrongly suspended");
    }

    @Test
    void submit_activeUser_throwsBadRequest() {
        UUID userId = UUID.randomUUID();
        User user = makeUser(userId, AccountStatus.ACTIVE);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));

        SubmitAppealRequest req = new SubmitAppealRequest();
        req.setReason("Please review");

        assertThatThrownBy(() -> appealService.submit(userId, req))
            .isInstanceOf(BadRequestException.class);
        verify(appealRepo, never()).save(any());
    }

    @Test
    void submit_bannedUser_throwsBadRequest() {
        UUID userId = UUID.randomUUID();
        User user = makeUser(userId, AccountStatus.BAN);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));

        SubmitAppealRequest req = new SubmitAppealRequest();
        req.setReason("Please review");

        assertThatThrownBy(() -> appealService.submit(userId, req))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void submit_alreadyPendingAppeal_throwsBadRequest() {
        UUID userId = UUID.randomUUID();
        User user = makeUser(userId, AccountStatus.SUSPENDED);
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
        when(appealRepo.existsByUserIdAndStatus(userId, AppealStatus.PENDING)).thenReturn(true);

        SubmitAppealRequest req = new SubmitAppealRequest();
        req.setReason("Please review again");

        assertThatThrownBy(() -> appealService.submit(userId, req))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void approve_reactivatesAccountAndLogsAudit() {
        UUID appealId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        AccountAppeal appeal = makePendingAppeal(appealId, userId);

        when(appealRepo.findById(appealId)).thenReturn(Optional.of(appeal));
        when(appealRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        AppealResponse result = appealService.approve(appealId, adminId);

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(appeal.getReviewedBy()).isEqualTo(adminId);

        ArgumentCaptor<UpdateUserStatusRequest> captor = ArgumentCaptor.forClass(UpdateUserStatusRequest.class);
        verify(adminUserService).updateStatus(eq(userId), captor.capture(), eq(adminId));
        assertThat(captor.getValue().getStatus()).isEqualTo(AccountStatus.ACTIVE);

        verify(auditLogService).log(
            eq("ACCOUNT_APPEAL_APPROVED"), eq(adminId), eq("ACCOUNT_APPEAL"), eq(appealId), isNull(), isNull()
        );
    }

    @Test
    void approve_alreadyReviewed_throwsBadRequestAndDoesNotReactivate() {
        UUID appealId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        AccountAppeal appeal = makePendingAppeal(appealId, UUID.randomUUID());
        appeal.setStatus(AppealStatus.APPROVED);

        when(appealRepo.findById(appealId)).thenReturn(Optional.of(appeal));

        assertThatThrownBy(() -> appealService.approve(appealId, adminId))
            .isInstanceOf(BadRequestException.class);

        verify(adminUserService, never()).updateStatus(any(), any(), any());
    }

    @Test
    void approve_notFound_throwsResourceNotFound() {
        UUID appealId = UUID.randomUUID();
        when(appealRepo.findById(appealId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appealService.approve(appealId, UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reject_setsRejectedStatusAndDoesNotTouchAccountStatus() {
        UUID appealId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        AccountAppeal appeal = makePendingAppeal(appealId, UUID.randomUUID());

        when(appealRepo.findById(appealId)).thenReturn(Optional.of(appeal));
        when(appealRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        RejectAppealRequest req = new RejectAppealRequest();
        req.setReason("Insufficient grounds");

        AppealResponse result = appealService.reject(appealId, req, adminId);

        assertThat(result.status()).isEqualTo("REJECTED");
        verify(adminUserService, never()).updateStatus(any(), any(), any());
        verify(auditLogService).log(
            eq("ACCOUNT_APPEAL_REJECTED"), eq(adminId), eq("ACCOUNT_APPEAL"), eq(appealId), eq("Insufficient grounds"), isNull()
        );
    }

    @Test
    void reject_alreadyReviewed_throwsBadRequest() {
        UUID appealId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        AccountAppeal appeal = makePendingAppeal(appealId, UUID.randomUUID());
        appeal.setStatus(AppealStatus.REJECTED);

        when(appealRepo.findById(appealId)).thenReturn(Optional.of(appeal));

        RejectAppealRequest req = new RejectAppealRequest();
        req.setReason("second look");

        assertThatThrownBy(() -> appealService.reject(appealId, req, adminId))
            .isInstanceOf(BadRequestException.class);
    }
}
