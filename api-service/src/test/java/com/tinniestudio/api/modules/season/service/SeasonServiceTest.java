package com.tinniestudio.api.modules.season.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.season.dto.CreateSeasonRequest;
import com.tinniestudio.api.modules.season.dto.SeasonResponse;
import com.tinniestudio.api.modules.season.dto.UpdateSeasonRequest;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.Season;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SeasonService")
class SeasonServiceTest {

    @Mock private SeasonRepository seasonRepository;
    @Mock private ContentRepository contentRepository;

    @InjectMocks private SeasonService seasonService;

    private Content seriesContent;
    private UUID contentId;

    @BeforeEach
    void setUp() {
        contentId = UUID.randomUUID();

        seriesContent = new Content();
        seriesContent.setId(contentId);
        seriesContent.setTitle("Breaking Bad");
        seriesContent.setType(ContentType.SERIES);
        seriesContent.setStatus(ContentStatus.DRAFT);
        seriesContent.setCreatedBy(UUID.randomUUID());
        seriesContent.setComingSoon(false);
        seriesContent.setFeatured(false);
        seriesContent.setViewCount(0L);
        seriesContent.setMaturityRating(MaturityRating.NOT_RATED);
        seriesContent.setCategories(new java.util.HashSet<>());
    }

    private Season buildSeason(int number) {
        Season s = new Season();
        s.setId(UUID.randomUUID());
        s.setContent(seriesContent);
        s.setSeasonNumber(number);
        s.setTitle("Season " + number);
        s.setEpisodes(new ArrayList<>());
        return s;
    }

    @Nested
    @DisplayName("listByContent()")
    class ListByContentTests {

        @Test
        @DisplayName("returns seasons in season-number order")
        void returnsSeasonsInOrder() {
            Season s1 = buildSeason(1);
            Season s2 = buildSeason(2);
            when(seasonRepository.findByContentIdOrderBySeasonNumberAsc(contentId))
                .thenReturn(List.of(s1, s2));

            List<SeasonResponse> result = seasonService.listByContent(contentId);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).seasonNumber()).isEqualTo(1);
            assertThat(result.get(1).seasonNumber()).isEqualTo(2);
            verify(seasonRepository).findByContentIdOrderBySeasonNumberAsc(contentId);
        }
    }

    @Nested
    @DisplayName("getById()")
    class GetByIdTests {

        @Test
        @DisplayName("returns response when season found")
        void returnsResponseWhenFound() {
            Season s = buildSeason(1);
            when(seasonRepository.findById(s.getId())).thenReturn(Optional.of(s));

            SeasonResponse result = seasonService.getById(s.getId());

            assertThat(result.seasonNumber()).isEqualTo(1);
            assertThat(result.contentId()).isEqualTo(contentId);
        }

        @Test
        @DisplayName("throws 404 when season not found")
        void throws404WhenNotFound() {
            UUID missingId = UUID.randomUUID();
            when(seasonRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> seasonService.getById(missingId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("auto-numbers when seasonNumber is null (MAX + 1)")
        void autoNumbersWhenSeasonNumberNull() {
            CreateSeasonRequest req = new CreateSeasonRequest(
                null, "New Season", null, null, null, null
            );
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(seriesContent));
            when(seasonRepository.findMaxSeasonNumberByContentId(contentId)).thenReturn(Optional.of(2));
            when(seasonRepository.save(any(Season.class))).thenAnswer(inv -> {
                Season s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                s.setEpisodes(new ArrayList<>());
                return s;
            });

            SeasonResponse result = seasonService.create(contentId, req);

            assertThat(result.seasonNumber()).isEqualTo(3);
        }

        @Test
        @DisplayName("auto-numbers to 1 when no seasons exist yet")
        void autoNumbersToOneWhenNoSeasons() {
            CreateSeasonRequest req = new CreateSeasonRequest(
                null, "Pilot Season", null, null, null, null
            );
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(seriesContent));
            when(seasonRepository.findMaxSeasonNumberByContentId(contentId)).thenReturn(Optional.empty());
            when(seasonRepository.save(any(Season.class))).thenAnswer(inv -> {
                Season s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                s.setEpisodes(new ArrayList<>());
                return s;
            });

            SeasonResponse result = seasonService.create(contentId, req);

            assertThat(result.seasonNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("uses provided seasonNumber when explicitly given")
        void usesProvidedSeasonNumber() {
            CreateSeasonRequest req = new CreateSeasonRequest(
                5, "Season Five", null, LocalDate.of(2024, 1, 1), null, null
            );
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(seriesContent));
            when(seasonRepository.existsByContentIdAndSeasonNumber(contentId, 5)).thenReturn(false);
            when(seasonRepository.save(any(Season.class))).thenAnswer(inv -> {
                Season s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                s.setEpisodes(new ArrayList<>());
                return s;
            });

            SeasonResponse result = seasonService.create(contentId, req);

            assertThat(result.seasonNumber()).isEqualTo(5);
            assertThat(result.title()).isEqualTo("Season Five");
        }

        @Test
        @DisplayName("throws 409 when provided seasonNumber already exists")
        void throws409OnDuplicateSeasonNumber() {
            CreateSeasonRequest req = new CreateSeasonRequest(
                2, "Duplicate Season", null, null, null, null
            );
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(seriesContent));
            when(seasonRepository.existsByContentIdAndSeasonNumber(contentId, 2)).thenReturn(true);

            assertThatThrownBy(() -> seasonService.create(contentId, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));
        }

        @Test
        @DisplayName("throws 404 when content not found")
        void throws404WhenContentNotFound() {
            UUID missingContentId = UUID.randomUUID();
            CreateSeasonRequest req = new CreateSeasonRequest(
                1, "Season One", null, null, null, null
            );
            when(contentRepository.findById(missingContentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> seasonService.create(missingContentId, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("update()")
    class UpdateTests {

        @Test
        @DisplayName("updates only non-null fields — title changes, posterUrl stays null")
        void updatesOnlyNonNullFields() {
            Season s = buildSeason(1);
            s.setPosterUrl("https://old-poster.jpg");
            UUID seasonId = s.getId();

            UpdateSeasonRequest req = new UpdateSeasonRequest(
                "Updated Title", null, null, null, null
            );
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(s));
            when(seasonRepository.save(any(Season.class))).thenAnswer(inv -> inv.getArgument(0));

            SeasonResponse result = seasonService.update(seasonId, req);

            assertThat(result.title()).isEqualTo("Updated Title");
            assertThat(result.posterUrl()).isEqualTo("https://old-poster.jpg"); // unchanged
        }

        @Test
        @DisplayName("throws 404 when season not found")
        void throws404WhenNotFound() {
            UUID missingId = UUID.randomUUID();
            when(seasonRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> seasonService.update(missingId, new UpdateSeasonRequest(null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("calls repository.delete(entity) when season found")
        void deletesWhenFound() {
            Season s = buildSeason(1);
            when(seasonRepository.findById(s.getId())).thenReturn(Optional.of(s));

            seasonService.delete(s.getId());

            verify(seasonRepository).delete(s);
        }

        @Test
        @DisplayName("throws 404 when season not found")
        void throws404WhenNotFound() {
            UUID missingId = UUID.randomUUID();
            when(seasonRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> seasonService.delete(missingId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }
}
