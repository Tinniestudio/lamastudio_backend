package com.tinniestudio.api.modules.homepage.service;

import com.tinniestudio.api.modules.category.repository.CategoryRepository;
import com.tinniestudio.api.modules.homepage.dto.CreateSectionRequest;
import com.tinniestudio.api.modules.homepage.dto.SectionResponse;
import com.tinniestudio.api.modules.homepage.dto.UpdateSectionRequest;
import com.tinniestudio.api.modules.homepage.repository.HomepageSectionRepository;
import com.tinniestudio.api.shared.entity.HomepageSection;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HomepageSectionService {

    private final HomepageSectionRepository repository;
    private final CategoryRepository categoryRepository;

    @Cacheable("homepage-sections")
    @Transactional(readOnly = true)
    public List<SectionResponse> listActive() {
        return repository.findByIsActiveTrueOrderByDisplayOrderAsc()
                .stream().map(SectionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<SectionResponse> listAll() {
        return repository.findAll().stream().map(SectionResponse::from).toList();
    }

    @Transactional
    @CacheEvict(value = "homepage-sections", allEntries = true)
    public SectionResponse create(CreateSectionRequest req) {
        HomepageSection section = new HomepageSection();
        section.setTitle(req.title());
        section.setSectionType(req.sectionType());
        section.setDisplayOrder(req.displayOrder() != null ? req.displayOrder() : 0);
        if (req.categoryId() != null) {
            section.setCategory(categoryRepository.findById(req.categoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found: " + req.categoryId())));
        }
        try {
            // saveAndFlush, not save: with a UUID-generated id, Hibernate has no reason to flush
            // the INSERT immediately, so a unique-constraint violation would otherwise surface at
            // transaction commit — after this method (and its try/catch) has already returned —
            // producing a raw 500 instead of this intended 409.
            return SectionResponse.from(repository.saveAndFlush(section));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Failed to create section — a constraint was violated (sectionType may already exist): " + e.getMostSpecificCause().getMessage());
        }
    }

    @Transactional
    @CacheEvict(value = "homepage-sections", allEntries = true)
    public SectionResponse update(UUID id, UpdateSectionRequest req) {
        HomepageSection section = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Section not found: " + id));
        if (req.title() != null)        section.setTitle(req.title());
        if (req.sectionType() != null)  section.setSectionType(req.sectionType());
        if (req.displayOrder() != null) section.setDisplayOrder(req.displayOrder());
        if (req.isActive() != null)     section.setIsActive(req.isActive());
        if (Boolean.TRUE.equals(req.removeCategory())) {
            section.setCategory(null);
        } else if (req.categoryId() != null) {
            section.setCategory(categoryRepository.findById(req.categoryId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found: " + req.categoryId())));
        }
        return SectionResponse.from(repository.save(section));
    }

    @Transactional
    @CacheEvict(value = "homepage-sections", allEntries = true)
    public void delete(UUID id) {
        HomepageSection section = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Section not found: " + id));
        repository.delete(section);
    }
}
