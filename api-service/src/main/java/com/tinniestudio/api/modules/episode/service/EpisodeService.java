package com.tinniestudio.api.modules.episode.service;

import com.tinniestudio.api.modules.episode.dto.CreateEpisodeRequest;
import com.tinniestudio.api.modules.episode.dto.EpisodeResponse;
import com.tinniestudio.api.modules.episode.dto.ReorderEpisodesRequest;
import com.tinniestudio.api.modules.episode.dto.UpdateEpisodeRequest;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.Episode;
import com.tinniestudio.api.shared.entity.Season;
import com.tinniestudio.api.shared.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EpisodeService {

    private final EpisodeRepository episodeRepository;
    private final SeasonRepository seasonRepository;

    @Transactional(readOnly = true)
    public List<EpisodeResponse> listBySeason(UUID seasonId) {
        Season season = seasonRepository.findById(seasonId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + seasonId));
        if (season.getContent().getStatus() != ContentStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + seasonId);
        }
        return episodeRepository.findBySeasonIdOrderByEpisodeNumberAsc(seasonId)
                .stream().map(EpisodeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public EpisodeResponse getById(UUID seasonId, UUID id) {
        Episode episode = episodeRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id));
        if (!episode.getSeason().getId().equals(seasonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id);
        }
        if (episode.getSeason().getContent().getStatus() != ContentStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id);
        }
        return EpisodeResponse.from(episode);
    }

    @Transactional(readOnly = true)
    public List<EpisodeResponse> listBySeasonForOwnerOrAdmin(UUID seasonId, UserDetails principal) {
        Season season = seasonRepository.findById(seasonId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + seasonId));
        assertOwnedByCallerOrAdmin(season.getContent(), principal);
        return episodeRepository.findBySeasonIdOrderByEpisodeNumberAsc(seasonId)
                .stream().map(EpisodeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public EpisodeResponse getByIdForOwnerOrAdmin(UUID seasonId, UUID id, UserDetails principal) {
        Episode episode = episodeRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id));
        if (!episode.getSeason().getId().equals(seasonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id);
        }
        assertOwnedByCallerOrAdmin(episode.getSeason().getContent(), principal);
        return EpisodeResponse.from(episode);
    }

    @Transactional
    public EpisodeResponse create(UUID seasonId, CreateEpisodeRequest req, UserDetails principal) {
        Season season = seasonRepository.findById(seasonId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + seasonId));
        assertOwnedByCallerOrAdmin(season.getContent(), principal);

        int episodeNumber;
        if (req.episodeNumber() != null) {
            if (episodeRepository.existsBySeasonIdAndEpisodeNumber(seasonId, req.episodeNumber())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Episode number " + req.episodeNumber() + " already exists in this season");
            }
            episodeNumber = req.episodeNumber();
        } else {
            episodeNumber = episodeRepository.findMaxEpisodeNumberBySeasonId(seasonId).orElse(0) + 1;
        }

        Episode episode = new Episode();
        episode.setSeason(season);
        episode.setEpisodeNumber(episodeNumber);
        episode.setTitle(req.title());
        episode.setDescription(req.description());
        episode.setReleaseDate(req.releaseDate());
        episode.setDurationSeconds(req.durationSeconds());
        episode.setThumbnailUrl(req.thumbnailUrl());
        try {
            // saveAndFlush: see ContentService.create for why plain save() doesn't reliably
            // surface the constraint violation inside this try/catch.
            return EpisodeResponse.from(episodeRepository.saveAndFlush(episode));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Episode number " + episodeNumber + " already exists in this season");
        }
    }

    @Transactional
    public EpisodeResponse update(UUID seasonId, UUID id, UpdateEpisodeRequest req, UserDetails principal) {
        Episode episode = episodeRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id));
        if (!episode.getSeason().getId().equals(seasonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id);
        }
        assertOwnedByCallerOrAdmin(episode.getSeason().getContent(), principal);
        if (req.title() != null) {
            if (req.title().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must not be blank");
            episode.setTitle(req.title());
        }
        if (req.description() != null)     episode.setDescription(req.description());
        if (req.releaseDate() != null)     episode.setReleaseDate(req.releaseDate());
        if (req.durationSeconds() != null) episode.setDurationSeconds(req.durationSeconds());
        if (req.thumbnailUrl() != null)    episode.setThumbnailUrl(req.thumbnailUrl());
        return EpisodeResponse.from(episodeRepository.save(episode));
    }

    @Transactional
    public void delete(UUID seasonId, UUID id) {
        // Delete stays ADMIN-only at the controller (@PreAuthorize("hasRole('ADMIN')")), so no
        // ownership check needed here — every caller who reaches this is already an admin.
        Episode episode = episodeRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id));
        if (!episode.getSeason().getId().equals(seasonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id);
        }
        episodeRepository.delete(episode);
    }

    @Transactional
    public void reorder(UUID seasonId, ReorderEpisodesRequest req, UserDetails principal) {
        Season season = seasonRepository.findById(seasonId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + seasonId));
        assertOwnedByCallerOrAdmin(season.getContent(), principal);

        List<UUID> orderedIds = req.episodeIds();
        List<Episode> allEpisodes = episodeRepository.findBySeasonIdOrderByEpisodeNumberAsc(seasonId);
        if (allEpisodes.size() != orderedIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Reorder list must contain all " + allEpisodes.size() + " episodes in the season (received " + orderedIds.size() + ")");
        }
        int tempBase = -(orderedIds.size() * 1000);

        // Phase 1: assign temp negative numbers (avoids UNIQUE conflicts during reorder)
        int temp = tempBase;
        for (UUID epId : orderedIds) {
            Episode ep = episodeRepository.findById(epId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + epId));
            if (!ep.getSeason().getId().equals(seasonId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Episode " + epId + " does not belong to season " + seasonId);
            }
            ep.setEpisodeNumber(temp++);
            episodeRepository.save(ep);
        }

        // Phase 2: assign final 1..N numbers
        int number = 1;
        for (UUID epId : orderedIds) {
            Episode ep = episodeRepository.findById(epId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found during reorder: " + epId));
            ep.setEpisodeNumber(number++);
            episodeRepository.save(ep);
        }
    }

    /**
     * 404 (not 403) on a content the caller doesn't own — same enumeration-safe pattern as
     * {@code AdminContentController.assertOwnedByCallerOrAdmin},
     * {@code SeasonService.assertOwnedByCallerOrAdmin}, and
     * {@code PartnerServiceImpl.assertOwnedByPartner}. Admins bypass entirely.
     */
    private void assertOwnedByCallerOrAdmin(Content content, UserDetails principal) {
        if (CurrentUser.isAdmin(principal)) {
            return;
        }
        UUID callerId = CurrentUser.id(principal);
        if (!callerId.equals(content.getCreatedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + content.getId());
        }
    }
}
