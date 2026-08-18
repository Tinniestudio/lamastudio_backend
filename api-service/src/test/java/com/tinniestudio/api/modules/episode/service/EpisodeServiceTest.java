package com.tinniestudio.api.modules.episode.service;

import com.tinniestudio.api.modules.episode.dto.CreateEpisodeRequest;
import com.tinniestudio.api.modules.episode.dto.EpisodeResponse;
import com.tinniestudio.api.modules.episode.dto.ReorderEpisodesRequest;
import com.tinniestudio.api.modules.episode.dto.UpdateEpisodeRequest;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.Episode;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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
@DisplayName("EpisodeService")
class EpisodeServiceTest {

    @Mock private EpisodeRepository episodeRepository;
    @Mock private SeasonRepository seasonRepository;

    @InjectMocks private EpisodeService episodeService;

    private Content content;
    private Season season;
    private UUID seasonId;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        UUID contentId = UUID.randomUUID();
        seasonId = UUID.randomUUID();
        ownerId = UUID.randomUUID();

        content = new Content();
        content.setId(contentId);
        content.setTitle("Breaking Bad");
        content.setType(ContentType.SERIES);
        content.setStatus(ContentStatus.DRAFT);
        content.setCreatedBy(ownerId);
        content.setComingSoon(false);
        content.setFeatured(false);
        content.setViewCount(0L);
        content.setMaturityRating(MaturityRating.NOT_RATED);
        content.setCategories(new java.util.HashSet<>());

        season = new Season();
        season.setId(seasonId);
        season.setContent(content);
        season.setSeasonNumber(1);
        season.setTitle("Season 1");
        season.setEpisodes(new ArrayList<>());
    }

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

    private UserDetails ownerPrincipal() {
        return principalFor(ownerId);
    }

    private Episode buildEpisode(int number) {
        Episode ep = new Episode();
        ep.setId(UUID.randomUUID());
        ep.setSeason(season);
        ep.setEpisodeNumber(number);
        ep.setTitle("Episode " + number);
        ep.setDurationSeconds(1800);
        return ep;
    }

    @Nested
    @DisplayName("listBySeason()")
    class ListBySeasonTests {

        @Test
        @DisplayName("returns episodes in episode-number order when content is published")
        void returnsEpisodesInOrder() {
            content.setStatus(ContentStatus.PUBLISHED);
            Episode ep1 = buildEpisode(1);
            Episode ep2 = buildEpisode(2);
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(episodeRepository.findBySeasonIdOrderByEpisodeNumberAsc(seasonId))
                .thenReturn(List.of(ep1, ep2));

            List<EpisodeResponse> result = episodeService.listBySeason(seasonId);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).episodeNumber()).isEqualTo(1);
            assertThat(result.get(1).episodeNumber()).isEqualTo(2);
            verify(episodeRepository).findBySeasonIdOrderByEpisodeNumberAsc(seasonId);
        }

        @Test
        @DisplayName("throws 404 when parent content is not published")
        void throws404WhenContentNotPublished() {
            content.setStatus(ContentStatus.DRAFT);
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));

            assertThatThrownBy(() -> episodeService.listBySeason(seasonId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test
        @DisplayName("throws 404 when season not found")
        void throws404WhenSeasonMissing() {
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> episodeService.listBySeason(seasonId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("getById()")
    class GetByIdTests {

        @Test
        @DisplayName("returns response when found and content is published")
        void returnsResponseWhenFound() {
            content.setStatus(ContentStatus.PUBLISHED);
            Episode ep = buildEpisode(1); // buildEpisode sets ep.setSeason(season) where season.getId() == seasonId
            when(episodeRepository.findById(ep.getId())).thenReturn(Optional.of(ep));

            EpisodeResponse result = episodeService.getById(seasonId, ep.getId());

            assertThat(result.episodeNumber()).isEqualTo(1);
            assertThat(result.seasonId()).isEqualTo(seasonId);
        }

        @Test
        @DisplayName("throws 404 when parent content is not published")
        void throws404WhenContentNotPublished() {
            content.setStatus(ContentStatus.DRAFT);
            Episode ep = buildEpisode(1);
            when(episodeRepository.findById(ep.getId())).thenReturn(Optional.of(ep));

            assertThatThrownBy(() -> episodeService.getById(seasonId, ep.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test
        @DisplayName("throws 404 when not found")
        void throws404WhenNotFound() {
            UUID missingId = UUID.randomUUID();
            when(episodeRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> episodeService.getById(seasonId, missingId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test
        @DisplayName("throws 404 when episode belongs to wrong season")
        void throws404WhenWrongSeason() {
            UUID otherSeasonId = UUID.randomUUID();

            Season otherSeason = new Season();
            otherSeason.setId(otherSeasonId);

            Episode ep = new Episode();
            ep.setId(UUID.randomUUID());
            ep.setSeason(otherSeason);
            ep.setEpisodeNumber(1);
            ep.setTitle("Ep 1");

            when(episodeRepository.findById(ep.getId())).thenReturn(Optional.of(ep));

            assertThatThrownBy(() -> episodeService.getById(seasonId, ep.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("listBySeasonForOwnerOrAdmin()")
    class ListBySeasonForOwnerOrAdminTests {

        @Test
        @DisplayName("returns episodes for the owning partner even when content is DRAFT")
        void returnsEpisodesForOwnerRegardlessOfStatus() {
            Episode e1 = buildEpisode(1);
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(episodeRepository.findBySeasonIdOrderByEpisodeNumberAsc(seasonId)).thenReturn(List.of(e1));

            List<EpisodeResponse> result = episodeService.listBySeasonForOwnerOrAdmin(seasonId, ownerPrincipal());

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("throws 404 when caller is a different partner")
        void throws404WhenNotOwner() {
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));

            assertThatThrownBy(() -> episodeService.listBySeasonForOwnerOrAdmin(seasonId, principalFor(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
        }

        @Test
        @DisplayName("admin can list episodes for a season they don't own")
        void adminCanListAnySeason() {
            Episode e1 = buildEpisode(1);
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(episodeRepository.findBySeasonIdOrderByEpisodeNumberAsc(seasonId)).thenReturn(List.of(e1));

            List<EpisodeResponse> result = episodeService.listBySeasonForOwnerOrAdmin(seasonId, adminPrincipal());

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("throws 404 when season does not exist")
        void throws404WhenSeasonMissing() {
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> episodeService.listBySeasonForOwnerOrAdmin(seasonId, ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }
    }

    @Nested
    @DisplayName("getByIdForOwnerOrAdmin()")
    class GetByIdForOwnerOrAdminTests {

        @Test
        @DisplayName("returns the episode for the owning partner even when content is DRAFT")
        void returnsForOwnerRegardlessOfStatus() {
            Episode e = buildEpisode(1);
            when(episodeRepository.findById(e.getId())).thenReturn(Optional.of(e));

            EpisodeResponse result = episodeService.getByIdForOwnerOrAdmin(seasonId, e.getId(), ownerPrincipal());

            assertThat(result.id()).isEqualTo(e.getId());
        }

        @Test
        @DisplayName("throws 404 when caller is a different partner")
        void throws404WhenNotOwner() {
            Episode e = buildEpisode(1);
            when(episodeRepository.findById(e.getId())).thenReturn(Optional.of(e));

            assertThatThrownBy(() -> episodeService.getByIdForOwnerOrAdmin(seasonId, e.getId(), principalFor(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
        }

        @Test
        @DisplayName("throws 404 when episode belongs to a different season")
        void throws404WhenWrongSeason() {
            Episode e = buildEpisode(1);
            UUID wrongSeasonId = UUID.randomUUID();
            when(episodeRepository.findById(e.getId())).thenReturn(Optional.of(e));

            assertThatThrownBy(() -> episodeService.getByIdForOwnerOrAdmin(wrongSeasonId, e.getId(), ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test
        @DisplayName("admin can get an episode for a season they don't own")
        void adminCanGetAnySeason() {
            Episode e = buildEpisode(1);
            when(episodeRepository.findById(e.getId())).thenReturn(Optional.of(e));

            EpisodeResponse result = episodeService.getByIdForOwnerOrAdmin(seasonId, e.getId(), adminPrincipal());

            assertThat(result.id()).isEqualTo(e.getId());
        }
    }

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("auto-numbers when episodeNumber is null (MAX + 1)")
        void autoNumbersWhenEpisodeNumberNull() {
            CreateEpisodeRequest req = new CreateEpisodeRequest(
                null, "New Episode", null, null, 2400, null
            );
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(episodeRepository.findMaxEpisodeNumberBySeasonId(seasonId)).thenReturn(Optional.of(2));
            when(episodeRepository.saveAndFlush(any(Episode.class))).thenAnswer(inv -> {
                Episode ep = inv.getArgument(0);
                ep.setId(UUID.randomUUID());
                return ep;
            });

            EpisodeResponse result = episodeService.create(seasonId, req, ownerPrincipal());

            assertThat(result.episodeNumber()).isEqualTo(3);
        }

        @Test
        @DisplayName("auto-numbers to 1 when no episodes exist yet")
        void autoNumbersToOneWhenNoEpisodes() {
            CreateEpisodeRequest req = new CreateEpisodeRequest(
                null, "Pilot", null, null, 2400, null
            );
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(episodeRepository.findMaxEpisodeNumberBySeasonId(seasonId)).thenReturn(Optional.empty());
            when(episodeRepository.saveAndFlush(any(Episode.class))).thenAnswer(inv -> {
                Episode ep = inv.getArgument(0);
                ep.setId(UUID.randomUUID());
                return ep;
            });

            EpisodeResponse result = episodeService.create(seasonId, req, ownerPrincipal());

            assertThat(result.episodeNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("uses provided episodeNumber when explicitly given")
        void usesProvidedEpisodeNumber() {
            CreateEpisodeRequest req = new CreateEpisodeRequest(
                5, "Episode Five", null, LocalDate.of(2024, 1, 1), 1800, null
            );
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(episodeRepository.existsBySeasonIdAndEpisodeNumber(seasonId, 5)).thenReturn(false);
            when(episodeRepository.saveAndFlush(any(Episode.class))).thenAnswer(inv -> {
                Episode ep = inv.getArgument(0);
                ep.setId(UUID.randomUUID());
                return ep;
            });

            EpisodeResponse result = episodeService.create(seasonId, req, ownerPrincipal());

            assertThat(result.episodeNumber()).isEqualTo(5);
            assertThat(result.title()).isEqualTo("Episode Five");
        }

        @Test
        @DisplayName("throws 409 when explicit episodeNumber already exists")
        void throws409OnDuplicateEpisodeNumber() {
            CreateEpisodeRequest req = new CreateEpisodeRequest(
                2, "Duplicate Episode", null, null, null, null
            );
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(episodeRepository.existsBySeasonIdAndEpisodeNumber(seasonId, 2)).thenReturn(true);

            assertThatThrownBy(() -> episodeService.create(seasonId, req, ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));
        }

        @Test
        @DisplayName("throws 409 when DataIntegrityViolationException from save (race condition)")
        void throws409OnDataIntegrityViolation() {
            CreateEpisodeRequest req = new CreateEpisodeRequest(
                null, "Race Episode", null, null, null, null
            );
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(episodeRepository.findMaxEpisodeNumberBySeasonId(seasonId)).thenReturn(Optional.of(0));
            when(episodeRepository.saveAndFlush(any(Episode.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

            assertThatThrownBy(() -> episodeService.create(seasonId, req, ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));
        }

        @Test
        @DisplayName("throws 404 when season not found")
        void throws404WhenSeasonNotFound() {
            UUID missingSeasonId = UUID.randomUUID();
            CreateEpisodeRequest req = new CreateEpisodeRequest(
                1, "Episode One", null, null, null, null
            );
            when(seasonRepository.findById(missingSeasonId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> episodeService.create(missingSeasonId, req, ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        }

        @Test
        @DisplayName("throws 404 when caller is a different partner (not the content owner)")
        void throws404WhenNotOwner() {
            CreateEpisodeRequest req = new CreateEpisodeRequest(1, "Episode One", null, null, null, null);
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));

            assertThatThrownBy(() -> episodeService.create(seasonId, req, principalFor(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));

            verifyNoInteractions(episodeRepository);
        }

        @Test
        @DisplayName("admin can create an episode on content they don't own")
        void adminCanCreateOnAnyContent() {
            CreateEpisodeRequest req = new CreateEpisodeRequest(1, "Episode One", null, null, null, null);
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(episodeRepository.existsBySeasonIdAndEpisodeNumber(seasonId, 1)).thenReturn(false);
            when(episodeRepository.saveAndFlush(any(Episode.class))).thenAnswer(inv -> {
                Episode ep = inv.getArgument(0);
                ep.setId(UUID.randomUUID());
                return ep;
            });

            EpisodeResponse result = episodeService.create(seasonId, req, adminPrincipal());

            assertThat(result.episodeNumber()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("update()")
    class UpdateTests {

        @Test
        @DisplayName("partial update — only non-null fields applied")
        void partialUpdate() {
            Episode ep = buildEpisode(1);
            ep.setThumbnailUrl("https://old-thumb.jpg");
            UUID epId = ep.getId();

            UpdateEpisodeRequest req = new UpdateEpisodeRequest(
                "Updated Title", null, null, null, null
            );
            when(episodeRepository.findById(epId)).thenReturn(Optional.of(ep));
            when(episodeRepository.save(any(Episode.class))).thenAnswer(inv -> inv.getArgument(0));

            EpisodeResponse result = episodeService.update(seasonId, epId, req, ownerPrincipal());

            assertThat(result.title()).isEqualTo("Updated Title");
            assertThat(result.thumbnailUrl()).isEqualTo("https://old-thumb.jpg"); // unchanged
        }

        @Test
        @DisplayName("throws 404 when episode belongs to wrong season")
        void throws404WhenWrongSeason() {
            Episode ep = buildEpisode(1);
            UUID epId = ep.getId();
            UUID wrongSeasonId = UUID.randomUUID();

            when(episodeRepository.findById(epId)).thenReturn(Optional.of(ep));

            assertThatThrownBy(() -> episodeService.update(wrongSeasonId, epId, new UpdateEpisodeRequest(null, null, null, null, null), ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
        }

        @Test
        @DisplayName("throws 404 when caller is a different partner (not the content owner)")
        void throws404WhenNotOwner() {
            Episode ep = buildEpisode(1);
            when(episodeRepository.findById(ep.getId())).thenReturn(Optional.of(ep));

            assertThatThrownBy(() -> episodeService.update(
                    seasonId, ep.getId(), new UpdateEpisodeRequest("Hijacked", null, null, null, null), principalFor(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));

            verify(episodeRepository, never()).save(any());
        }

        @Test
        @DisplayName("admin can update an episode on content they don't own")
        void adminCanUpdateAnyContent() {
            Episode ep = buildEpisode(1);
            when(episodeRepository.findById(ep.getId())).thenReturn(Optional.of(ep));
            when(episodeRepository.save(any(Episode.class))).thenAnswer(inv -> inv.getArgument(0));

            EpisodeResponse result = episodeService.update(
                seasonId, ep.getId(), new UpdateEpisodeRequest("Admin Edit", null, null, null, null), adminPrincipal());

            assertThat(result.title()).isEqualTo("Admin Edit");
        }
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("calls repository.delete(entity) when found and season matches")
        void deletesWhenFound() {
            Episode ep = buildEpisode(1);
            when(episodeRepository.findById(ep.getId())).thenReturn(Optional.of(ep));

            episodeService.delete(seasonId, ep.getId());

            verify(episodeRepository).delete(ep);
        }

        @Test
        @DisplayName("throws 404 when episode belongs to wrong season")
        void throws404WhenWrongSeason() {
            Episode ep = buildEpisode(1);
            UUID wrongSeasonId = UUID.randomUUID();
            when(episodeRepository.findById(ep.getId())).thenReturn(Optional.of(ep));

            assertThatThrownBy(() -> episodeService.delete(wrongSeasonId, ep.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));
        }
    }

    @Nested
    @DisplayName("reorder()")
    class ReorderTests {

        @Test
        @DisplayName("reassigns episode numbers 1..N in provided order")
        void reassignsEpisodeNumbers() {
            Episode ep1 = buildEpisode(2); // currently episode 2
            Episode ep2 = buildEpisode(1); // currently episode 1
            UUID ep1Id = ep1.getId();
            UUID ep2Id = ep2.getId();

            // reorder: ep1 should become #1, ep2 should become #2
            ReorderEpisodesRequest req = new ReorderEpisodesRequest(List.of(ep1Id, ep2Id));

            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(episodeRepository.findBySeasonIdOrderByEpisodeNumberAsc(seasonId)).thenReturn(List.of(ep1, ep2));
            when(episodeRepository.findById(ep1Id)).thenReturn(Optional.of(ep1));
            when(episodeRepository.findById(ep2Id)).thenReturn(Optional.of(ep2));
            when(episodeRepository.save(any(Episode.class))).thenAnswer(inv -> inv.getArgument(0));

            episodeService.reorder(seasonId, req, ownerPrincipal());

            // After phase 2, ep1 should be 1 and ep2 should be 2
            assertThat(ep1.getEpisodeNumber()).isEqualTo(1);
            assertThat(ep2.getEpisodeNumber()).isEqualTo(2);
            // save should be called twice per episode (phase 1 + phase 2)
            verify(episodeRepository, times(4)).save(any(Episode.class));
        }

        @Test
        @DisplayName("throws 400 when reorder list is partial (not all episodes included)")
        void throws400OnPartialReorderList() {
            Episode ep1 = buildEpisode(1);
            Episode ep2 = buildEpisode(2);

            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(episodeRepository.findBySeasonIdOrderByEpisodeNumberAsc(seasonId)).thenReturn(List.of(ep1, ep2));

            ReorderEpisodesRequest req = new ReorderEpisodesRequest(List.of(ep1.getId())); // only 1 of 2

            assertThatThrownBy(() -> episodeService.reorder(seasonId, req, ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("throws 400 when reorder list contains episode from different season")
        void throws400WhenEpisodeFromWrongSeason() {
            UUID otherSeasonId = UUID.randomUUID();

            Season rightSeason = new Season(); rightSeason.setId(seasonId);
            Season wrongSeason = new Season(); wrongSeason.setId(otherSeasonId);

            Episode ep1 = new Episode(); ep1.setId(UUID.randomUUID()); ep1.setEpisodeNumber(1); ep1.setTitle("E1");
            ep1.setSeason(rightSeason);

            Episode ep2 = new Episode(); ep2.setId(UUID.randomUUID()); ep2.setEpisodeNumber(2); ep2.setTitle("E2");
            ep2.setSeason(wrongSeason); // wrong season

            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));
            when(episodeRepository.findBySeasonIdOrderByEpisodeNumberAsc(seasonId)).thenReturn(List.of(ep1, ep2));
            when(episodeRepository.findById(ep1.getId())).thenReturn(Optional.of(ep1));
            when(episodeRepository.findById(ep2.getId())).thenReturn(Optional.of(ep2));
            when(episodeRepository.save(any(Episode.class))).thenAnswer(inv -> inv.getArgument(0));

            ReorderEpisodesRequest req = new ReorderEpisodesRequest(List.of(ep1.getId(), ep2.getId()));

            assertThatThrownBy(() -> episodeService.reorder(seasonId, req, ownerPrincipal()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("throws 404 when caller is a different partner (not the content owner)")
        void throws404WhenNotOwner() {
            Episode ep1 = buildEpisode(1);
            when(seasonRepository.findById(seasonId)).thenReturn(Optional.of(season));

            ReorderEpisodesRequest req = new ReorderEpisodesRequest(List.of(ep1.getId()));

            assertThatThrownBy(() -> episodeService.reorder(seasonId, req, principalFor(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404));

            verifyNoInteractions(episodeRepository);
        }
    }
}
