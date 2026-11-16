package com.habit66.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.habit66.config.AppProperties;

class VapidKeysTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path dataDir;

    @Test
    void generatesKeysOfTheWebPushSizesOnFirstRun() throws Exception {
        VapidKeys keys = new VapidKeys(props("", ""), mapper);

        byte[] point = Base64.getUrlDecoder().decode(keys.publicKey());
        assertThat(point).hasSize(65);
        assertThat(point[0]).isEqualTo((byte) 0x04);
        assertThat(Base64.getUrlDecoder().decode(keys.privateKey())).hasSize(32);
        assertThat(keys.publicKey()).doesNotContain("=", "+", "/");

        JsonNode stored = mapper.readTree(dataDir.resolve("vapid.json").toFile());
        assertThat(stored.path("public_key").asText()).isEqualTo(keys.publicKey());
        assertThat(stored.path("private_key").asText()).isEqualTo(keys.privateKey());
        assertThat(Files.exists(dataDir.resolve("vapid.json.tmp"))).isFalse();
    }

    @Test
    void reusesStoredKeysOnTheNextRun() {
        VapidKeys first = new VapidKeys(props("", ""), mapper);
        VapidKeys second = new VapidKeys(props("", ""), mapper);

        assertThat(second.publicKey()).isEqualTo(first.publicKey());
        assertThat(second.privateKey()).isEqualTo(first.privateKey());
    }

    @Test
    void configuredKeysWinAndNothingIsWritten() {
        String[] pair = VapidKeys.generate();

        VapidKeys keys = new VapidKeys(props(pair[0], pair[1]), mapper);

        assertThat(keys.publicKey()).isEqualTo(pair[0]);
        assertThat(keys.privateKey()).isEqualTo(pair[1]);
        assertThat(Files.exists(dataDir.resolve("vapid.json"))).isFalse();
    }

    /** 저장 도중 꺼져 반쯤 쓰인 파일이 남아도 서버는 떠야 한다. */
    @Test
    void truncatedKeyFileIsReplacedInsteadOfBlockingStartup() throws Exception {
        Files.writeString(dataDir.resolve("vapid.json"), "{\"public_key\":\"BPx", StandardCharsets.UTF_8);

        VapidKeys keys = new VapidKeys(props("", ""), mapper);

        assertThat(Base64.getUrlDecoder().decode(keys.publicKey())).hasSize(65);
        JsonNode stored = mapper.readTree(dataDir.resolve("vapid.json").toFile());
        assertThat(stored.path("public_key").asText()).isEqualTo(keys.publicKey());
    }

    @Test
    void keyFileWithWrongSizedKeysIsReplaced() throws Exception {
        Files.writeString(dataDir.resolve("vapid.json"),
                "{\"public_key\":\"AAAA\",\"private_key\":\"not base64 !!\"}", StandardCharsets.UTF_8);

        VapidKeys keys = new VapidKeys(props("", ""), mapper);

        assertThat(Base64.getUrlDecoder().decode(keys.publicKey())).hasSize(65);
        assertThat(Base64.getUrlDecoder().decode(keys.privateKey())).hasSize(32);
    }

    private AppProperties props(String publicKey, String privateKey) {
        return new AppProperties(
                dataDir.toString(), "Asia/Seoul", 3, 66, "",
                new AppProperties.MorningLock("06:00", "09:30"),
                new AppProperties.Tts(false, "python -m edge_tts", "-10%", "a", "b", 60),
                new AppProperties.Push(publicKey, privateKey, "mailto:test@example.com"),
                new AppProperties.Reminder(false, "0 0 22 * * *", "0 30 22 * * *", 3,
                        new AppProperties.Telegram(false, "", ""),
                        new AppProperties.Discord(false, "")),
                new AppProperties.Vocab("none", "claude", "haiku", 120, "", "0 0 12 * * *", 3),
                new AppProperties.Backup(false, "0 30 4 * * *", 14, ""));
    }
}
