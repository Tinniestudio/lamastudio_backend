package com.tinniestudio.api.modules.partner.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class UpdatePartnerProfileRequest {
    @Size(max = 255)
    private String companyName;
    @Size(max = 500)
    private String websiteUrl;
    @Size(max = 2000)
    private String bio;
}
