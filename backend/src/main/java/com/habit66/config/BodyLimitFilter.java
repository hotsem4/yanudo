package com.habit66.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.habit66.common.BodyTooLargeException;
import com.habit66.common.ErrorResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청 본문 상한. 컨트롤러의 크기 검사는 본문을 메모리에 다 올린 뒤에 돌기 때문에, 그 전에 읽는 단계에서 끊는다.
 * Content-Length 가 상한을 넘으면 바로 413, 길이를 알리지 않은 본문은 읽다가 상한에서 멈춘다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BodyLimitFilter extends OncePerRequestFilter {

    static final long DEFAULT_LIMIT = 2L * 1024 * 1024;
    /** 오디오 업로드. 문장 하나짜리 MP3 는 수십 KB 다. */
    static final long AUDIO_LIMIT = 5L * 1024 * 1024;

    private final ObjectMapper mapper;

    public BodyLimitFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = request.getRequestURI().startsWith("/api/v1/admin/audio/") ? AUDIO_LIMIT : DEFAULT_LIMIT;
        if (request.getContentLengthLong() > limit) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            mapper.writeValue(response.getOutputStream(),
                    new ErrorResponse("PAYLOAD_TOO_LARGE", "요청 본문이 너무 큽니다."));
            return;
        }
        chain.doFilter(new LimitedRequest(request, limit), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream in;
        private final long limit;
        private long count;

        LimitedStream(ServletInputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                counted(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                counted(n);
            }
            return n;
        }

        private void counted(int n) throws IOException {
            count += n;
            if (count > limit) {
                throw new BodyTooLargeException(limit);
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
