package com.tinniestudio.api.modules.upload.repository;

import com.tinniestudio.api.shared.entity.UploadSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {}
