package com.habit66.tts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 합성 단위. 오디오 파일 이름 규칙은 여기 한 곳에만 있다(Python·프론트에 복제하지 않는다).
 * variant 는 같은 목소리라도 품질이 다른 엔진(Azure 48kHz)일 때만 붙는다. 비어 있으면 예전 이름 그대로다.
 */
public record Utterance(String voice, String rate, String text, String variant) {

    public Utterance(String voice, String rate, String text) {
        this(voice, rate, text, "");
    }

    public String fileName() {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            String key = voice + "|" + rate + "|" + text + (variant == null || variant.isEmpty() ? "" : "|" + variant);
            byte[] digest = sha1.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 20) + ".mp3";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }
}
