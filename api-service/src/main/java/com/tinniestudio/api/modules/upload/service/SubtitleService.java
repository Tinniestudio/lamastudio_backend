package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.upload.dto.PartnerSubtitleResponse;
import com.tinniestudio.api.modules.upload.repository.SubtitleRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.Subtitle;
import com.tinniestudio.api.shared.entity.VideoAsset;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubtitleService {

    private final SubtitleRepository subtitleRepository;
    private final VideoAssetRepository videoAssetRepository;

    @Transactional(readOnly = true)
    public List<PartnerSubtitleResponse> list(UUID userId, UUID videoAssetId) {
        VideoAsset asset = assertOwnedVideoAsset(userId, videoAssetId);
        return subtitleRepository.findByVideoAsset_Id(asset.getId()).stream()
            .map(PartnerSubtitleResponse::from)
            .toList();
    }

    @Transactional
    public void delete(UUID userId, UUID videoAssetId, UUID subtitleId) {
        assertOwnedVideoAsset(userId, videoAssetId);
        Subtitle subtitle = subtitleRepository.findById(subtitleId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subtitle not found: " + subtitleId));
        // Guard against a caller passing a real subtitle id that belongs to a DIFFERENT video
        // asset than the one named in the URL — same enumeration-safe 404 (not 403) pattern
        // used throughout this module.
        if (!subtitle.getVideoAsset().getId().equals(videoAssetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Subtitle not found: " + subtitleId);
        }
        subtitleRepository.delete(subtitle);
    }

    private VideoAsset assertOwnedVideoAsset(UUID userId, UUID videoAssetId) {
        VideoAsset asset = videoAssetRepository.findById(videoAssetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId));
        // Ownership by uploader — same pattern PartnerVideoService.activate() and
        // UploadService.attachSubtitle() already use for VideoAsset-scoped IDOR guards.
        if (!userId.equals(asset.getUploadedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found: " + videoAssetId);
        }
        return asset;
    }
}
