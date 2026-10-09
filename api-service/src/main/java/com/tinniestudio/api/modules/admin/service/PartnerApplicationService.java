package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.dto.RejectApplicationRequest;
import com.tinniestudio.api.modules.partner.dto.PartnerApplicationRequest;
import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.UUID;

public interface PartnerApplicationService {
    PartnerApplicationResponse apply(UserDetails principal, PartnerApplicationRequest req, HttpServletResponse response);
    Page<PartnerApplicationResponse> list(PartnerApplicationStatus status, Pageable pageable);
    PartnerApplicationResponse approve(UUID applicationId, UUID adminId);
    PartnerApplicationResponse reject(UUID applicationId, RejectApplicationRequest req, UUID adminId);
    PartnerApplicationResponse getByUserId(UUID userId);
}
