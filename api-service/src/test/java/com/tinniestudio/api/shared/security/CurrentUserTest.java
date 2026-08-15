package com.tinniestudio.api.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CurrentUser")
class CurrentUserTest {

    @Test
    @DisplayName("id() parses the principal's username as the caller's UUID")
    void id_parsesUsernameAsUuid() {
        UUID userId = UUID.randomUUID();
        UserDetails principal = new User(userId.toString(), "n/a", List.of());

        assertThat(CurrentUser.id(principal)).isEqualTo(userId);
    }

    @Test
    @DisplayName("id() throws AuthenticationCredentialsNotFoundException when principal is null")
    void id_nullPrincipal_throws() {
        assertThatThrownBy(() -> CurrentUser.id(null))
            .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    @DisplayName("idOrNull() returns the caller's UUID when authenticated")
    void idOrNull_returnsIdWhenPresent() {
        UUID userId = UUID.randomUUID();
        UserDetails principal = new User(userId.toString(), "n/a", List.of());

        assertThat(CurrentUser.idOrNull(principal)).isEqualTo(userId);
    }

    @Test
    @DisplayName("idOrNull() returns null instead of throwing when principal is null (optional-auth endpoints)")
    void idOrNull_nullPrincipal_returnsNull() {
        assertThat(CurrentUser.idOrNull(null)).isNull();
    }
}
