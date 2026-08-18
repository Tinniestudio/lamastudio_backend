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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
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
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        contentId = UUID.randomUUID();
        ownerId = UUID.randomUUID();

        seriesContent = new Content();
        seriesContent.setId(contentId);
        seriesContent.setTitle("Breaking Bad");
        seriesContent.setType(ContentType.SERIES);
        seriesContent.setStatus(ContentStatus.DRAFT);
        seriesContent.setCreatedBy(ownerId);
        seriesContent.setComingSoon(false);
        seriesContent.setFeatured(false);
        seriesContent.setViewCount(0L);
        seriesContent.setMaturityRating(MaturityRating.NOT_RATED);
        seriesContent.setCategories(new java.util.HashSet<>());
    }

    /** Non-admin principal, username = the given user id (matches JwtAuthenticationFilter's convention). */
    private UserDetails principalFor(UUID userId) {
        UserDetails principal = mock(UserDetails.class);
        lenient().when(principal.getUsername()).thenReturn(userId.toString());
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_PARTNER"));
        lenient().doReturn(authorities).when(principal).getAuthorities();
        return principal;
    }

    private UserDetails adminPrincipal() {
        UserDetails principal = mock(UserDetails.class);
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        lenient().doReturn(authorities).when(principal).getAuthorities();
        return principal;
    }

    /** The owning partner — the common case for tests that aren't specifically about ownership. */
    private UserDetails ownerPrincipal() {
        return principalFor(ownerId);
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
        @DisplayName("returns seasons in season-number order for published content")
        void returnsSeasonsInOrder() {
            seriesContent.setStatus(ContentStatus.PUBLISHED);
            Season s1 = buildSeason(1);
            Season s2 = buildSeason(2);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(seriesContent));
            when(seasonRepository.findByContentIdOrderBySeasonNumberAsc(contentId))
                .thenReturn(List.of(s1, s2));

            List<SeasonResponse> result = seasonService.listByContent(contentId);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).seasonNumber()).isEqualTo(1);
            assertThat(result.get(1).seasonNumber()).isEqualTo(2);
            verify(seasonRepository).findByContentIdOrderBySeasonNumberAsc(contentId);
        }

        @Test
        @DisplayName("throws 404 when parent content is not published")
        void throws404WhenContentNotPublished() {
            seriesContent.setStatus(ContentStatus.DRAFT);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(seriesContent));

            assertThatThrownBy(() -> seasonService.listByContent(contentId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test
        @DisplayName("throws 404 when content does not exist")
        void throws404WhenContentMissing() {
            when(contentRepository.findById(contentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> seasonService.listByContent(contentId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("getById()")
    class GetByIdTests {

        @Test
        @DisplayName("returns response when season found and content is published")
        void returnsResponseWhenFound() {
            seriesContent.setStatus(ContentStatus.PUBLISHED);
            Season s = buildSeason(1);
            when(seasonRepository.findById(s.getId())).thenReturn(Optional.of(s));

            SeasonResponse result = seasonService.getById(contentId, s.getId());

            assertThat(result.seasonNumber()).isEqualTo(1);
            assertThat(result.contentId()).isEqualTo(contentId);
        }

        @Test
        @DisplayName("throws 404 when season not found")
        void throws404WhenNotFound() {
            UUID missingId = UUID.randomUUID();
            when(seasonRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> seasonService.getById(contentId, missingId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test
        @DisplayName("throws 404 when season belongs to a different content")
        void throws404WhenWrongContent() {
            Season s = buildSeason(1);
            UUID wrongContentId = UUID.randomUUID();
            when(seasonRepository.findById(s.getId())).thenReturn(Optional.of(s));

            assertThatThrownBy(() -> seasonService.getById(wrongContentId, s.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test
        @DisplayName("throws 404 when parent content is not published")
        void throws404WhenContentNotPublished() {
            seriesContent.setStatus(ContentStatus.DRAFT);
            Season s = buildSeason(1);
            when(seasonRepository.findById(s.getId())).thenReturn(Optional.of(s));

            assertThatThrownBy(() -> seasonService.getById(contentId, s.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("listByContentForOwnerOrAdmin()")
    class ListByContentForOwnerOrAdminTests {

        @Test
        @DisplayName("returns seasons for the owning partner even when content is DRAFT")
        void returnsSeasonsForOwnerRegardlessOfStatus() {
            Season s1 = buildSeason(1);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(seriesContent));
            when(seasonRepository.findByContentIdOrderBySeasonNumberAsc(contentId)).thenReturn(List.of(s1));

            List<SeasonResponse> result = seasonService.listByContentForOwnerOrAdmin(contentId, ownerPrincipal());

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("throws 404 when caller is a different partner")
        void throws404WhenNotOwner() {
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(seriesContent));

            assertThatThrownBy(() -> seasonService.listByContentForOwnerOrAdmin(contentId, principalFor(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
        }

        @Test
        @DisplayName("admin can list seasons for content they don't own")
        void adminCanListAnyContent() {
            Season s1 = buildSeason(1);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(seriesContent));
            when(seasonRepository.findByContentIdOrderBySeasonNumberAsc(contentId)).thenReturn(List.of(s1));

            List<SeasonResponse> result = seasonService.listByContentForOwnerOrAdmin(contentId, adminPrincipal());

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("throws 404 when content does not exist")
        void throws404WhenContentMissing() {
            when(contentRepository.findById(contentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> seasonService.listByContentForOwnerOrAdmin(contentId, ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("getByIdForOwnerOrAdmin()")
    class GetByIdForOwnerOrAdminTests {

        @Test
        @DisplayName("returns the season for the owning partner even when content is DRAFT")
        void returnsForOwnerRegardlessOfStatus() {
            Season s = buildSeason(1);
            when(seasonRepository.findById(s.getId())).thenReturn(Optional.of(s));

            SeasonResponse result = seasonService.getByIdForOwnerOrAdmin(contentId, s.getId(), ownerPrincipal());

            assertThat(result.id()).isEqualTo(s.getId());
        }

        @Test
        @DisplayName("throws 404 when caller is a different partner")
        void throws404WhenNotOwner() {
            Season s = buildSeason(1);
            when(seasonRepository.findById(s.getId())).thenReturn(Optional.of(s));

            assertThatThrownBy(() -> seasonService.getByIdForOwnerOrAdmin(contentId, s.getId(), principalFor(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
        }

        @Test
        @DisplayName("throws 404 when season belongs to a different content")
        void throws404WhenWrongContent() {
            Season s = buildSeason(1);
            UUID wrongContentId = UUID.randomUUID();
            when(seasonRepository.findById(s.getId())).thenReturn(Optional.of(s));

            assertThatThrownBy(() -> seasonService.getByIdForOwnerOrAdmin(wrongContentId, s.getId(), ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test
        @DisplayName("admin can get a season for content they don't own")
        void adminCanGetAnyContent() {
            Season s = buildSeason(1);
            when(seasonRepository.findById(s.getId())).thenReturn(Optional.of(s));

            SeasonResponse result = seasonService.getByIdForOwnerOrAdmin(contentId, s.getId(), adminPrincipal());

            assertThat(result.id()).isEqualTo(s.getId());
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
            when(seasonRepository.saveAndFlush(any(Season.class))).thenAnswer(inv -> {
                Season s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                s.setEpisodes(new ArrayList<>());
                return s;
            });

            SeasonResponse result = seasonService.create(contentId, req, ownerPrincipal());

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
            when(seasonRepository.saveAndFlush(any(Season.class))).thenAnswer(inv -> {
                Season s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                s.setEpisodes(new ArrayList<>());
                return s;
            });

            SeasonResponse result = seasonService.create(contentId, req, ownerPrincipal());

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
            when(seasonRepository.saveAndFlush(any(Season.class))).thenAnswer(inv -> {
                Season s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                s.setEpisodes(new ArrayList<>());
                return s;
            });

            SeasonResponse result = seasonService.create(contentId, req, ownerPrincipal());

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

            assertThatThrownBy(() -> seasonService.create(contentId, req, ownerPrincipal()))
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

            assertThatThrownBy(() -> seasonService.create(missingContentId, req, ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test
        @DisplayName("throws 404 when caller is a different partner (not the content owner)")
        void throws404WhenNotOwner() {
            CreateSeasonRequest req = new CreateSeasonRequest(1, "Season One", null, null, null, null);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(seriesContent));

            assertThatThrownBy(() -> seasonService.create(contentId, req, principalFor(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));

            verifyNoInteractions(seasonRepository);
        }

        @Test
        @DisplayName("admin can create a season on content they don't own")
        void adminCanCreateOnAnyContent() {
            CreateSeasonRequest req = new CreateSeasonRequest(1, "Season One", null, null, null, null);
            when(contentRepository.findById(contentId)).thenReturn(Optional.of(seriesContent));
            when(seasonRepository.existsByContentIdAndSeasonNumber(contentId, 1)).thenReturn(false);
            when(seasonRepository.saveAndFlush(any(Season.class))).thenAnswer(inv -> {
                Season s = inv.getArgument(0);
                s.setId(UUID.randomUUID());
                s.setEpisodes(new ArrayList<>());
                return s;
            });

            SeasonResponse result = seasonService.create(contentId, req, adminPrincipal());

            assertThat(result.seasonNumber()).isEqualTo(1);
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

            SeasonResponse result = seasonService.update(contentId, seasonId, req, ownerPrincipal());

            assertThat(result.title()).isEqualTo("Updated Title");
            assertThat(result.posterUrl()).isEqualTo("https://old-poster.jpg"); // unchanged
        }

        @Test
        @DisplayName("throws 404 when season not found")
        void throws404WhenNotFound() {
            UUID missingId = UUID.randomUUID();
            when(seasonRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> seasonService.update(contentId, missingId, new UpdateSeasonRequest(null, null, null, null, null), ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test
        @DisplayName("update() throws 404 when season belongs to different content")
        void updateThrowsWhenWrongContent() {
            Season season = new Season();
            season.setId(UUID.randomUUID());
            season.setContent(seriesContent);
            season.setSeasonNumber(1);
            season.setEpisodes(new java.util.ArrayList<>());

            UUID wrongContentId = UUID.randomUUID();
            when(seasonRepository.findById(season.getId())).thenReturn(Optional.of(season));

            assertThatThrownBy(() -> seasonService.update(wrongContentId, season.getId(), new UpdateSeasonRequest(null, null, null, null, null), ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
        }

        @Test
        @DisplayName("throws 404 when caller is a different partner (not the content owner)")
        void updateThrowsWhenNotOwner() {
            Season s = buildSeason(1);
            when(seasonRepository.findById(s.getId())).thenReturn(Optional.of(s));

            assertThatThrownBy(() -> seasonService.update(
                    contentId, s.getId(), new UpdateSeasonRequest("Hijacked", null, null, null, null), principalFor(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));

            verify(seasonRepository, never()).save(any());
        }

        @Test
        @DisplayName("admin can update a season on content they don't own")
        void adminCanUpdateAnyContent() {
            Season s = buildSeason(1);
            when(seasonRepository.findById(s.getId())).thenReturn(Optional.of(s));
            when(seasonRepository.save(any(Season.class))).thenAnswer(inv -> inv.getArgument(0));

            SeasonResponse result = seasonService.update(
                contentId, s.getId(), new UpdateSeasonRequest("Admin Edit", null, null, null, null), adminPrincipal());

            assertThat(result.title()).isEqualTo("Admin Edit");
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

            seasonService.delete(contentId, s.getId());

            verify(seasonRepository).delete(s);
        }

        @Test
        @DisplayName("throws 404 when season not found")
        void throws404WhenNotFound() {
            UUID missingId = UUID.randomUUID();
            when(seasonRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> seasonService.delete(contentId, missingId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("create() — race condition")
    class CreateRaceConditionTests {

        @Test
        @DisplayName("throws 409 when save fails due to duplicate season number (race condition)")
        void throwsConflictOnSaveViolation() {
            CreateSeasonRequest req = new CreateSeasonRequest(null, "Season 1", null, null, null, null);
            when(contentRepository.findById(seriesContent.getId())).thenReturn(Optional.of(seriesContent));
            when(seasonRepository.findMaxSeasonNumberByContentId(seriesContent.getId())).thenReturn(Optional.of(0));
            when(seasonRepository.saveAndFlush(any(Season.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

            assertThatThrownBy(() -> seasonService.create(seriesContent.getId(), req, ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));
        }
    }
}
