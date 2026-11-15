package com.habit66.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app")
public record AppProperties(
        @DefaultValue("./data") String dataDir,
        @DefaultValue("Asia/Seoul") String timezone,
        @DefaultValue("3") int dayRolloverHour,
        @DefaultValue("66") int totalDays,
        @DefaultValue("") String adminToken,
        @DefaultValue MorningLock morningLock,
        @DefaultValue Tts tts,
        @DefaultValue Push push,
        @DefaultValue Reminder reminder,
        @DefaultValue Vocab vocab) {

    /** 단어장 생성기 설정(SPEC 4.9). */
    public record Vocab(
            @DefaultValue("claude-cli") String generator,
            @DefaultValue("claude") String claudeCommand,
            @DefaultValue("haiku") String claudeModel,
            @DefaultValue("120") int timeoutSeconds) {
    }

    public record MorningLock(
            @DefaultValue("06:00") String start,
            @DefaultValue("09:30") String end) {
    }

    public record Tts(
            @DefaultValue("true") boolean autoGenerate,
            @DefaultValue("python -m edge_tts") String command,
            @DefaultValue("-10%") String rate,
            @DefaultValue("en-US-AndrewMultilingualNeural") String voiceA,
            @DefaultValue("en-US-AvaMultilingualNeural") String voiceB,
            @DefaultValue("60") int timeoutSeconds) {

        public String voiceFor(String speaker) {
            return "B".equals(speaker) ? voiceB : voiceA;
        }
    }

    public record Push(
            @DefaultValue("") String publicKey,
            @DefaultValue("") String privateKey,
            @DefaultValue("mailto:habit66@example.com") String subject) {
    }

    public record Reminder(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0 0 22 * * *") String cron,
            @DefaultValue("0 30 22 * * *") String retryCron,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue Telegram telegram,
            @DefaultValue Discord discord) {
    }

    public record Telegram(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String botToken,
            @DefaultValue("") String chatId) {
    }

    public record Discord(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String webhookUrl) {
    }

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    public Path dataPath() {
        return Paths.get(dataDir).toAbsolutePath().normalize();
    }

    public Path audioPath() {
        return dataPath().resolve("audio");
    }
}
