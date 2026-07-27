package com.tinniestudio.api.modules.admin.service;

import com.tinniestudio.api.modules.admin.dto.AuditLogResponse;
import com.tinniestudio.api.modules.admin.repository.AuditLogRepository;
import com.tinniestudio.api.shared.entity.AuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock AuditLogRepository auditLogRepo;
    @InjectMocks AuditLogServiceImpl auditLogService;

    @Test
    void log_savesEntryWithCorrectFields() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        when(auditLogRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        auditLogService.log("USER_SUSPENDED", actorId, "USER", targetId, "Violation", null);

        verify(auditLogRepo).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getAction()).isEqualTo("USER_SUSPENDED");
        assertThat(saved.getActorId()).isEqualTo(actorId);
        assertThat(saved.getActorType()).isEqualTo("ADMIN");
        assertThat(saved.getTargetType()).isEqualTo("USER");
        assertThat(saved.getTargetId()).isEqualTo(targetId);
        assertThat(saved.getReason()).isEqualTo("Violation");
        assertThat(saved.getMetadata()).isNull();
    }

    @Test
    void log_nullActorId_setsSystemActorType() {
        when(auditLogRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        auditLogService.log("CLEANUP", null, "UPLOAD_SESSION", UUID.randomUUID(), null, null);

        verify(auditLogRepo).save(captor.capture());
        assertThat(captor.getValue().getActorType()).isEqualTo("SYSTEM");
    }

    @Test
    void listAll_returnsMappedPage() {
        AuditLog entry = new AuditLog();
        ReflectionTestUtils.setField(entry, "id", UUID.randomUUID());
        entry.setAction("USER_SUSPENDED");
        entry.setActorType("ADMIN");

        when(auditLogRepo.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(entry)));

        var result = auditLogService.listAll(Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).action()).isEqualTo("USER_SUSPENDED");
    }

    @Test
    void listByTarget_returnsMappedPage() {
        AuditLog entry = new AuditLog();
        ReflectionTestUtils.setField(entry, "id", UUID.randomUUID());
        UUID targetId = UUID.randomUUID();
        entry.setTargetType("USER");
        entry.setTargetId(targetId);
        entry.setAction("USER_BANNED");
        entry.setActorType("ADMIN");

        when(auditLogRepo.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(eq("USER"), eq(targetId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(entry)));

        var result = auditLogService.listByTarget("USER", targetId, Pageable.unpaged());

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).targetId()).isEqualTo(targetId);
    }
}
