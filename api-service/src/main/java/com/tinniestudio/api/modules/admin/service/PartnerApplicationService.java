package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.PartnerApplicationResponse;
import com.tinniestudio.api.modules.admin.dto.RejectApplicationRequest;
import com.tinniestudio.api.modules.partner.dto.PartnerApplicationRequest;
import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

public interface PartnerApplicationService {
    PartnerApplicationResponse apply(UUID userId, PartnerApplicationRequest req);
    Page<PartnerApplicationResponse> list(PartnerApplicationStatus status, Pageable pageable);
    PartnerApplicationResponse approve(UUID applicationId, UUID adminId);
    PartnerApplicationResponse reject(UUID applicationId, RejectApplicationRequest req, UUID adminId);
}
