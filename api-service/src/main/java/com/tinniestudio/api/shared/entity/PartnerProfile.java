package com.tinniestudio.api.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "partner_profiles")
@Getter @Setter @NoArgsConstructor
public class PartnerProfile extends BaseEntity {

    @Column(nullable = false, unique = true)
    private UUID userId;

    private String companyName;
    private String websiteUrl;

    @Column(columnDefinition = "TEXT")
    private String bio;

    private String logoUrl;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal revenueSharePercentage = BigDecimal.valueOf(70.00);

    @Column(nullable = false)
    private Boolean isVerified = true;
}
