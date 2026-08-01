package com.tinniestudio.api.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    /**
     * Cookie-based auth is the primary scheme (HTTP-only cookies set by backend).
     * Bearer token is exposed as a fallback for direct API testing via Swagger UI.
     *
     * In Swagger UI: use the Authorize button, paste your access_token in the
     * Bearer field. For production use, cookies are set automatically by the browser.
     */
    private static final String BEARER_SCHEME_NAME = "BearerAuth";
    private static final String COOKIE_SCHEME_NAME  = "CookieAuth";

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @Bean
    public OpenAPI tinnieStudioOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .externalDocs(externalDocs())
                .servers(List.of(
                        new Server().url(baseUrl + "/api/v1").description("Current environment"),
                        new Server().url("http://localhost:8080").description("Local development")
                ))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, bearerSecurityScheme())
                        .addSecuritySchemes(COOKIE_SCHEME_NAME, cookieSecurityScheme())
                )
                // Global security: every endpoint requires one of these unless overridden with @SecurityRequirements({})
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME))
                .addSecurityItem(new SecurityRequirement().addList(COOKIE_SCHEME_NAME));
    }

    private Info apiInfo() {
        return new Info()
                .title("LamaStudio API")
                .version("1.0.0")
                .description("""
                        ## LamaStudio Backend API
                        
                        Production-grade REST API for the LamaStudio platform.
                        
                        ### Authentication
                        
                        LamaStudio uses **stateless JWT authentication** with HTTP-only cookies:
                        
                        - `access_token` — short-lived (15 min), sent automatically by the browser on every request
                        - `refresh_token` — long-lived (7 days), used to silently refresh the access token
                        
                        Tokens are **never exposed in response bodies**. They are set as `HttpOnly; Secure; SameSite=Lax` cookies by the backend.
                        
                        For testing in Swagger UI, use the **Authorize** button and paste your `access_token` value into the BearerAuth field.
                        
                        ### Google OAuth2
                        
                        Initiate the Google login flow by navigating to:
                        ```
                        GET /api/v1/oauth2/authorization/google
                        ```
                        After Google redirects back, the backend issues internal JWTs and sets auth cookies,
                        then redirects to the frontend.
                        
                        ### Role-Based Access Control
                        
                        | Role | Description |
                        |---|---|
                        | `ROLE_USER` | Default role assigned on registration |
                        | `ROLE_ADMIN` | Administrative access |
                        | `ROLE_SUPER_ADMIN` | Full platform access |
                        """)
                .contact(new Contact()
                        .name("LamaStudio Team")
                        .email("dev@tinniestudio.com.com")
                        .url(frontendUrl))
                .license(new License()
                        .name("Proprietary")
                        .url(frontendUrl + "/legal"));
    }

    private ExternalDocumentation externalDocs() {
        return new ExternalDocumentation()
                .description("LamaStudio Developer Docs")
                .url(frontendUrl + "/docs");
    }

    private SecurityScheme bearerSecurityScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .name(BEARER_SCHEME_NAME)
                .description(
                        "JWT access token obtained from `/api/v1/auth/login` or `/api/v1/auth/register`. " +
                        "In production this is sent automatically via HTTP-only cookie. " +
                        "For Swagger UI testing, paste the raw token value here."
                );
    }

    private SecurityScheme cookieSecurityScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name("access_token")
                .description("HTTP-only cookie set by the backend after successful login. Sent automatically by the browser.");
    }
}
