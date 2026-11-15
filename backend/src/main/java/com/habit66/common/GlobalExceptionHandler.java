package com.habit66.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return respond(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(BodyTooLargeException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(BodyTooLargeException e) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "요청 본문이 너무 큽니다.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof BodyTooLargeException tooLarge) {
                return handleTooLarge(tooLarge);
            }
        }
        return respond(HttpStatus.BAD_REQUEST, "INVALID_JSON", "요청 본문을 읽을 수 없습니다. JSON 형식을 확인하세요.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception e) {
        // 스프링이 상태 코드를 정해 둔 예외(없는 경로, 지원하지 않는 메서드 등)는 그 상태를 유지한다.
        if (e instanceof org.springframework.web.ErrorResponse known) {
            HttpStatusCode status = known.getStatusCode();
            HttpStatus resolved = HttpStatus.resolve(status.value());
            String code = resolved != null ? resolved.name() : "HTTP_" + status.value();
            return respond(status, code, messageFor(status));
        }
        log.error("Unhandled exception", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버에서 오류가 났습니다. 서버 로그를 확인하세요.");
    }

    private static String messageFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> "요청한 경로를 찾을 수 없습니다.";
            case 405 -> "지원하지 않는 요청 방식입니다.";
            case 415 -> "지원하지 않는 본문 형식입니다.";
            default -> status.is4xxClientError() ? "요청을 처리할 수 없습니다." : "서버에서 오류가 났습니다.";
        };
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatusCode status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(code, message));
    }
}
