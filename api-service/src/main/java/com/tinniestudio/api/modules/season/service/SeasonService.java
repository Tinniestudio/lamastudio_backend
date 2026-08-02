package com.tinniestudio.api.modules.season.service;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.season.dto.CreateSeasonRequest;
import com.tinniestudio.api.modules.season.dto.SeasonResponse;
import com.tinniestudio.api.modules.season.dto.UpdateSeasonRequest;
import com.tinniestudio.api.modules.season.repository.SeasonRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.Season;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SeasonService {

    private final SeasonRepository seasonRepository;
    private final ContentRepository contentRepository;

    @Transactional(readOnly = true)
    public List<SeasonResponse> listByContent(UUID contentId) {
        Content content = contentRepository.findById(contentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + contentId));
        if (content.getStatus() != ContentStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + contentId);
        }
        return seasonRepository.findByContentIdOrderBySeasonNumberAsc(contentId)
                .stream().map(SeasonResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public SeasonResponse getById(UUID contentId, UUID id) {
        Season season = seasonRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + id));
        if (!season.getContent().getId().equals(contentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + id);
        }
        if (season.getContent().getStatus() != ContentStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + id);
        }
        return SeasonResponse.from(season);
    }

    @Transactional
    public SeasonResponse create(UUID contentId, CreateSeasonRequest req) {
        Content content = contentRepository.findById(contentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found: " + contentId));

        int seasonNumber;
        if (req.seasonNumber() != null) {
            if (seasonRepository.existsByContentIdAndSeasonNumber(contentId, req.seasonNumber())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Season number " + req.seasonNumber() + " already exists for this content");
            }
            seasonNumber = req.seasonNumber();
        } else {
            seasonNumber = seasonRepository.findMaxSeasonNumberByContentId(contentId).orElse(0) + 1;
        }

        Season season = new Season();
        season.setContent(content);
        season.setSeasonNumber(seasonNumber);
        season.setTitle(req.title());
        season.setDescription(req.description());
        season.setReleaseDate(req.releaseDate());
        season.setPosterUrl(req.posterUrl());
        season.setThumbnailUrl(req.thumbnailUrl());
        try {
            return SeasonResponse.from(seasonRepository.save(season));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Season number " + seasonNumber + " already exists for this content");
        }
    }

    @Transactional
    public SeasonResponse update(UUID contentId, UUID id, UpdateSeasonRequest req) {
        Season season = seasonRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + id));
        if (!season.getContent().getId().equals(contentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + id);
        }
        if (req.title() != null)        season.setTitle(req.title());
        if (req.description() != null)  season.setDescription(req.description());
        if (req.releaseDate() != null)  season.setReleaseDate(req.releaseDate());
        if (req.posterUrl() != null)    season.setPosterUrl(req.posterUrl());
        if (req.thumbnailUrl() != null) season.setThumbnailUrl(req.thumbnailUrl());
        return SeasonResponse.from(seasonRepository.save(season));
    }

    @Transactional
    public void delete(UUID contentId, UUID id) {
        Season season = seasonRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + id));
        if (!season.getContent().getId().equals(contentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Season not found: " + id);
        }
        seasonRepository.delete(season);
    }
}
