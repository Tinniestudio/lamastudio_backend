package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AuditLogResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

public interface AuditLogService {
    void log(String action, UUID actorId, String targetType, UUID targetId, String reason, String metadata);
    Page<AuditLogResponse> listAll(Pageable pageable);
    Page<AuditLogResponse> listByTarget(String targetType, UUID targetId, Pageable pageable);
}
