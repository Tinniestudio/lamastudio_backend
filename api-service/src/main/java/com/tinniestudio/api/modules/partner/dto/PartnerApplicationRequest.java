package com.tinniestudio.api.modules.partner.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

    // Required only when the caller is anonymous — enforced in
    // PartnerApplicationServiceImpl.apply, not via @NotNull, since these must stay
    // absent/ignored for an authenticated caller applying with their existing account.
    @Email
    @Size(max = 255)
    private String email;

    @Size(min = 8, max = 128)
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]+$",
        message = "Password must contain at least one uppercase letter, lowercase letter, digit, and special character"
    )
    private String password;

    @Size(max = 100)
    private String firstName;

    @Size(max = 100)
    private String lastName;
}
