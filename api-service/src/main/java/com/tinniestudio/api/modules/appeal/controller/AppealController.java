package com.tinniestudio.api.modules.appeal.controller;

import com.tinniestudio.api.shared.security.CurrentUser;
import com.tinniestudio.api.modules.appeal.dto.AppealResponse;
import com.tinniestudio.api.modules.appeal.dto.SubmitAppealRequest;
import com.tinniestudio.api.modules.appeal.service.AppealService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A SUSPENDED account's JWT does not authenticate against any endpoint EXCEPT this one — see
 * JwtAuthenticationFilter, which special-cases exactly this path so a suspended user can still
 * reach it despite UserDetailsServiceImpl normally rejecting inactive accounts outright. BAN and
 * DELETED accounts remain fully blocked, matching Batch 14 #2 ("BAN, block user from total access").
 */
@Tag(name = "Appeals", description = "Suspended-account appeal submission")
@RestController
@RequestMapping("/appeals")
@RequiredArgsConstructor
public class AppealController {

    private final AppealService appealService;

    @Operation(summary = "Submit an appeal to recover a suspended account")
    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<AppealResponse> submit(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody SubmitAppealRequest req) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(appealService.submit(userId, req));
    }
}
