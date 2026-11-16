package com.habit66.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
        @DefaultValue Vocab vocab,
        @DefaultValue Backup backup) {

    /**
     * DB 자동 백업. 매일 cron 에 data/backup/habit66-YYYY-MM-DD.db 를 만들고 최근 keep 개만 남긴다.
     * extraDir 를 정하면(예: 클라우드 동기화 폴더) 같은 사본과 VAPID 키를 그곳에도 둔다.
     */
    public record Backup(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0 30 4 * * *") String cron,
            @DefaultValue("14") int keep,
            @DefaultValue("") String extraDir) {
    }

    /** 단어장 생성기 설정(SPEC 4.9). */
    public record Vocab(
            @DefaultValue("claude-cli") String generator,
            @DefaultValue("claude") String claudeCommand,
            @DefaultValue("haiku") String claudeModel,
            @DefaultValue("120") int timeoutSeconds,
            @DefaultValue("") String claudeOauthToken,
            @DefaultValue("0 0 12 * * *") String loginCheckCron,
            @DefaultValue("3") int loginWarnDays) {

        /** 토큰이 로그에 찍히지 않게 가린다(레코드 기본 toString 은 모든 값을 보여 준다). */
        @Override
        public String toString() {
            return "Vocab[generator=" + generator + ", claudeCommand=" + claudeCommand + ", claudeModel=" + claudeModel
                    + ", timeoutSeconds=" + timeoutSeconds + ", claudeOauthToken="
                    + (claudeOauthToken == null || claudeOauthToken.isBlank() ? "(none)" : "(set)")
                    + ", loginCheckCron=" + loginCheckCron + ", loginWarnDays=" + loginWarnDays + "]";
        }
    }

    public record MorningLock(
            @DefaultValue("06:00") String start,
            @DefaultValue("09:30") String end) {
    }

    /**
     * 음성 합성. engine 이 "azure" 이고 키·지역이 있으면 Azure Speech(REST, 48kHz·192kbps)로, 아니면 edge-tts 로 만든다.
     * 같은 목소리라도 품질이 다른 파일이므로 Azure 파일은 이름 규칙에 variant 를 더해 따로 만든다.
     */
    public record Tts(
            @DefaultValue("true") boolean autoGenerate,
            @DefaultValue("python -m edge_tts") String command,
            @DefaultValue("-10%") String rate,
            @DefaultValue("en-US-AndrewMultilingualNeural") String voiceA,
            @DefaultValue("en-US-AvaMultilingualNeural") String voiceB,
            @DefaultValue("120") int timeoutSeconds,
            @DefaultValue("edge") String engine,
            @DefaultValue Azure azure) {

        /** 설정 바인딩은 이 정식 생성자로 한다(아래 6개짜리는 테스트·코드에서 쓰는 편의 생성자). */
        @ConstructorBinding
        public Tts {
        }

        public Tts(boolean autoGenerate, String command, String rate, String voiceA, String voiceB, int timeoutSeconds) {
            this(autoGenerate, command, rate, voiceA, voiceB, timeoutSeconds, "edge", new Azure("", "", Azure.FORMAT));
        }

        public String voiceFor(String speaker) {
            return "B".equals(speaker) ? voiceB : voiceA;
        }

        /** 실제로 Azure 로 만드는가(키·지역이 없으면 설정이 azure 여도 edge-tts 로 돌아간다). */
        public boolean useAzure() {
            return "azure".equalsIgnoreCase(engine) && azure != null && !azure.key().isBlank() && !azure.region().isBlank();
        }

        /** 파일 이름 규칙에 더하는 값. edge-tts 는 비워 두어 기존 파일 이름이 그대로다. */
        public String variant() {
            return useAzure() ? "azure:" + azure.format() : "";
        }
    }

    /** Azure Speech. key·region 은 config/application.yml 에만 둔다(저장소에 올리지 않는다). */
    public record Azure(
            @DefaultValue("") String key,
            @DefaultValue("") String region,
            @DefaultValue(Azure.FORMAT) String format) {

        public static final String FORMAT = "audio-48khz-192kbitrate-mono-mp3";

        @Override
        public String toString() {
            return "Azure[key=" + mask(key) + ", region=" + region + ", format=" + format + "]";
        }
    }

    public record Push(
            @DefaultValue("") String publicKey,
            @DefaultValue("") String privateKey,
            @DefaultValue("mailto:habit66@example.com") String subject) {

        @Override
        public String toString() {
            return "Push[publicKey=" + publicKey + ", privateKey=" + mask(privateKey) + ", subject=" + subject + "]";
        }
    }

    public record Reminder(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0 0 22 * * *") String cron,
            @DefaultValue("0 */15 22-23,0-2 * * *") String retryCron,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue Telegram telegram,
            @DefaultValue Discord discord) {
    }

    public record Telegram(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String botToken,
            @DefaultValue("") String chatId) {

        @Override
        public String toString() {
            return "Telegram[enabled=" + enabled + ", botToken=" + mask(botToken) + ", chatId=" + mask(chatId) + "]";
        }
    }

    public record Discord(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String webhookUrl) {

        @Override
        public String toString() {
            return "Discord[enabled=" + enabled + ", webhookUrl=" + mask(webhookUrl) + "]";
        }
    }

    /** 비밀값은 있는지만 보인다. */
    static String mask(String secret) {
        return secret == null || secret.isBlank() ? "(none)" : "(set)";
    }

    @Override
    public String toString() {
        return "AppProperties[dataDir=" + dataDir + ", timezone=" + timezone + ", dayRolloverHour=" + dayRolloverHour
                + ", totalDays=" + totalDays + ", adminToken=" + mask(adminToken) + ", push=" + push
                + ", reminder=" + reminder + ", vocab=" + vocab + ", backup=" + backup + "]";
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
