package com.tinniestudio.api.modules.contenttype.service;

import com.tinniestudio.api.modules.contenttype.dto.ContentTypeResponse;
import com.tinniestudio.api.modules.contenttype.dto.CreateContentTypeRequest;
import com.tinniestudio.api.modules.contenttype.dto.UpdateContentTypeRequest;
import com.tinniestudio.api.modules.contenttype.repository.ContentTypeRepository;
import com.tinniestudio.api.shared.entity.ContentType;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContentTypeService {

    private final ContentTypeRepository contentTypeRepository;

    @Cacheable("content-types")
    @Transactional(readOnly = true)
    public List<ContentTypeResponse> listActive() {
        return contentTypeRepository.findByIsActiveTrueOrderByDisplayOrderAsc()
                .stream().map(ContentTypeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ContentTypeResponse> listAll() {
        return contentTypeRepository.findAll().stream().map(ContentTypeResponse::from).toList();
    }

    @Transactional
    @CacheEvict(value = "content-types", allEntries = true)
    public ContentTypeResponse create(CreateContentTypeRequest req) {
        ContentType type = new ContentType();
        type.setName(req.name());
        type.setDescription(req.description());
        type.setStructuralKind(req.structuralKind());
        type.setDisplayOrder(req.displayOrder() != null ? req.displayOrder() : 0);
        try {
            // saveAndFlush: see ContentService.create for why plain save() doesn't reliably
            // surface the constraint violation inside this try/catch.
            return ContentTypeResponse.from(contentTypeRepository.saveAndFlush(type));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Content type name already exists: " + req.name());
        }
    }

    @Transactional
    @CacheEvict(value = "content-types", allEntries = true)
    public ContentTypeResponse update(UUID id, UpdateContentTypeRequest req) {
        ContentType type = contentTypeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content type not found: " + id));
        if (req.name() != null)           type.setName(req.name());
        if (req.description() != null)    type.setDescription(req.description());
        if (req.structuralKind() != null) type.setStructuralKind(req.structuralKind());
        if (req.displayOrder() != null)   type.setDisplayOrder(req.displayOrder());
        if (req.isActive() != null)       type.setIsActive(req.isActive());
        return ContentTypeResponse.from(contentTypeRepository.save(type));
    }

    @Transactional
    @CacheEvict(value = "content-types", allEntries = true)
    public void delete(UUID id) {
        ContentType type = contentTypeRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content type not found: " + id));
        try {
            contentTypeRepository.delete(type);
            contentTypeRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Content type is referenced by existing content and cannot be deleted");
        }
    }
}
