package com.tinniestudio.worker.repository;

import com.tinniestudio.worker.entity.VideoVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface VideoVariantRepository extends JpaRepository<VideoVariant, UUID> {}
