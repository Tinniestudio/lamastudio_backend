package com.tinniestudio.api.modules.episode.service;

import com.tinniestudio.api.modules.episode.dto.CreateEpisodeRequest;
import com.tinniestudio.api.modules.episode.dto.EpisodeResponse;
import com.tinniestudio.api.modules.episode.dto.ReorderEpisodesRequest;
import com.tinniestudio.api.modules.episode.dto.UpdateEpisodeRequest;
import com.tinniestudio.api.modules.episode.repository.EpisodeRepository;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.shared.entity.Episode;
import com.tinniestudio.api.shared.entity.Season;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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
        return EpisodeResponse.from(episode);
    }

    @Transactional
    public EpisodeResponse create(UUID seasonId, CreateEpisodeRequest req) {
        Season season = seasonRepository.findById(seasonId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + seasonId));

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
            return EpisodeResponse.from(episodeRepository.save(episode));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Episode number " + episodeNumber + " already exists in this season");
        }
    }

    @Transactional
    public EpisodeResponse update(UUID seasonId, UUID id, UpdateEpisodeRequest req) {
        Episode episode = episodeRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id));
        if (!episode.getSeason().getId().equals(seasonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id);
        }
        if (req.title() != null)           episode.setTitle(req.title());
        if (req.description() != null)     episode.setDescription(req.description());
        if (req.releaseDate() != null)     episode.setReleaseDate(req.releaseDate());
        if (req.durationSeconds() != null) episode.setDurationSeconds(req.durationSeconds());
        if (req.thumbnailUrl() != null)    episode.setThumbnailUrl(req.thumbnailUrl());
        return EpisodeResponse.from(episodeRepository.save(episode));
    }

    @Transactional
    public void delete(UUID seasonId, UUID id) {
        Episode episode = episodeRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id));
        if (!episode.getSeason().getId().equals(seasonId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found: " + id);
        }
        episodeRepository.delete(episode);
    }

    @Transactional
    public void reorder(UUID seasonId, ReorderEpisodesRequest req) {
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
}
