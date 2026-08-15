package com.tinniestudio.api.shared.entity;

import com.tinniestudio.api.shared.entity.DomainEnums.AppealStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_appeals")
@Getter @Setter @NoArgsConstructor
public class AccountAppeal extends BaseEntity {

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppealStatus status = AppealStatus.PENDING;

    private UUID reviewedBy;
    private Instant reviewedAt;
}
