package com.habit66.tts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 합성 단위. 오디오 파일 이름 규칙은 여기 한 곳에만 있다(Python·프론트에 복제하지 않는다). */
public record Utterance(String voice, String rate, String text) {

    public String fileName() {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((voice + "|" + rate + "|" + text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 20) + ".mp3";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }
}
