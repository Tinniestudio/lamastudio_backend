package com.tinniestudio.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tinniestudio.api.shared.web.ApiResponse;
import com.tinniestudio.api.shared.web.SkipResponseWrapper;
import com.tinniestudio.api.shared.web.SuccessResponseWrapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuccessResponseWrapperTest {

    private SuccessResponseWrapper wrapper;
    private ServerHttpRequest request;
    private ServerHttpResponse response;

    // ── Test helper controllers ────────────────────────────────────────────────

    @SkipResponseWrapper
    static class SkippedController {
        public Object doSomething() { return null; }
    }

    static class NormalController {
        public Object doSomething() { return null; }
        public ApiResponse<Object> doAlreadyWrapped() { return null; }
    }


    // ── Setup ──────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        wrapper = new SuccessResponseWrapper();
        request = mock(ServerHttpRequest.class);
        response = mock(ServerHttpResponse.class);
    }

    private MethodParameter paramFor(Class<?> cls, String methodName) {
        try {
            Method method = cls.getDeclaredMethod(methodName);
            return new MethodParameter(method, -1);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    // ── supports() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("supports: normal controller with non-ApiResponse return → true")
    void supports_normalController_returnsTrue() {
        var param = paramFor(NormalController.class, "doSomething");
        assertThat(wrapper.supports(param, MappingJackson2HttpMessageConverter.class)).isTrue();
    }

    @Test
    @DisplayName("supports: @SkipResponseWrapper controller → false")
    void supports_skippedController_returnsFalse() {
        var param = paramFor(SkippedController.class, "doSomething");
        assertThat(wrapper.supports(param, MappingJackson2HttpMessageConverter.class)).isFalse();
    }

    @Test
    @DisplayName("supports: non-Jackson converter (String) → false")
    void supports_stringConverter_returnsFalse() {
        var param = paramFor(NormalController.class, "doSomething");
        assertThat(wrapper.supports(param, StringHttpMessageConverter.class)).isFalse();
    }

    @Test
    @DisplayName("supports: non-Jackson converter (ByteArray) → false — prevents api-docs cast error")
    void supports_byteArrayConverter_returnsFalse() {
        var param = paramFor(NormalController.class, "doSomething");
        assertThat(wrapper.supports(param, ByteArrayHttpMessageConverter.class)).isFalse();
    }

    @Test
    @DisplayName("supports: return type is already ApiResponse → false")
    void supports_alreadyApiResponseReturnType_returnsFalse() {
        var param = paramFor(NormalController.class, "doAlreadyWrapped");
        assertThat(wrapper.supports(param, MappingJackson2HttpMessageConverter.class)).isFalse();
    }

    // ── beforeBodyWrite() ─────────────────────────────────────────────────────

    @Test
    @DisplayName("beforeBodyWrite: null body → null returned")
    void beforeBodyWrite_nullBody_returnsNull() {
        var param = paramFor(NormalController.class, "doSomething");
        Object result = wrapper.beforeBodyWrite(null, param, MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class, request, response);
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("beforeBodyWrite: DTO body → wrapped with data=body and GET default message")
    void beforeBodyWrite_dtoBody_wrapsInEnvelope() {
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        var param = paramFor(NormalController.class, "doSomething");
        var body = Map.of("userId", "abc-123");

        Object result = wrapper.beforeBodyWrite(body, param, MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class, request, response);

        assertThat(result).isInstanceOf(ApiResponse.class);
        @SuppressWarnings("unchecked")
        ApiResponse<Object> env = (ApiResponse<Object>) result;
        assertThat(env.success()).isTrue();
        assertThat(env.data()).isEqualTo(body);
        assertThat(env.message()).isEqualTo("Retrieved successfully");
    }

    @Test
    @DisplayName("beforeBodyWrite: Map with single 'message' key → message extracted, data=null")
    void beforeBodyWrite_messageMap_extractsMessageAndNullsData() {
        var param = paramFor(NormalController.class, "doSomething");
        var body = Map.of("message", "Logged out successfully");

        @SuppressWarnings("unchecked")
        ApiResponse<Object> result = (ApiResponse<Object>) wrapper.beforeBodyWrite(body, param,
                MediaType.APPLICATION_JSON, MappingJackson2HttpMessageConverter.class, request, response);

        assertThat(result.message()).isEqualTo("Logged out successfully");
        assertThat(result.data()).isNull();
        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("beforeBodyWrite: existing ApiResponse body → returned unchanged (idempotent)")
    void beforeBodyWrite_alreadyApiResponse_notRewrapped() {
        var param = paramFor(NormalController.class, "doSomething");
        var body = ApiResponse.ok("Already wrapped", "payload");

        Object result = wrapper.beforeBodyWrite(body, param, MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class, request, response);

        assertThat(result).isSameAs(body);
    }

    @Test
    @DisplayName("beforeBodyWrite: POST → message is 'Created successfully'")
    void beforeBodyWrite_post_usesCreatedMessage() {
        when(request.getMethod()).thenReturn(HttpMethod.POST);
        var param = paramFor(NormalController.class, "doSomething");

        @SuppressWarnings("unchecked")
        ApiResponse<Object> result = (ApiResponse<Object>) wrapper.beforeBodyWrite(
                Map.of("id", 1), param, MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class, request, response);

        assertThat(result.message()).isEqualTo("Created successfully");
    }

    @Test
    @DisplayName("beforeBodyWrite: PATCH → message is 'Updated successfully'")
    void beforeBodyWrite_patch_usesUpdatedMessage() {
        when(request.getMethod()).thenReturn(HttpMethod.PATCH);
        var param = paramFor(NormalController.class, "doSomething");

        @SuppressWarnings("unchecked")
        ApiResponse<Object> result = (ApiResponse<Object>) wrapper.beforeBodyWrite(
                Map.of("status", "ok"), param, MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class, request, response);

        assertThat(result.message()).isEqualTo("Updated successfully");
    }

    @Test
    @DisplayName("beforeBodyWrite: DELETE → message is 'Deleted successfully'")
    void beforeBodyWrite_delete_usesDeletedMessage() {
        when(request.getMethod()).thenReturn(HttpMethod.DELETE);
        var param = paramFor(NormalController.class, "doSomething");

        @SuppressWarnings("unchecked")
        ApiResponse<Object> result = (ApiResponse<Object>) wrapper.beforeBodyWrite(
                Map.of("deleted", true), param, MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class, request, response);

        assertThat(result.message()).isEqualTo("Deleted successfully");
    }

    // ── US2: meta serialization ────────────────────────────────────────────────

    @Test
    @DisplayName("ApiResponse with meta=null serializes without 'meta' key")
    void apiResponse_nullMeta_absentInJson() throws Exception {
        var mapper = new ObjectMapper();
        var resp = ApiResponse.ok("msg", "data");
        String json = mapper.writeValueAsString(resp);
        assertThat(json).doesNotContain("\"meta\"");
    }

    @Test
    @DisplayName("ApiResponse with meta populated serializes all four pagination fields")
    void apiResponse_populatedMeta_presentInJson() throws Exception {
        var mapper = new ObjectMapper();
        var meta = new ApiResponse.Meta(47, 1, 20, 3);
        var resp = ApiResponse.ok("msg", "data", meta);
        String json = mapper.writeValueAsString(resp);
        assertThat(json).contains("\"total\":47", "\"page\":1", "\"size\":20", "\"totalPages\":3");
    }
}
