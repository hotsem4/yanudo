package com.habit66.vocab;

/**
 * 생성 실패. {@link #hint()}가 있으면 사용자가 손봐야 풀리는 문제(로그인 만료, API 키 없음)이고,
 * 같은 이유로 줄줄이 실패할 것이므로 큐는 남은 표현을 더 시도하지 않는다.
 */
public class GenerationException extends Exception {

    private final String hint;

    public GenerationException(String message) {
        this(message, null, null);
    }

    public GenerationException(String message, String hint) {
        this(message, hint, null);
    }

    public GenerationException(String message, String hint, Throwable cause) {
        super(message, cause);
        this.hint = hint;
    }

    /** 사용자가 해야 할 일(로그에 그대로 남긴다). 없으면 null. */
    public String hint() {
        return hint;
    }

    public boolean needsUserAction() {
        return hint != null;
    }
}
