package com.tinniestudio.api.modules.analytics.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContentAnalyticsDailyId implements Serializable {
    private UUID contentId;
    private LocalDate analyticsDate;
}
