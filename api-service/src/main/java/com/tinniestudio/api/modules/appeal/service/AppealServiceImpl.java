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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AppealServiceImpl implements AppealService {

    private final AccountAppealRepository appealRepo;
    private final UserRepository userRepo;
    private final AdminUserService adminUserService;
    private final AuditLogService auditLogService;

    @Override
    @Transactional
    public AppealResponse submit(UUID userId, SubmitAppealRequest req) {
        User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (user.getAccountStatus() != AccountStatus.SUSPENDED) {
            throw new BadRequestException("Only suspended accounts can submit an appeal");
        }
        if (appealRepo.existsByUserIdAndStatus(userId, AppealStatus.PENDING)) {
            throw new BadRequestException("A pending appeal already exists");
        }
        AccountAppeal appeal = new AccountAppeal();
        appeal.setUserId(userId);
        appeal.setReason(req.getReason());
        return AppealResponse.from(appealRepo.save(appeal));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AppealResponse> list(AppealStatus status, Pageable pageable) {
        return (status != null
            ? appealRepo.findByStatusOrderByCreatedAtDesc(status, pageable)
            : appealRepo.findAllByOrderByCreatedAtDesc(pageable))
            .map(AppealResponse::from);
    }

    @Override
    @Transactional
    public AppealResponse approve(UUID appealId, UUID adminId) {
        AccountAppeal appeal = requirePending(appealId);
        appeal.setStatus(AppealStatus.APPROVED);
        appeal.setReviewedBy(adminId);
        appeal.setReviewedAt(Instant.now());
        appealRepo.save(appeal);

        UpdateUserStatusRequest reactivate = new UpdateUserStatusRequest();
        reactivate.setStatus(AccountStatus.ACTIVE);
        reactivate.setReason("Appeal approved");
        adminUserService.updateStatus(appeal.getUserId(), reactivate, adminId);

        auditLogService.log("ACCOUNT_APPEAL_APPROVED", adminId, "ACCOUNT_APPEAL", appealId, null, null);
        return AppealResponse.from(appeal);
    }

    @Override
    @Transactional
    public AppealResponse reject(UUID appealId, RejectAppealRequest req, UUID adminId) {
        AccountAppeal appeal = requirePending(appealId);
        appeal.setStatus(AppealStatus.REJECTED);
        appeal.setReviewedBy(adminId);
        appeal.setReviewedAt(Instant.now());
        appealRepo.save(appeal);
        auditLogService.log("ACCOUNT_APPEAL_REJECTED", adminId, "ACCOUNT_APPEAL", appealId, req.getReason(), null);
        return AppealResponse.from(appeal);
    }

    private AccountAppeal requirePending(UUID appealId) {
        AccountAppeal appeal = appealRepo.findById(appealId)
            .orElseThrow(() -> new ResourceNotFoundException("Appeal not found"));
        if (appeal.getStatus() != AppealStatus.PENDING) {
            throw new BadRequestException("Appeal has already been reviewed");
        }
        return appeal;
    }
}
