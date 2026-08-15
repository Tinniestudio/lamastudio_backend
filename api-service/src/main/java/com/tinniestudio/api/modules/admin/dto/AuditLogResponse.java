package com.tinniestudio.api.modules.admin.dto;

import com.tinniestudio.api.shared.entity.AuditLog;
import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(
    UUID id,
    UUID actorId,
    String actorType,
    String action,
    String targetType,
    UUID targetId,
    String reason,
    String metadata,
    Instant createdAt
) {
    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
            log.getId(), log.getActorId(), log.getActorType(),
            log.getAction(), log.getTargetType(), log.getTargetId(),
            log.getReason(), log.getMetadata(), log.getCreatedAt()
        );
    }
}
