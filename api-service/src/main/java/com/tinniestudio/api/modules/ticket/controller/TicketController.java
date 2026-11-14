package com.tinniestudio.api.modules.ticket.controller;

import com.tinniestudio.api.modules.ticket.dto.CreateTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.ReplyTicketRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketAttachmentUploadRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketAttachmentUploadResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketDetailResponse;
import com.tinniestudio.api.modules.ticket.dto.TicketResponse;
import com.tinniestudio.api.modules.ticket.service.TicketService;
import com.tinniestudio.api.shared.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Support Tickets", description = "User-facing ticket submission and thread management.")
public class TicketController {

    private final TicketService ticketService;

    @Operation(summary = "Submit a new support ticket")
    @PostMapping
    public ResponseEntity<TicketResponse> create(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody CreateTicketRequest request) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(ticketService.createTicket(userId, request));
    }

    @Operation(summary = "List the authenticated user's own tickets")
    @GetMapping
    public ResponseEntity<Page<TicketResponse>> list(
            @AuthenticationPrincipal UserDetails principal,
            Pageable pageable) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(ticketService.listMyTickets(userId, pageable));
    }

    @Operation(summary = "Get one of the authenticated user's own tickets, with its full message thread")
    @GetMapping("/{ticketId}")
    public ResponseEntity<TicketDetailResponse> getOne(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID ticketId) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(ticketService.getMyTicket(userId, ticketId));
    }

    @Operation(summary = "Reply to one of the authenticated user's own tickets")
    @PostMapping("/{ticketId}/messages")
    public ResponseEntity<TicketDetailResponse> reply(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable UUID ticketId,
            @Valid @RequestBody ReplyTicketRequest request) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(ticketService.replyToTicket(userId, ticketId, request));
    }

    @Operation(summary = "Get a presigned URL to upload a ticket/message attachment")
    @PostMapping("/attachments/presign")
    public ResponseEntity<TicketAttachmentUploadResponse> presignAttachment(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody TicketAttachmentUploadRequest request) {
        UUID userId = CurrentUser.id(principal);
        return ResponseEntity.ok(ticketService.initiateAttachmentUpload(userId, request));
    }
}
