package com.habit66.tts;

import static com.habit66.tts.Mp3Samples.concat;
import static com.habit66.tts.Mp3Samples.frame;
import static com.habit66.tts.Mp3Samples.frames;
import static com.habit66.tts.Mp3Samples.id3v2;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.habit66.tts.Mp3Frames.Scan;

class Mp3FramesTest {

    @Test
    void countsMpeg2Layer3FramesAt24kHz() {
        Scan scan = Mp3Frames.scan(frames(100));

        assertThat(scan.frames()).isEqualTo(100);
        assertThat(scan.frameBytes()).isEqualTo(14_400);
        assertThat(scan.audioBytes()).isEqualTo(14_400);
        assertThat(scan.durationMs()).isEqualTo(2_400);
        assertThat(scan.valid()).isTrue();
        assertThat(Mp3Frames.durationMs(frames(100))).hasValue(2_400);
    }

    @Test
    void skipsId3v2TagAtTheStart() {
        byte[] tag = id3v2(4_000);
        // 태그 본문에 가짜 동기 바이트가 있어도 건너뛰어야 한다.
        tag[100] = (byte) 0xFF;
        tag[101] = (byte) 0xF3;
        tag[102] = (byte) 0x64;

        Scan scan = Mp3Frames.scan(concat(tag, frames(50)));

        assertThat(scan.frames()).isEqualTo(50);
        assertThat(scan.audioBytes()).isEqualTo(50 * 144);
        assertThat(scan.durationMs()).isEqualTo(1_200);
        assertThat(scan.valid()).isTrue();
    }

    @Test
    void ignoresId3v1TagAtTheEnd() {
        byte[] tag = new byte[128];
        tag[0] = 'T';
        tag[1] = 'A';
        tag[2] = 'G';

        Scan scan = Mp3Frames.scan(concat(frames(20), tag));

        assertThat(scan.frames()).isEqualTo(20);
        assertThat(scan.audioBytes()).isEqualTo(20 * 144);
        assertThat(scan.valid()).isTrue();
    }

    @Test
    void readsMpeg1Layer3() {
        // MPEG-1 Layer III, 128kbps, 44100Hz: 417바이트, 1152샘플.
        byte[] frame = new byte[417];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xFB;
        frame[2] = (byte) 0x90;
        frame[3] = (byte) 0x64;
        byte[] data = new byte[417 * 40];
        for (int i = 0; i < 40; i++) {
            System.arraycopy(frame, 0, data, i * 417, 417);
        }

        Scan scan = Mp3Frames.scan(data);

        assertThat(scan.frames()).isEqualTo(40);
        assertThat(scan.durationMs()).isEqualTo(Math.round(40 * 1152 * 1000.0 / 44100));
        assertThat(scan.valid()).isTrue();
    }

    @Test
    void readsMpeg25Layer3() {
        // MPEG-2.5 Layer III, 16kbps, 8000Hz: 144바이트, 576샘플(72ms).
        byte[] frame = new byte[144];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xE3;
        frame[2] = (byte) 0x28;
        frame[3] = (byte) 0xC4;
        byte[] data = new byte[144 * 10];
        for (int i = 0; i < 10; i++) {
            System.arraycopy(frame, 0, data, i * 144, 144);
        }

        Scan scan = Mp3Frames.scan(data);

        assertThat(scan.frames()).isEqualTo(10);
        assertThat(scan.durationMs()).isEqualTo(720);
    }

    @Test
    void addsThePaddingByteToTheFrameLength() {
        byte[] padded = new byte[145];
        padded[0] = (byte) 0xFF;
        padded[1] = (byte) 0xF3;
        padded[2] = (byte) 0x66;
        padded[3] = (byte) 0xC4;

        Scan scan = Mp3Frames.scan(concat(padded, padded, frames(10)));

        assertThat(scan.frames()).isEqualTo(12);
        assertThat(scan.frameBytes()).isEqualTo(2 * 145 + 10 * 144);
    }

    @Test
    void rejectsClipsShorterThan200ms() {
        assertThat(Mp3Frames.scan(frames(8)).durationMs()).isEqualTo(192);
        assertThat(Mp3Frames.durationMs(frames(8))).isEmpty();
        assertThat(Mp3Frames.durationMs(frames(9))).hasValue(216);
    }

    @Test
    void rejectsFilesWhereFramesCoverLessThan90Percent() {
        byte[] audio = frames(40);
        byte[] junk = new byte[1_000];

        assertThat(Mp3Frames.scan(concat(audio, junk)).valid()).isFalse();
        assertThat(Mp3Frames.scan(concat(audio, new byte[400])).valid()).isTrue();
    }

    @Test
    void resynchronizesAfterGarbageInTheMiddle() {
        Scan scan = Mp3Frames.scan(concat(frames(30), new byte[50], frames(30)));

        assertThat(scan.frames()).isEqualTo(60);
        assertThat(scan.valid()).isTrue();
    }

    @Test
    void dropsATruncatedLastFrame() {
        byte[] data = concat(frames(20), Arrays.copyOf(frame(), 100));

        Scan scan = Mp3Frames.scan(data);

        assertThat(scan.frames()).isEqualTo(20);
        assertThat(scan.durationMs()).isEqualTo(480);
    }

    @Test
    void rejectsThingsThatAreNotMp3() {
        assertThat(Mp3Frames.durationMs(new byte[0])).isEmpty();
        assertThat(Mp3Frames.durationMs(new byte[5_000])).isEmpty();
        assertThat(Mp3Frames.durationMs("<html>error</html>".repeat(200).getBytes(StandardCharsets.UTF_8))).isEmpty();
        // Layer II 헤더(0xFFF5)는 받지 않는다.
        byte[] layer2 = frames(30);
        for (int i = 0; i < 30; i++) {
            layer2[i * 144 + 1] = (byte) 0xF5;
        }
        assertThat(Mp3Frames.durationMs(layer2)).isEmpty();
    }

    @Test
    void utteranceFileNameIsTwentyHexCharsOfSha1() {
        Utterance a = new Utterance("en-US-AndrewMultilingualNeural", "-10%", "Hi, what can I get for you today?");

        assertThat(a.fileName()).matches("[0-9a-f]{20}\\.mp3");
        assertThat(a.fileName()).isEqualTo(new Utterance(a.voice(), a.rate(), a.text()).fileName());
        assertThat(a.fileName()).isNotEqualTo(new Utterance("en-US-AvaMultilingualNeural", "-10%", a.text()).fileName());
        assertThat(a.fileName()).isNotEqualTo(new Utterance(a.voice(), "+0%", a.text()).fileName());
        // sha1("v|r|t") = 알려진 값으로 규칙 자체를 고정한다.
        assertThat(new Utterance("v", "r", "t").fileName()).isEqualTo(sha1Prefix("v|r|t") + ".mp3");
        // 품질이 다른 엔진(Azure 48kHz)은 variant 가 붙어 다른 파일이 된다. 비어 있으면 예전 이름 그대로다.
        assertThat(new Utterance("v", "r", "t", "").fileName()).isEqualTo(new Utterance("v", "r", "t").fileName());
        assertThat(new Utterance("v", "r", "t", "azure:x").fileName()).isEqualTo(sha1Prefix("v|r|t|azure:x") + ".mp3");
    }

    private static String sha1Prefix(String input) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 20);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
