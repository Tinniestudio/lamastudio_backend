package com.tinniestudio.api.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "content_cast")
@Getter
@Setter
@NoArgsConstructor
public class ContentCast extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    @Column(nullable = false)
    private String name;

    private String role;

    private String characterName;

    private String profileImageUrl;

    @Column(nullable = false)
    private Integer displayOrder = 0;
}
