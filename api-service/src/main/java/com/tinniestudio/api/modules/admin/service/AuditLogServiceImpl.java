package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AuditLogResponse;
import com.tinniestudio.api.modules.admin.repository.AuditLogRepository;
import com.tinniestudio.api.shared.entity.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogRepository auditLogRepo;

    @Override
    @Transactional
    public void log(String action, UUID actorId, String targetType, UUID targetId, String reason, String metadata) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setActorType(actorId != null ? "ADMIN" : "SYSTEM");
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setReason(reason);
        entry.setMetadata(metadata);
        auditLogRepo.save(entry);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> listAll(Pageable pageable) {
        return auditLogRepo.findAllByOrderByCreatedAtDesc(pageable).map(AuditLogResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> listByTarget(String targetType, UUID targetId, Pageable pageable) {
        return auditLogRepo.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId, pageable)
            .map(AuditLogResponse::from);
    }
}
