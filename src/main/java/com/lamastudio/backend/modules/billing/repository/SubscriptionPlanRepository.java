package com.lamastudio.backend.modules.billing.repository;

import com.lamastudio.backend.shared.entity.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

    Optional<SubscriptionPlan> findByNameIgnoreCase(String name);

    List<SubscriptionPlan> findByNameInAndIsActiveTrue(Collection<String> names);

    List<SubscriptionPlan> findAllByOrderByPriceAsc();
}
