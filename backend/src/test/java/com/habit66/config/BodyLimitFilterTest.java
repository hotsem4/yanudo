package com.habit66.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.habit66.common.BodyTooLargeException;

import jakarta.servlet.FilterChain;

class BodyLimitFilterTest {

    private static final int MB = 1024 * 1024;

    private final BodyLimitFilter filter = new BodyLimitFilter(new ObjectMapper());

    @Test
    void declaredLengthOverTheLimitIsRejectedBeforeReading() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/events");
        request.setContent(new byte[2 * MB + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain mustNotRun = (req, res) -> {
            throw new AssertionError("chain must not run");
        };

        filter.doFilter(request, response, mustNotRun);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"error\":\"PAYLOAD_TOO_LARGE\"");
    }

    @Test
    void bodyWithoutDeclaredLengthIsCutOffWhileReading() {
        MockHttpServletRequest request = unknownLength("/api/v1/events", new byte[3 * MB]);
        FilterChain readsEverything = (req, res) -> req.getInputStream().readAllBytes();

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), readsEverything))
                .isInstanceOf(BodyTooLargeException.class);
    }

    @Test
    void bodiesWithinTheLimitPassThroughUnchanged() throws Exception {
        byte[] body = "{\"events\":[]}".getBytes();
        MockHttpServletRequest request = unknownLength("/api/v1/events", body);
        AtomicReference<byte[]> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(req.getInputStream().readAllBytes()));

        assertThat(seen.get()).isEqualTo(body);
    }

    @Test
    void audioUploadsGetTheLargerLimit() throws Exception {
        MockHttpServletRequest audio = unknownLength("/api/v1/admin/audio/0123456789abcdef0123.mp3", new byte[3 * MB]);
        AtomicReference<byte[]> seen = new AtomicReference<>();

        filter.doFilter(audio, new MockHttpServletResponse(), (req, res) -> seen.set(req.getInputStream().readAllBytes()));
        assertThat(seen.get()).hasSize(3 * MB);

        MockHttpServletRequest tooBig = unknownLength("/api/v1/admin/audio/0123456789abcdef0123.mp3",
                new byte[5 * MB + 1]);
        assertThatThrownBy(() -> filter.doFilter(tooBig, new MockHttpServletResponse(),
                (req, res) -> req.getInputStream().readAllBytes()))
                .isInstanceOf(BodyTooLargeException.class);
    }

    /** Content-Length 없이(chunked) 온 요청. */
    private static MockHttpServletRequest unknownLength(String uri, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(body);
        return request;
    }
}
