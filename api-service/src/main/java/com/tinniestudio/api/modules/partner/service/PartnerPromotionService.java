package com.tinniestudio.api.modules.partner.service;

import com.tinniestudio.api.shared.entity.PartnerProfile;
import java.util.UUID;

/**
 * Shared "grant ROLE_PARTNER + ensure a PartnerProfile exists" logic used by BOTH partner
 * promotion paths:
 *  - self-service: PartnerApplicationServiceImpl.approve() (admin approves a submitted application)
 *  - direct: AdminUserServiceImpl.promoteToPartner() (admin promotes an arbitrary user with no
 *    prior application)
 *
 * Extracted so the role-grant + profile-creation side effect is defined exactly once.
 */
public interface PartnerPromotionService {
    /**
     * Idempotent: adding ROLE_PARTNER to a user that already has it is a no-op (Set semantics),
     * and an existing PartnerProfile is never overwritten/duplicated.
     */
    PartnerProfile grantPartnerRoleAndProfile(UUID userId, String companyName, String websiteUrl);

    /**
     * Revokes ROLE_PARTNER from a user — used when admin rejects a previously-APPROVED partner
     * application (undoing the approval) or otherwise removes partner access for a regulation
     * violation. The PartnerProfile row is kept (not deleted) for audit/history purposes and is
     * simply left orphaned of the role; it's marked unverified so nothing treats it as active.
     * Idempotent: removing a role the user doesn't have is a no-op (Set semantics).
     */
    void revokePartnerRole(UUID userId);
}
