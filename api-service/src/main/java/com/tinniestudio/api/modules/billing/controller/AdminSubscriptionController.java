package com.tinniestudio.api.modules.billing.controller;

import com.tinniestudio.api.modules.billing.dto.AdminPlanRequest;
import com.tinniestudio.api.modules.billing.dto.AdminPlanResponse;
import com.tinniestudio.api.modules.billing.service.AdminSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Admin Plans", description = "Admin CRUD for subscription plans")
@RestController
@RequestMapping("/admin/plans")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MODERATOR')")
public class AdminSubscriptionController {

    private final AdminSubscriptionService adminSubscriptionService;

    @Operation(summary = "List all plans including inactive")
    @GetMapping
    public ResponseEntity<List<AdminPlanResponse>> listAll() {
        return ResponseEntity.ok(adminSubscriptionService.getAllPlans());
    }

    @Operation(summary = "Get a single plan by ID")
    @GetMapping("/{planId}")
    public ResponseEntity<AdminPlanResponse> getOne(@PathVariable UUID planId) {
        return ResponseEntity.ok(adminSubscriptionService.getPlan(planId));
    }

    @Operation(summary = "Create a new subscription plan")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping
    public ResponseEntity<AdminPlanResponse> create(@Valid @RequestBody AdminPlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminSubscriptionService.createPlan(request));
    }

    @Operation(summary = "Update an existing plan")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PutMapping("/{planId}")
    public ResponseEntity<AdminPlanResponse> update(
            @PathVariable UUID planId,
            @Valid @RequestBody AdminPlanRequest request) {
        return ResponseEntity.ok(adminSubscriptionService.updatePlan(planId, request));
    }

    @Operation(summary = "Soft-delete a plan (sets isActive = false)")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @DeleteMapping("/{planId}")
    public ResponseEntity<Object> delete(@PathVariable UUID planId) {
        adminSubscriptionService.deletePlan(planId);
        return ResponseEntity.ok(Map.of("message", "Subscription plan deleted successfully"));
    }

    @Operation(summary = "Restore a deleted plan (sets isActive = true)")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PutMapping("/{planId}/restore")
    public ResponseEntity<Object> restore(@PathVariable UUID planId) {
        adminSubscriptionService.restorePlan(planId);
        return ResponseEntity.ok(Map.of("message", "Subscription plan restored successfully"));
    }
}
