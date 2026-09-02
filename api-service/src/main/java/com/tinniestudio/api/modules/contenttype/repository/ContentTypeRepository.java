package com.tinniestudio.api.modules.contenttype.repository;

import com.tinniestudio.api.shared.entity.ContentType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContentTypeRepository extends JpaRepository<ContentType, UUID> {
    List<ContentType> findByIsActiveTrueOrderByDisplayOrderAsc();
    Optional<ContentType> findBySlug(String slug);
}
