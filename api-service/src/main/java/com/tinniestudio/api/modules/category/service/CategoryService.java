package com.tinniestudio.api.modules.category.service;

import com.tinniestudio.api.modules.category.dto.CategoryResponse;
import com.tinniestudio.api.modules.category.dto.CreateCategoryRequest;
import com.tinniestudio.api.modules.category.dto.UpdateCategoryRequest;
import com.tinniestudio.api.modules.category.repository.CategoryRepository;
import com.tinniestudio.api.shared.entity.Category;
import com.tinniestudio.api.shared.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final StorageService storageService;

    @Cacheable("categories")
    @Transactional(readOnly = true)
    public List<CategoryResponse> listActive() {
        return categoryRepository.findByIsActiveTrueOrderByDisplayOrderAsc()
                .stream().map(CategoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> listAll() {
        return categoryRepository.findAll().stream().map(CategoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse getBySlug(String slug) {
        return categoryRepository.findBySlug(slug)
                .map(CategoryResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found: " + slug));
    }

    @Transactional
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse create(CreateCategoryRequest req, MultipartFile poster) {
        Category category = new Category();
        category.setName(req.name());
        category.setDescription(req.description());
        category.setDisplayOrder(req.displayOrder() != null ? req.displayOrder() : 0);
        if (poster != null && !poster.isEmpty()) {
            category.setPosterUrl(uploadPoster(poster, req.name()));
        }
        try {
            return CategoryResponse.from(categoryRepository.save(category));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Category name already exists: " + req.name());
        }
    }

    @Transactional
    @CacheEvict(value = "categories", allEntries = true)
    public CategoryResponse update(UUID id, UpdateCategoryRequest req, MultipartFile poster) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found: " + id));
        if (req.name() != null)         category.setName(req.name());
        if (req.description() != null)  category.setDescription(req.description());
        if (req.displayOrder() != null) category.setDisplayOrder(req.displayOrder());
        if (req.isActive() != null)     category.setIsActive(req.isActive());
        if (poster != null && !poster.isEmpty()) {
            category.setPosterUrl(uploadPoster(poster, category.getName()));
        }
        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    @CacheEvict(value = "categories", allEntries = true)
    public void delete(UUID id) {
        if (!categoryRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found: " + id);
        }
        categoryRepository.deleteById(id);
    }

    private String uploadPoster(MultipartFile poster, String categoryName) {
        try {
            String sanitizedName = categoryName.toLowerCase().replaceAll("[^a-z0-9]", "-");
            String ext = getExtension(poster.getOriginalFilename());
            String key = "posters/categories/" + sanitizedName + "." + ext;
            String contentType = poster.getContentType() != null ? poster.getContentType() : "application/octet-stream";
            return storageService.uploadFile(key, poster.getBytes(), contentType);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload category poster");
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "jpg";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
