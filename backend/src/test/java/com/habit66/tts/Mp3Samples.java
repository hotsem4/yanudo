package com.habit66.tts;

import java.io.ByteArrayOutputStream;

/** 테스트용 합성 MP3. edge-tts 와 같은 24kHz MPEG-2 Layer III 48kbps 프레임(144바이트, 24ms)을 만든다. */
public final class Mp3Samples {

    public static final int FRAME_BYTES = 144;
    public static final int FRAME_MS = 24;

    private Mp3Samples() {
    }

    /** MPEG-2 Layer III, 48kbps, 24000Hz, 패딩 없음, 모노. */
    public static byte[] frame() {
        byte[] frame = new byte[FRAME_BYTES];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xF3;
        frame[2] = (byte) 0x64;
        frame[3] = (byte) 0xC4;
        return frame;
    }

    public static byte[] frames(int count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < count; i++) {
            out.writeBytes(frame());
        }
        return out.toByteArray();
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** 본문 {@code size}바이트짜리 ID3v2.3 태그(헤더 10바이트 포함). */
    public static byte[] id3v2(int size) {
        byte[] tag = new byte[10 + size];
        tag[0] = 'I';
        tag[1] = 'D';
        tag[2] = '3';
        tag[3] = 3;
        tag[6] = (byte) ((size >> 21) & 0x7F);
        tag[7] = (byte) ((size >> 14) & 0x7F);
        tag[8] = (byte) ((size >> 7) & 0x7F);
        tag[9] = (byte) (size & 0x7F);
        return tag;
    }
}
