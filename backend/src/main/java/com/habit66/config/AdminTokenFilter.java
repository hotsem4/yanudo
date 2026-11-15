package com.habit66.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.habit66.common.ErrorResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** `app.admin-token`이 있으면 /api/v1/admin/** 에 X-Admin-Token 헤더를 요구한다. 비어 있으면 통과. */
@Component
public class AdminTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Admin-Token";
    private static final String ADMIN = "/api/v1/admin";

    private final AppProperties props;
    private final ObjectMapper mapper;

    public AdminTokenFilter(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String token = props.adminToken();
        return token == null || token.isBlank() || !isAdminPath(request);
    }

    /**
     * 원시 URI 로 판단하면 `/api/v1/%61dmin/…` 이나 `/api/v1/admin;x=1/…` 로 검사를 건너뛸 수 있다
     * (MVC 는 디코딩하고 경로 매개변수를 뗀 뒤 매핑한다). 그래서 MVC 와 같은 방식으로 푼 경로와
     * 서블릿 컨테이너가 정규화한 경로를 둘 다 보고, 어느 쪽이든 관리자 경로면 토큰을 요구한다.
     */
    static boolean isAdminPath(HttpServletRequest request) {
        String servletPath = request.getServletPath() == null ? "" : request.getServletPath();
        String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
        return guarded(servletPath + pathInfo) || guarded(decodedPath(request));
    }

    private static String decodedPath(HttpServletRequest request) {
        try {
            RequestPath path = RequestPath.parse(request.getRequestURI(), request.getContextPath());
            StringBuilder decoded = new StringBuilder();
            for (PathContainer.Element element : path.pathWithinApplication().elements()) {
                if (element instanceof PathContainer.PathSegment segment) {
                    decoded.append('/').append(segment.valueToMatch());
                }
            }
            return decoded.toString();
        } catch (RuntimeException e) {
            // 풀 수 없는 경로는 관리자 경로로 보고 막는다.
            return ADMIN;
        }
    }

    private static boolean guarded(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.equals(ADMIN) || lower.startsWith(ADMIN + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        byte[] expected = props.adminToken().getBytes(StandardCharsets.UTF_8);
        if (given != null && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(),
                new ErrorResponse("UNAUTHORIZED", "관리자 토큰(X-Admin-Token)이 필요합니다."));
    }
}
