package com.tinniestudio.api.modules.reviews.dto;

import com.tinniestudio.api.shared.entity.DomainEnums.ReviewStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateReviewStatusRequest {
    @NotNull
    private ReviewStatus status;
}
