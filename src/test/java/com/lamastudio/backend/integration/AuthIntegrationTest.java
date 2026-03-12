package com.lamastudio.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lamastudio.backend.auth.dto.LoginRequest;
import com.lamastudio.backend.auth.dto.RegisterRequest;
import com.lamastudio.backend.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@ExtendWith(SpringExtension.class)
class AuthIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("lamastudio_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    // Avoid real SMTP calls during tests
    @MockBean
    private JavaMailSender mailSender;

    private static final String CONTEXT_PATH = "/api/v1";

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postWithContext(String path) {
        return post(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getWithContext(String path) {
        return get(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    @Test
    @DisplayName("Registration flow persists user and returns 201")
    void registrationFlow() throws Exception {
        String email = "integration+register@example.com";
        RegisterRequest request = buildRegisterRequest(email);

    mockMvc.perform(postWithContext("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        assertThat(userRepository.findByEmail(email.toLowerCase())).isPresent();
    }

    @Test
    @DisplayName("Login flow returns cookies and 200")
    void loginFlow() throws Exception {
        String email = "integration+login@example.com";
        String password = "Password1!";
        register(email, password);

        LoginRequest login = new LoginRequest();
        login.setEmail(email);
        login.setPassword(password);

    mockMvc.perform(postWithContext("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(login)))
        .andExpect(status().isOk())
        .andExpect(cookie().exists("access_token"))
        .andExpect(cookie().exists("refresh_token"));
    }

    @Test
    @DisplayName("Protected endpoint requires auth and works with valid JWT cookie")
    void protectedEndpointFlow() throws Exception {
        String email = "integration+protected@example.com";
        String password = "Password1!";
        register(email, password);

        LoginRequest login = new LoginRequest();
        login.setEmail(email);
        login.setPassword(password);

        MvcResult loginResult = mockMvc.perform(postWithContext("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();

        // Without token -> 401
        mockMvc.perform(postWithContext("/auth/logout"))
                .andExpect(status().isUnauthorized());

        // With access_token cookie -> 200
        jakarta.servlet.http.Cookie[] cookies = loginResult.getResponse().getCookies();
        mockMvc.perform(postWithContext("/auth/logout").cookie(cookies))
                .andExpect(status().isOk());
    }

    private void register(String email, String password) throws Exception {
        RegisterRequest request = buildRegisterRequest(email);
        request.setPassword(password);
        mockMvc.perform(postWithContext("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private RegisterRequest buildRegisterRequest(String email) {
        RegisterRequest request = new RegisterRequest();
        request.setEmail(email);
        request.setPassword("Password1!");
        request.setFirstName("Jane");
        request.setLastName("Doe");
        return request;
    }
}
