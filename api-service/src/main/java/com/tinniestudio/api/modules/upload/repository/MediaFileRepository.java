package com.tinniestudio.api.modules.upload.repository;

import com.tinniestudio.api.shared.entity.MediaFile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface MediaFileRepository extends JpaRepository<MediaFile, UUID> {}
