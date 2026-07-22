package com.tinniestudio.api.modules.reviews.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.modules.reviews.dto.ReviewResponse;
import com.tinniestudio.api.modules.reviews.dto.UpdateReviewStatusRequest;
import com.tinniestudio.api.modules.reviews.service.ReviewService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.entity.DomainEnums.ReviewStatus;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminReviewControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private ReviewService reviewService;
    @MockBean private JwtTokenProvider jwtTokenProvider;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    private static final String CONTEXT_PATH = "/api/v1";

    private MockHttpServletRequestBuilder patchWithContext(String path) {
        return patch(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
    }

    @Test
    @DisplayName("PATCH /admin/reviews/{id}/status returns 200 with REJECTED status")
    @WithMockUser(username = "admin", roles = "ADMIN")
    void moderateStatus_returns200WithRejectedStatus() throws Exception {
        UUID reviewId = UUID.randomUUID();
        ReviewResponse reviewResponse = new ReviewResponse(
            reviewId, UUID.randomUUID(), UUID.randomUUID(),
            (short) 1, "Spam content", "REJECTED",
            Instant.now(), Instant.now()
        );

        UpdateReviewStatusRequest request = new UpdateReviewStatusRequest();
        request.setStatus(ReviewStatus.REJECTED);

        when(reviewService.moderateStatus(any(UUID.class), any(UpdateReviewStatusRequest.class)))
            .thenReturn(reviewResponse);

        mockMvc.perform(patchWithContext("/admin/reviews/" + reviewId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }
}
