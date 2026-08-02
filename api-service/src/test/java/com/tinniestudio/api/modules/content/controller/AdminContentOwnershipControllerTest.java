package com.tinniestudio.api.modules.content.controller;

import com.tinniestudio.api.modules.content.repository.ContentRepository;
import com.tinniestudio.api.modules.content.service.ContentService;
import com.tinniestudio.api.modules.user.service.UserDetailsServiceImpl;
import com.tinniestudio.api.shared.entity.Content;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentStatus;
import com.tinniestudio.api.shared.entity.DomainEnums.ContentType;
import com.tinniestudio.api.shared.entity.DomainEnums.MaturityRating;
import com.tinniestudio.api.shared.security.jwt.JwtAuthenticationFilter;
import com.tinniestudio.api.shared.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test: a PARTNER used to be able to update()/submit() ANY content by id — the
 * class-level @PreAuthorize("hasRole('ADMIN') or hasRole('PARTNER')") had no ownership check.
 * Now a non-admin caller must own the content (content.createdBy == caller id) or gets 404.
 */
@WebMvcTest(controllers = AdminContentController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminContentOwnershipControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ContentService contentService;
    @MockBean ContentRepository contentRepository;
    @MockBean JwtTokenProvider jwtTokenProvider;
    @MockBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean UserDetailsServiceImpl userDetailsService;

    private Content contentOwnedBy(UUID ownerId) {
        Content content = new Content();
        content.setId(UUID.randomUUID());
        content.setTitle("Some Title");
        content.setType(ContentType.MOVIE);
        content.setStatus(ContentStatus.DRAFT);
        content.setMaturityRating(MaturityRating.NOT_RATED);
        content.setCreatedBy(ownerId);
        content.setCategories(new HashSet<>());
        return content;
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "PARTNER")
    void partner_cannotUpdate_contentOwnedByAnotherPartner() throws Exception {
        UUID otherPartnerId = UUID.randomUUID();
        Content content = contentOwnedBy(otherPartnerId);
        when(contentRepository.findById(content.getId())).thenReturn(Optional.of(content));

        mockMvc.perform(patch("/admin/contents/" + content.getId())
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "PARTNER")
    void partner_canUpdate_ownContent() throws Exception {
        UUID callerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Content content = contentOwnedBy(callerId);
        when(contentRepository.findById(content.getId())).thenReturn(Optional.of(content));
        when(contentService.update(any(), any())).thenReturn(null);

        mockMvc.perform(patch("/admin/contents/" + content.getId())
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "ADMIN")
    void admin_canUpdate_anyContent() throws Exception {
        Content content = contentOwnedBy(UUID.randomUUID());
        when(contentService.update(any(), any())).thenReturn(null);

        mockMvc.perform(patch("/admin/contents/" + content.getId())
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "11111111-1111-1111-1111-111111111111", roles = "PARTNER")
    void partner_cannotSubmit_contentOwnedByAnotherPartner() throws Exception {
        UUID otherPartnerId = UUID.randomUUID();
        Content content = contentOwnedBy(otherPartnerId);
        when(contentRepository.findById(content.getId())).thenReturn(Optional.of(content));

        mockMvc.perform(post("/admin/contents/" + content.getId() + "/submit"))
            .andExpect(status().isNotFound());
    }
}
