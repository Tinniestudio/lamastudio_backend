package com.tinniestudio.api.modules.ticket.controller;

import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.dto.UpdateTicketStatusRequest;
import com.tinniestudio.api.modules.ticket.service.AdminTicketService;
import com.tinniestudio.api.shared.entity.DomainEnums.TicketStatus;
import com.tinniestudio.api.shared.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/admin/tickets")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Support Tickets", description = "Any admin (SUPER_ADMIN, MODERATOR, or SUPPORT) can view and reply to any ticket — no per-agent assignment.")
public class AdminTicketController {

    private final AdminTicketService adminTicketService;

    @Operation(summary = "List all tickets, optionally filtered by status")
    @GetMapping
    public ResponseEntity<Page<TicketResponse>> list(
            @RequestParam(required = false) TicketStatus status,
            Pageable pageable) {
        return ResponseEntity.ok(adminTicketService.listTickets(status, pageable));
    }

    @Operation(summary = "Get a ticket's full thread")
    @GetMapping("/{ticketId}")
    public ResponseEntity<TicketDetailResponse> getOne(@PathVariable UUID ticketId) {
        return ResponseEntity.ok(adminTicketService.getTicket(ticketId));
    }

    @Operation(summary = "Reply to a ticket as an admin")
    @PostMapping("/{ticketId}/messages")
    public ResponseEntity<TicketDetailResponse> reply(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID ticketId,
            @Valid @RequestBody ReplyTicketRequest request) {
        UUID adminId = CurrentUser.id(principal);
        return ResponseEntity.ok(adminTicketService.replyToTicket(adminId, ticketId, request));
    }

    @Operation(summary = "Resolve or close a ticket")
    @PatchMapping("/{ticketId}/status")
    public ResponseEntity<TicketResponse> updateStatus(
            @PathVariable UUID ticketId,
            @Valid @RequestBody UpdateTicketStatusRequest request) {
        return ResponseEntity.ok(adminTicketService.updateStatus(ticketId, request));
    }
}
