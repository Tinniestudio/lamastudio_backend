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
     * Activates the given (already target-linked, already-persisted) asset and atomically
     * retires every true sibling sharing the same (target, assetType) — both writes commit or
     * roll back together in one transaction, closing a window where a crash between them could
     * leave zero active assets for that pair.
     *
     * Scope is picked by the MOST SPECIFIC target set on the asset: episode, then season, then
     * content — in that order, and episode/season MUST be checked before content. Per
     * UploadService.linkTargetAndAssertOwnership(), content is denormalized onto every
     * episode-linked and season-linked asset too (so a whole show's episodes all share one
     * content.id). Checking content first would match on that shared id and silently fall back
     * into the content-wide bug this ordering closes: activating one episode's video would
     * retire every OTHER episode's active video on the same show, not just true siblings of the
     * asset being activated. Season-linked assets are similarly checked before content for the
     * same reason.
     */
    @Transactional
    public void activateAndRetireSiblings(VideoAsset asset) {
        if (asset.getEpisode() != null) {
            videoAssetRepository.deactivateOtherAssetsForEpisode(asset.getEpisode().getId(), asset.getAssetType(), asset.getId());
        } else if (asset.getSeason() != null) {
            videoAssetRepository.deactivateOtherAssetsForSeason(asset.getSeason().getId(), asset.getAssetType(), asset.getId());
        } else if (asset.getContent() != null) {
            videoAssetRepository.deactivateOtherAssetsForContent(asset.getContent().getId(), asset.getAssetType(), asset.getId());
        }
        asset.setActive(true);
        videoAssetRepository.save(asset);
    }
}
