package com.tinniestudio.api.modules.homepage.repository;

import com.tinniestudio.api.shared.entity.HomepageSection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface HomepageSectionRepository extends JpaRepository<HomepageSection, UUID> {
    List<HomepageSection> findByIsActiveTrueOrderByDisplayOrderAsc();
}
