package com.tinniestudio.api.modules.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
public class UpdateProfileRequest {

    @Size(max = 100)
    private String firstName;

    @Size(max = 100)
    private String lastName;

    @Size(max = 150)
    private String displayName;

    @Size(max = 500)
    private String bio;

    @Pattern(regexp = "^[a-zA-Z]{1,8}(-[a-zA-Z0-9]{1,8})*$", message = "Invalid language code format")
    @Size(max = 10)
    private String languageCode;

    @Pattern(regexp = "^[A-Z]{2}$", message = "Country code must be a 2-letter ISO 3166-1 alpha-2 code")
    private String countryCode;

    @Size(max = 100)
    private String timezone;

    @Size(max = 30)
    private String phoneNumber;

    private LocalDate dateOfBirth;
}
