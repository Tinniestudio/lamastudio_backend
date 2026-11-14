package com.tinniestudio.api.modules.ticket.service;

import com.tinniestudio.api.modules.ticket.dto.TicketAttachmentUploadRequest;
import com.tinniestudio.api.modules.ticket.dto.TicketAttachmentUploadResponse;
import com.tinniestudio.api.shared.config.AppProperties;
import com.tinniestudio.api.shared.exception.BadRequestException;
import com.tinniestudio.api.shared.storage.PresignedUploadResult;
import com.tinniestudio.api.shared.storage.StorageService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Shared attachment validation/URL-resolution for ticket messages — used by both the
 * user-facing and admin-facing reply paths so the mime/size rules and CDN-URL-building logic
 * exist in exactly one place, mirroring the presign/upload/confirm pattern already established
 * by {@code UserProfileServiceImpl}'s avatar upload flow.
 */
@Component
@RequiredArgsConstructor
public class TicketAttachmentResolver {

    private static final long MAX_ATTACHMENT_BYTES = 10_485_760L; // 10 MB, same ceiling as avatar uploads
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "application/pdf"
    );

    private final StorageService storageService;
    private final AppProperties appProperties;

    public TicketAttachmentUploadResponse initiateUpload(TicketAttachmentUploadRequest request, UUID userId) {
        if (request.getMimeType() == null || !ALLOWED_MIME_TYPES.contains(request.getMimeType())) {
            throw new BadRequestException(
                    "File type not supported. Allowed: image/jpeg, image/png, image/webp, application/pdf");
        }
        if (request.getFileSizeBytes() == null || request.getFileSizeBytes() > MAX_ATTACHMENT_BYTES) {
            throw new BadRequestException("File size exceeds the 10 MB limit");
        }

        String ext = mimeToExtension(request.getMimeType());
        String storageKey = "tickets/" + userId + "/" + UUID.randomUUID() + "." + ext;

        PresignedUploadResult result = storageService.generateUploadUrl(
                storageKey, request.getMimeType(), request.getFileSizeBytes(), Duration.ofMinutes(15));
        return new TicketAttachmentUploadResponse(result.uploadUrl(), result.storageKey(), result.expiresAt());
    }

    /**
     * @param ownerId the id whose own {@code tickets/{ownerId}/} prefix {@code storageKey} must
     * fall under. For the user-facing path this is the caller's own id; for the admin-facing
     * path (no separate admin upload flow exists) this is the ticket's owning user id — see
     * {@code AdminTicketServiceImpl.replyToTicket} in Task 7 Step 7.
     * @return the final CDN URL if {@code storageKey} is non-blank and the upload really landed
     * in storage, or {@code null} if no key was provided (no attachment on this message).
     * @throws BadRequestException if the key is outside {@code ownerId}'s own prefix, contains a
     * path-traversal sequence, or nothing exists at it yet.
     */
    public String resolveAttachmentUrl(UUID ownerId, String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return null;
        }
        // Without this ownership-scoping check, any caller could pass ANY storage key that
        // happens to exist in the bucket (e.g. another user's avatar) and have it falsely
        // accepted as this ticket message's attachment — objectExists() alone only proves
        // *something* is there, not that ownerId is the one who put it there.
        String requiredPrefix = "tickets/" + ownerId + "/";
        if (storageKey.contains("..") || !storageKey.startsWith(requiredPrefix)) {
            throw new BadRequestException("Invalid attachment reference.");
        }
        if (!storageService.objectExists(storageKey)) {
            throw new BadRequestException("Upload not found in storage. Please upload the file before submitting.");
        }
        return appProperties.getCdn().getBaseUrl() + "/" + storageKey;
    }

    private String mimeToExtension(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
    }
}
