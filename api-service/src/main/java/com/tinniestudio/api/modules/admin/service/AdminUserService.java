package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AdminUserResponse;
import com.tinniestudio.api.modules.admin.dto.PromoteToPartnerRequest;
import com.tinniestudio.api.modules.admin.dto.UpdateUserRequest;
import com.tinniestudio.api.modules.admin.dto.UpdateUserStatusRequest;
import com.tinniestudio.api.modules.partner.dto.PartnerProfileResponse;
import com.tinniestudio.api.shared.entity.DomainEnums.AccountStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

public interface AdminUserService {
    Page<AdminUserResponse> listUsers(AccountStatus status, String search, Pageable pageable);
    AdminUserResponse getById(UUID userId);
    AdminUserResponse update(UUID userId, UpdateUserRequest req);
    void updateStatus(UUID userId, UpdateUserStatusRequest req, UUID adminId);
    void softDelete(UUID userId, UUID adminId);

    /** Direct admin-initiated promotion of an arbitrary user to partner (no application needed). */
    PartnerProfileResponse promoteToPartner(UUID userId, PromoteToPartnerRequest req, UUID adminId);
}
