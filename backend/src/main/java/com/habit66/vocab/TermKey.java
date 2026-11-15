package com.habit66.vocab;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 표현을 하나로 합치는 키(SPEC F-08): 소문자, 앞뒤 공백 제거, 연속 공백 하나로.
 * 프론트의 {@code termKey()}(api/types.ts)와 결과가 같아야 한다. 오프라인에서 담은 표현을 프론트가 이 키로 먼저 그린다.
 */
public final class TermKey {

    /** 자바스크립트의 \s 와 같은 범위(유니코드 공백, 줄바꿈 없는 공백, BOM 포함). */
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}\\uFEFF]+", Pattern.UNICODE_CHARACTER_CLASS);

    private TermKey() {
    }

    public static String of(String text) {
        return clean(text).toLowerCase(Locale.ROOT);
    }

    /** 대소문자는 그대로 두고 공백만 정리한 표시용 문자열. */
    public static String clean(String text) {
        return text == null ? "" : SPACES.matcher(text).replaceAll(" ").strip();
    }
}
