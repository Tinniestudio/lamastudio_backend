package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AdminUserResponse;
import com.tinniestudio.api.modules.admin.dto.UpdateUserRequest;
import com.tinniestudio.api.modules.admin.dto.UpdateUserStatusRequest;
import com.tinniestudio.api.modules.auth.user.service.SessionService;
import com.tinniestudio.api.modules.user.repository.UserRepository;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import com.tinniestudio.api.shared.entity.User;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.EnumSet;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private final UserRepository userRepo;
    private final SessionService sessionService;
    private final AuditLogService auditLogService;

    private static final EnumSet<AccountStatus> TOKEN_REVOKE_STATUSES =
        EnumSet.of(AccountStatus.SUSPENDED, AccountStatus.BAN);

    @Override
    @Transactional(readOnly = true)
    public Page<AdminUserResponse> listUsers(AccountStatus status, String search, Pageable pageable) {
        return userRepo.findByFilters(status, search, pageable).map(AdminUserResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserResponse getById(UUID userId) {
        return userRepo.findById(userId)
            .map(AdminUserResponse::from)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    @Override
    @Transactional
    public AdminUserResponse update(UUID userId, UpdateUserRequest req) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (req.getEmail() != null) user.setEmail(req.getEmail());
        if (req.getEmailVerified() != null) user.setEmailVerified(req.getEmailVerified());
        return AdminUserResponse.from(userRepo.save(user));
    }

    @Override
    @Transactional
    public void updateStatus(UUID userId, UpdateUserStatusRequest req, UUID adminId) {
        // BUG FIX: DELETED is not a valid value here. DELETE /admin/users/{id} (softDelete()
        // below) is the single correct path for deletion — it sets deletedAt AND is the only
        // place account deletion is triggered. Allowing DELETED through this endpoint too created
        // two divergent "deleted" signals: accountStatus flipped to DELETED without deletedAt
        // ever being set, and (since DELETED wasn't in TOKEN_REVOKE_STATUSES) without revoking
        // the user's sessions either — contradicting the suspend/ban/delete contract.
        if (req.getStatus() == AccountStatus.DELETED) {
            throw new BadRequestException(
                "DELETED is not a valid status update; use DELETE /admin/users/{id} to delete a user");
        }

        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        user.setAccountStatus(req.getStatus());
        userRepo.save(user);
        if (TOKEN_REVOKE_STATUSES.contains(req.getStatus())) {
            sessionService.revokeAllUserSessions(userId, adminId);
        }
        auditLogService.log(
            "USER_" + req.getStatus().name(),
            adminId, "USER", userId,
            req.getReason(), null
        );
    }

    @Override
    @Transactional
    public void softDelete(UUID userId, UUID adminId) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        user.softDelete();
        userRepo.save(user);
        auditLogService.log("USER_DELETED", adminId, "USER", userId, null, null);
    }
}
