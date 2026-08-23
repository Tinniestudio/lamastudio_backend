package com.tinniestudio.api.modules.upload.service;

import com.tinniestudio.api.modules.upload.dto.PartnerSubtitleResponse;
import com.tinniestudio.api.modules.upload.repository.SubtitleRepository;
import com.tinniestudio.api.modules.upload.repository.VideoAssetRepository;
import com.tinniestudio.api.shared.entity.Subtitle;
import com.tinniestudio.api.shared.entity.VideoAsset;
import com.tinniestudio.api.shared.entity.DomainEnums.SubtitleFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubtitleService")
class SubtitleServiceTest {

    @Mock SubtitleRepository subtitleRepository;
    @Mock VideoAssetRepository videoAssetRepository;

    @InjectMocks SubtitleService subtitleService;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID videoAssetId = UUID.randomUUID();

    private VideoAsset ownedAsset() {
        VideoAsset a = new VideoAsset();
        a.setId(videoAssetId);
        a.setUploadedBy(ownerId);
        return a;
    }

    @Nested @DisplayName("list()")
    class ListTests {

        @Test @DisplayName("returns subtitles for an owned video asset")
        void returnsForOwnedAsset() {
            VideoAsset asset = ownedAsset();
            Subtitle s = new Subtitle();
            s.setId(UUID.randomUUID());
            s.setVideoAsset(asset);
            s.setLanguageCode("en");
            s.setFormat(SubtitleFormat.VTT);
            when(videoAssetRepository.findById(videoAssetId)).thenReturn(Optional.of(asset));
            when(subtitleRepository.findByVideoAsset_Id(videoAssetId)).thenReturn(List.of(s));

            List<PartnerSubtitleResponse> result = subtitleService.list(ownerId, videoAssetId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).languageCode()).isEqualTo("en");
        }

        @Test @DisplayName("returns every subtitle attached to the video, not just one")
        void returnsMultipleSubtitles() {
            VideoAsset asset = ownedAsset();
            Subtitle en = new Subtitle();
            en.setId(UUID.randomUUID());
            en.setVideoAsset(asset);
            en.setLanguageCode("en");
            en.setFormat(SubtitleFormat.VTT);
            Subtitle fr = new Subtitle();
            fr.setId(UUID.randomUUID());
            fr.setVideoAsset(asset);
            fr.setLanguageCode("fr");
            fr.setFormat(SubtitleFormat.SRT);
            when(videoAssetRepository.findById(videoAssetId)).thenReturn(Optional.of(asset));
            when(subtitleRepository.findByVideoAsset_Id(videoAssetId)).thenReturn(List.of(en, fr));

            List<PartnerSubtitleResponse> result = subtitleService.list(ownerId, videoAssetId);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(PartnerSubtitleResponse::languageCode).containsExactly("en", "fr");
        }

        @Test @DisplayName("throws 404 when the video doesn't belong to the caller")
        void throws404WhenNotOwner() {
            VideoAsset asset = new VideoAsset();
            asset.setId(videoAssetId);
            asset.setUploadedBy(UUID.randomUUID());
            when(videoAssetRepository.findById(videoAssetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> subtitleService.list(ownerId, videoAssetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
            verifyNoInteractions(subtitleRepository);
        }

        @Test @DisplayName("throws 404 when the video doesn't exist")
        void throws404WhenMissing() {
            when(videoAssetRepository.findById(videoAssetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> subtitleService.list(ownerId, videoAssetId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested @DisplayName("delete()")
    class DeleteTests {

        @Test @DisplayName("deletes a subtitle owned via its video asset")
        void deletesOwnedSubtitle() {
            VideoAsset asset = ownedAsset();
            UUID subtitleId = UUID.randomUUID();
            Subtitle subtitle = new Subtitle();
            subtitle.setId(subtitleId);
            subtitle.setVideoAsset(asset);
            when(videoAssetRepository.findById(videoAssetId)).thenReturn(Optional.of(asset));
            when(subtitleRepository.findById(subtitleId)).thenReturn(Optional.of(subtitle));

            subtitleService.delete(ownerId, videoAssetId, subtitleId);

            verify(subtitleRepository).delete(subtitle);
        }

        @Test @DisplayName("throws 404 when the video doesn't belong to the caller")
        void throws404WhenNotOwner() {
            VideoAsset asset = new VideoAsset();
            asset.setId(videoAssetId);
            asset.setUploadedBy(UUID.randomUUID());
            UUID subtitleId = UUID.randomUUID();
            when(videoAssetRepository.findById(videoAssetId)).thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> subtitleService.delete(ownerId, videoAssetId, subtitleId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
            verifyNoInteractions(subtitleRepository);
        }

        @Test @DisplayName("throws 404 when the subtitle doesn't exist")
        void throws404WhenSubtitleMissing() {
            VideoAsset asset = ownedAsset();
            UUID subtitleId = UUID.randomUUID();
            when(videoAssetRepository.findById(videoAssetId)).thenReturn(Optional.of(asset));
            when(subtitleRepository.findById(subtitleId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> subtitleService.delete(ownerId, videoAssetId, subtitleId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test @DisplayName("throws 404 when the subtitle belongs to a different video asset")
        void throws404WhenSubtitleBelongsToDifferentAsset() {
            VideoAsset asset = ownedAsset();
            VideoAsset otherAsset = new VideoAsset();
            otherAsset.setId(UUID.randomUUID());
            UUID subtitleId = UUID.randomUUID();
            Subtitle subtitle = new Subtitle();
            subtitle.setId(subtitleId);
            subtitle.setVideoAsset(otherAsset);
            when(videoAssetRepository.findById(videoAssetId)).thenReturn(Optional.of(asset));
            when(subtitleRepository.findById(subtitleId)).thenReturn(Optional.of(subtitle));

            assertThatThrownBy(() -> subtitleService.delete(ownerId, videoAssetId, subtitleId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
            verify(subtitleRepository, never()).delete(any());
        }
    }
}
