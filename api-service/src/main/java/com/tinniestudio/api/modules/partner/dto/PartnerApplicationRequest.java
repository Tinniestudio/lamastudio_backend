package com.tinniestudio.api.modules.partner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class PartnerApplicationRequest {
    @NotBlank
    @Size(max = 255)
    private String companyName;

    @Size(max = 2000)
    private String description;

    @Size(max = 500)
    private String websiteUrl;
}
