package com.tinniestudio.api.modules.upload.repository;

import com.tinniestudio.api.shared.entity.Subtitle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SubtitleRepository extends JpaRepository<Subtitle, UUID> {
}
