package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.VideoAsset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VideoActivationService {

    private final VideoAssetRepository videoAssetRepository;

    /**
     * Activates the given (already content-linked, already-persisted) asset and atomically
     * retires every sibling sharing (content, assetType) — both writes commit or roll back
     * together in one transaction, closing a window where a crash between them could leave zero
     * active assets for that pair. Callers must have already confirmed asset.getContent() != null.
     */
    @Transactional
    public void activateAndRetireSiblings(VideoAsset asset) {
        videoAssetRepository.deactivateOtherAssets(asset.getContent().getId(), asset.getAssetType(), asset.getId());
        asset.setActive(true);
        videoAssetRepository.save(asset);
    }
}
