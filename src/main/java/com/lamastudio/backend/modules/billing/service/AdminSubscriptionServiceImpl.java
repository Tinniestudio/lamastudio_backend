package com.lamastudio.backend.modules.billing.service;

import com.lamastudio.backend.modules.billing.dto.AdminPlanRequest;
import com.lamastudio.backend.modules.billing.dto.AdminPlanResponse;
import com.lamastudio.backend.modules.billing.repository.SubscriptionPlanRepository;
import com.lamastudio.backend.shared.entity.SubscriptionPlan;
import com.lamastudio.backend.shared.exception.BadRequestException;
import com.lamastudio.backend.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSubscriptionServiceImpl implements AdminSubscriptionService {

    private final SubscriptionPlanRepository planRepository;

    @Override
    @Transactional(readOnly = true)
    public List<AdminPlanResponse> getAllPlans() {
        return planRepository.findAllByOrderByPriceAsc()
                .stream()
                .map(AdminPlanResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public AdminPlanResponse getPlan(UUID planId) {
        return AdminPlanResponse.from(findOrThrow(planId));
    }

    @Override
    @Transactional
    public AdminPlanResponse createPlan(AdminPlanRequest request) {
        if (planRepository.findByNameIgnoreCase(request.getName()).isPresent()) {
            throw new BadRequestException("A plan with the name '" + request.getName() + "' already exists");
        }

        SubscriptionPlan plan = new SubscriptionPlan();
        applyRequest(plan, request);
        plan = planRepository.save(plan);

        log.info("Admin created subscription plan '{}' (id={})", plan.getName(), plan.getId());
        return AdminPlanResponse.from(plan);
    }

    @Override
    @Transactional
    public AdminPlanResponse updatePlan(UUID planId, AdminPlanRequest request) {
        SubscriptionPlan plan = findOrThrow(planId);

        // Name uniqueness check — allow keeping the same name on the same plan
        planRepository.findByNameIgnoreCase(request.getName())
                .filter(existing -> !existing.getId().equals(planId))
                .ifPresent(dup -> {
                    throw new BadRequestException("A plan with the name '" + request.getName() + "' already exists");
                });

        applyRequest(plan, request);
        plan = planRepository.save(plan);

        log.info("Admin updated subscription plan '{}' (id={})", plan.getName(), plan.getId());
        return AdminPlanResponse.from(plan);
    }

    @Override
    @Transactional
    public void deletePlan(UUID planId) {
        SubscriptionPlan plan = findOrThrow(planId);
        plan.setIsActive(false);
        planRepository.save(plan);
        log.info("Admin soft-deleted subscription plan '{}' (id={})", plan.getName(), planId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SubscriptionPlan findOrThrow(UUID planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription plan not found"));
    }

    private void applyRequest(SubscriptionPlan plan, AdminPlanRequest request) {
        plan.setName(request.getName().strip());
        plan.setDescription(request.getDescription());
        plan.setPrice(request.getPrice());
        plan.setCurrency(request.getCurrency() != null ? request.getCurrency().toUpperCase() : "CAD");
        plan.setBillingCycle(request.getBillingCycle());
        plan.setMaxDevices(request.getMaxDevices());
        plan.setVideoQuality(request.getVideoQuality());
        plan.setContentLimit(request.getContentLimit());
        plan.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
    }
}
