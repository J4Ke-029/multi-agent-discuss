package com.multiagent.discuss.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ApiErrorPrivacyTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private static final String PRIVATE_VALUE = "private-value-must-not-be-returned";

    @Test
    void internalErrorDoesNotExposeDatabaseDetailsAndHasConsistentTraceId() {
        var response = handler.other(new RuntimeException("SQL password=" + PRIVATE_VALUE));
        var body = response.getBody();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(body);
        assertFalse(body.toString().contains(PRIVATE_VALUE));
        assertTrue(body.get("hint").toString().contains(body.get("traceId").toString()));
    }

    @Test
    void upstreamResponseAndNetworkAddressAreNotReflected() {
        var upstream = HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, PRIVATE_VALUE,
                null, PRIVATE_VALUE.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertFalse(handler.upstreamClient(upstream).getBody().toString().contains(PRIVATE_VALUE));
        var network = new ResourceAccessException(PRIVATE_VALUE, new ConnectException(PRIVATE_VALUE));
        assertFalse(handler.upstream(network).getBody().toString().contains(PRIVATE_VALUE));
    }

    @Test
    void expectedBusinessErrorsRemainActionable() {
        var response = handler.badRequest(new IllegalArgumentException("智能体不存在"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("智能体不存在", response.getBody().get("message"));
    }

    @Test
    void wrappedStateErrorDoesNotExposeItsTechnicalCause() {
        var response = handler.conflict(new IllegalStateException(
                "文献处理失败: " + PRIVATE_VALUE, new RuntimeException(PRIVATE_VALUE)));
        assertFalse(response.getBody().toString().contains(PRIVATE_VALUE));
        assertEquals("文献处理失败", response.getBody().get("message"));
    }
}
