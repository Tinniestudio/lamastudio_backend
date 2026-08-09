package com.tinniestudio.api.modules.appeal.dto;

import com.tinniestudio.api.shared.entity.AccountAppeal;
import java.time.Instant;
import java.util.UUID;

public record AppealResponse(
    UUID id,
    UUID userId,
    String reason,
    String status,
    UUID reviewedBy,
    Instant reviewedAt,
    Instant createdAt
) {
    public static AppealResponse from(AccountAppeal a) {
        return new AppealResponse(
            a.getId(), a.getUserId(), a.getReason(), a.getStatus().name(),
            a.getReviewedBy(), a.getReviewedAt(), a.getCreatedAt()
        );
    }
}
