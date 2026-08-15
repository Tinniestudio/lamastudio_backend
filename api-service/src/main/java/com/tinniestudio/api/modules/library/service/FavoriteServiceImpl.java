package com.tinniestudio.api.modules.library.service;

import com.tinniestudio.api.modules.content.dto.ContentSummaryResponse;
import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.library.dto.FavoriteResponse;
import com.tinniestudio.api.modules.library.repository.FavoriteRepository;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.Favorite;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

    private static final int MAX_FAVORITES = 500;

    private final FavoriteRepository favoriteRepo;
    private final ContentRepository contentRepo;

    @Override
    @Transactional
    public void add(UUID userId, UUID contentId) {
        if (favoriteRepo.existsByUserIdAndContentId(userId, contentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Content is already in favorites");
        }

        if (favoriteRepo.countByUserId(userId) >= MAX_FAVORITES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Favorites limit of " + MAX_FAVORITES + " reached");
        }

        if (!contentRepo.existsById(contentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found");
        }

        Favorite favorite = new Favorite();
        favorite.setUserId(userId);
        favorite.setContentId(contentId);
        favoriteRepo.save(favorite);
    }

    @Override
    @Transactional
    public void remove(UUID userId, UUID contentId) {
        Favorite favorite = favoriteRepo.findByUserIdAndContentId(userId, contentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Favorite not found"));
        favoriteRepo.delete(favorite);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FavoriteResponse> list(UUID userId, Pageable pageable) {
        Page<Favorite> favoritePage = favoriteRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable);

        List<UUID> contentIds = favoritePage.getContent().stream()
                .map(Favorite::getContentId)
                .toList();

        Map<UUID, ContentSummaryResponse> contentMap = contentRepo.findAllById(contentIds).stream()
                .collect(Collectors.toMap(Content::getId, ContentSummaryResponse::from));

        return favoritePage.map(fav -> new FavoriteResponse(
                fav.getId(),
                fav.getContentId(),
                fav.getCreatedAt(),
                contentMap.get(fav.getContentId())
        ));
    }
}
