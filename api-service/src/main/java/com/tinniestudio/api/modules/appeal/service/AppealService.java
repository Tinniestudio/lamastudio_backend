package com.tinniestudio.api.modules.appeal.service;

import com.tinniestudio.api.modules.admin.dto.RejectAppealRequest;
import com.tinniestudio.api.modules.appeal.dto.AppealResponse;
import com.tinniestudio.api.modules.appeal.dto.SubmitAppealRequest;
import com.tinniestudio.api.shared.entity.DomainEnums.AppealStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

public interface AppealService {
    /** Only a SUSPENDED account may submit an appeal (BAN/DELETED cannot; ACTIVE has nothing to appeal). */
    AppealResponse submit(UUID userId, SubmitAppealRequest req);
    Page<AppealResponse> list(AppealStatus status, Pageable pageable);
    /** Approving reactivates the account (sets AccountStatus back to ACTIVE). */
    AppealResponse approve(UUID appealId, UUID adminId);
    AppealResponse reject(UUID appealId, RejectAppealRequest req, UUID adminId);
}
