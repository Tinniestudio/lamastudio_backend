package com.tinniestudio.api.shared.entity;

import com.tinniestudio.api.shared.entity.DomainEnums.PartnerApplicationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "partner_applications")
@Getter @Setter @NoArgsConstructor
public class PartnerApplication extends BaseEntity {

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String companyName;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String websiteUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartnerApplicationStatus status = PartnerApplicationStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String rejectionReason;

    private UUID reviewedBy;
    private Instant reviewedAt;
}
