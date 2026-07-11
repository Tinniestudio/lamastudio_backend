package com.tinniestudio.api.shared.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;

@Entity
@Table(name = "media_files")
@Getter
@Setter
@NoArgsConstructor
public class MediaFile extends BaseEntity {

    @Column(nullable = false)
    private UUID userId;

    private UUID uploadSessionId;

    private String fileType;

    @Column(nullable = false)
    private String storageKey;

    private String originalFilename;

    private String mimeType;

    private Long fileSizeBytes;
}
