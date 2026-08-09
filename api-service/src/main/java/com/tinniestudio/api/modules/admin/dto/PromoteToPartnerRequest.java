package com.tinniestudio.api.modules.admin.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Direct admin-initiated promotion of an arbitrary user to partner (Batch 13 #1 / Batch 14 #4) —
 * no prior partner application required. Both fields are optional since an admin may promote a
 * user before the partner fills in their own profile details via PATCH /partners/profile.
 */
@Getter @Setter @NoArgsConstructor
public class PromoteToPartnerRequest {
    @Size(max = 255)
    private String companyName;
    @Size(max = 500)
    private String websiteUrl;
}
