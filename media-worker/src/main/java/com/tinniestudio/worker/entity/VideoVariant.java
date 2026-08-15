package com.tinniestudio.worker.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "video_variants")
@Getter @Setter @NoArgsConstructor
public class VideoVariant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "video_asset_id", nullable = false)
    private UUID videoAssetId;

    @Column(nullable = false)
    private String resolution;

    private Integer width;
    private Integer height;
    private Long bitrate;
    private String manifestKey;
    private Integer segmentCount;
}
