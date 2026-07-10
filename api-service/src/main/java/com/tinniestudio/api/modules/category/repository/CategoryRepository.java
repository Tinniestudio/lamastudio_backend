package com.tinniestudio.api.modules.category.repository;

import com.tinniestudio.api.shared.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByIsActiveTrueOrderByDisplayOrderAsc();
    Optional<Category> findBySlug(String slug);
    boolean existsByName(String name);
}
