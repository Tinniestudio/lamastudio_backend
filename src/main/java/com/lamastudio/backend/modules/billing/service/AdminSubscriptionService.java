package com.lamastudio.backend.modules.billing.service;

import com.lamastudio.backend.modules.billing.dto.AdminPlanRequest;
import com.lamastudio.backend.modules.billing.dto.AdminPlanResponse;

import java.util.List;
import java.util.UUID;

public interface AdminSubscriptionService {

    List<AdminPlanResponse> getAllPlans();

    AdminPlanResponse getPlan(UUID planId);

    AdminPlanResponse createPlan(AdminPlanRequest request);

    AdminPlanResponse updatePlan(UUID planId, AdminPlanRequest request);

    /** Soft delete — sets isActive = false. Never removes the row. */
    void deletePlan(UUID planId);
}
