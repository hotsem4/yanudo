package com.habit66.tts;

import java.util.OptionalLong;

/**
 * MP3(MPEG 1/2/2.5 Layer III) 프레임 헤더를 끝까지 세어 길이를 구한다. 외부 라이브러리를 쓰지 않는다.
 * edge-tts 출력은 24kHz MPEG-2 Layer III 이다.
 */
public final class Mp3Frames {

    public static final double MIN_COVERAGE = 0.9;
    public static final long MIN_DURATION_MS = 200;

    /**
     * @param frames     찾은 프레임 수
     * @param frameBytes 프레임이 덮은 바이트
     * @param audioBytes ID3v2(앞)·ID3v1(뒤) 태그를 뺀 파일 바이트
     */
    public record Scan(int frames, long frameBytes, long audioBytes, long durationMs) {

        public boolean valid() {
            return frames > 0 && frameBytes >= audioBytes * MIN_COVERAGE && durationMs >= MIN_DURATION_MS;
        }
    }

    private static final int[] BITRATE_V1 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] BITRATE_V2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
    private static final int[] RATE_V1 = {44100, 48000, 32000};
    private static final int[] RATE_V2 = {22050, 24000, 16000};
    private static final int[] RATE_V25 = {11025, 12000, 8000};

    private Mp3Frames() {
    }

    /** 받아들일 수 있는 MP3면 길이(ms), 아니면 빈 값. */
    public static OptionalLong durationMs(byte[] data) {
        Scan scan = scan(data);
        return scan.valid() ? OptionalLong.of(scan.durationMs()) : OptionalLong.empty();
    }

    public static Scan scan(byte[] data) {
        int start = id3v2Length(data);
        int end = data.length;
        if (end - start >= 128 && data[end - 128] == 'T' && data[end - 127] == 'A' && data[end - 126] == 'G') {
            end -= 128;
        }
        if (start >= end) {
            return new Scan(0, 0, Math.max(0, end - start), 0);
        }

        int frames = 0;
        long frameBytes = 0;
        double millis = 0;
        boolean synced = false;
        int pos = start;
        long[] frame = new long[2];
        while (pos + 4 <= end) {
            if (parse(data, pos, frame) && pos + frame[0] <= end
                    && (synced || confirmed(data, (int) (pos + frame[0]), end))) {
                frames++;
                frameBytes += frame[0];
                millis += frame[1] / 1000.0;
                pos += (int) frame[0];
                synced = true;
            } else {
                pos++;
                synced = false;
            }
        }
        return new Scan(frames, frameBytes, end - start, Math.round(millis));
    }

    /** 동기를 새로 잡을 때는 바로 뒤에도 프레임이 이어져야(또는 파일이 끝나야) 진짜 프레임으로 본다. */
    private static boolean confirmed(byte[] data, int next, int end) {
        return next == end || (next + 4 <= end && parse(data, next, new long[2]));
    }

    /** out[0] = 프레임 길이(바이트), out[1] = 프레임 길이(마이크로초). */
    private static boolean parse(byte[] d, int pos, long[] out) {
        int b0 = d[pos] & 0xFF;
        int b1 = d[pos + 1] & 0xFF;
        int b2 = d[pos + 2] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) {
            return false;
        }
        int version = (b1 >> 3) & 0x3;   // 0 = MPEG 2.5, 2 = MPEG 2, 3 = MPEG 1
        int layer = (b1 >> 1) & 0x3;     // 1 = Layer III
        int bitrateIndex = (b2 >> 4) & 0xF;
        int rateIndex = (b2 >> 2) & 0x3;
        int padding = (b2 >> 1) & 0x1;
        if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) {
            return false;
        }
        boolean v1 = version == 3;
        int bitrate = (v1 ? BITRATE_V1 : BITRATE_V2)[bitrateIndex] * 1000;
        int sampleRate = (v1 ? RATE_V1 : version == 2 ? RATE_V2 : RATE_V25)[rateIndex];
        int samples = v1 ? 1152 : 576;
        int length = (samples / 8) * bitrate / sampleRate + padding;
        if (length < 4) {
            return false;
        }
        out[0] = length;
        out[1] = samples * 1_000_000L / sampleRate;
        return true;
    }

    private static int id3v2Length(byte[] d) {
        if (d.length < 10 || d[0] != 'I' || d[1] != 'D' || d[2] != '3') {
            return 0;
        }
        int size = ((d[6] & 0x7F) << 21) | ((d[7] & 0x7F) << 14) | ((d[8] & 0x7F) << 7) | (d[9] & 0x7F);
        int footer = (d[5] & 0x10) != 0 ? 10 : 0;
        return Math.min(d.length, 10 + size + footer);
    }
}
