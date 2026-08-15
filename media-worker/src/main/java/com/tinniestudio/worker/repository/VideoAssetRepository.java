package com.tinniestudio.worker.repository;

import com.tinniestudio.worker.entity.VideoAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface VideoAssetRepository extends JpaRepository<VideoAsset, UUID> {}
