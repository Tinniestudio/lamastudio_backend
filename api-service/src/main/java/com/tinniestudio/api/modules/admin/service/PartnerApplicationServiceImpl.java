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
public class PartnerApplicationServiceImpl implements PartnerApplicationService {

    private final PartnerApplicationRepository applicationRepo;
    private final PartnerProfileRepository profileRepo;
    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final AuditLogService auditLogService;

    @Override
    @Transactional
    public PartnerApplicationResponse apply(UUID userId, PartnerApplicationRequest req) {
        if (applicationRepo.existsByUserIdAndStatus(userId, PartnerApplicationStatus.PENDING)) {
            throw new BadRequestException("A pending partner application already exists");
        }
        PartnerApplication app = new PartnerApplication();
        app.setUserId(userId);
        app.setCompanyName(req.getCompanyName());
        app.setDescription(req.getDescription());
        app.setWebsiteUrl(req.getWebsiteUrl());
        return PartnerApplicationResponse.from(applicationRepo.save(app));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PartnerApplicationResponse> list(PartnerApplicationStatus status, Pageable pageable) {
        return (status != null
            ? applicationRepo.findByStatusOrderByCreatedAtDesc(status, pageable)
            : applicationRepo.findAllByOrderByCreatedAtDesc(pageable))
            .map(PartnerApplicationResponse::from);
    }

    @Override
    @Transactional
    public PartnerApplicationResponse approve(UUID applicationId, UUID adminId) {
        PartnerApplication app = applicationRepo.findById(applicationId)
            .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        if (app.getStatus() != PartnerApplicationStatus.PENDING) {
            throw new BadRequestException("Application has already been reviewed");
        }

        app.setStatus(PartnerApplicationStatus.APPROVED);
        app.setReviewedBy(adminId);
        app.setReviewedAt(Instant.now());
        applicationRepo.save(app);

        User user = userRepo.findById(app.getUserId())
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Role partnerRole = roleRepo.findByName(RoleName.ROLE_PARTNER)
            .orElseThrow(() -> new ResourceNotFoundException("ROLE_PARTNER not found"));
        user.addRole(partnerRole);
        userRepo.save(user);

        if (!profileRepo.existsByUserId(app.getUserId())) {
            PartnerProfile profile = new PartnerProfile();
            profile.setUserId(app.getUserId());
            profile.setCompanyName(app.getCompanyName());
            profile.setWebsiteUrl(app.getWebsiteUrl());
            profileRepo.save(profile);
        }

        auditLogService.log("PARTNER_APPLICATION_APPROVED", adminId, "PARTNER_APPLICATION", applicationId, null, null);
        return PartnerApplicationResponse.from(app);
    }

    @Override
    @Transactional
    public PartnerApplicationResponse reject(UUID applicationId, RejectApplicationRequest req, UUID adminId) {
        PartnerApplication app = applicationRepo.findById(applicationId)
            .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        if (app.getStatus() != PartnerApplicationStatus.PENDING) {
            throw new BadRequestException("Application has already been reviewed");
        }
        app.setStatus(PartnerApplicationStatus.REJECTED);
        app.setRejectionReason(req.getReason());
        app.setReviewedBy(adminId);
        app.setReviewedAt(Instant.now());
        applicationRepo.save(app);
        auditLogService.log("PARTNER_APPLICATION_REJECTED", adminId, "PARTNER_APPLICATION", applicationId, req.getReason(), null);
        return PartnerApplicationResponse.from(app);
    }
}
