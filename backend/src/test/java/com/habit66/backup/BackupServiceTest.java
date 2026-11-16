package com.habit66.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.habit66.common.LogicalClock;
import com.habit66.config.AppProperties;

class BackupServiceTest {

    @TempDir
    Path dataDir;

    @TempDir
    Path extraDir;

    @Test
    void writesAVerifiedCopyNamedByLogicalDateAndCopiesToTheExtraDir() throws Exception {
        Files.writeString(dataDir.resolve("vapid.json"), "{}");
        SingleConnectionDataSource ds = new SingleConnectionDataSource(
                "jdbc:sqlite:" + dataDir.resolve("habit66.db"), true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("CREATE TABLE day_achievement (log_date TEXT PRIMARY KEY)");
        jdbc.update("INSERT INTO day_achievement VALUES ('2026-11-15')");
        // 2026-11-16 02:00 서울은 03시 롤오버 전이라 논리 날짜가 11-15 다.
        LogicalClock clock = new LogicalClock(Clock.fixed(Instant.parse("2026-11-15T17:00:00Z"), ZoneId.of("UTC")),
                ZoneId.of("Asia/Seoul"), 3);

        BackupService.Result r = new BackupService(jdbc, props(extraDir.toString()), clock).backupNow();
        ds.destroy();

        Path file = dataDir.resolve("backup").resolve("habit66-2026-11-15.db");
        assertThat(r.file()).isEqualTo(file.toString());
        assertThat(rows(file)).isEqualTo(1);
        assertThat(extraDir.resolve("habit66-2026-11-15.db")).exists();
        assertThat(extraDir.resolve("vapid.json")).exists();
        try (Stream<Path> left = Files.list(dataDir.resolve("backup"))) {
            assertThat(left.map(p -> p.getFileName().toString()).toList()).containsExactly("habit66-2026-11-15.db");
        }
    }

    @Test
    void keepsOnlyTheNewestDatedBackupsAndLeavesManualOnesAlone() throws Exception {
        Path dir = Files.createDirectories(dataDir.resolve("backup"));
        for (int day = 1; day <= 16; day++) {
            Files.writeString(dir.resolve(String.format("habit66-2026-11-%02d.db", day)), "x");
        }
        Files.writeString(dir.resolve("habit66-before-vocab-20261116.db"), "manual");

        BackupService.prune(dir, 14);

        try (Stream<Path> left = Files.list(dir)) {
            List<String> names = left.map(p -> p.getFileName().toString()).sorted().toList();
            assertThat(names).hasSize(15).contains("habit66-before-vocab-20261116.db", "habit66-2026-11-03.db",
                    "habit66-2026-11-16.db").doesNotContain("habit66-2026-11-01.db", "habit66-2026-11-02.db");
        }
    }

    private static int rows(Path db) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM day_achievement")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private AppProperties props(String extra) {
        return new AppProperties(
                dataDir.toString(), "Asia/Seoul", 3, 66, "",
                new AppProperties.MorningLock("06:00", "09:30"),
                new AppProperties.Tts(false, "python -m edge_tts", "-10%", "a", "b", 60),
                new AppProperties.Push("", "", "mailto:test@example.com"),
                new AppProperties.Reminder(false, "0 0 22 * * *", "0 30 22 * * *", 3,
                        new AppProperties.Telegram(false, "", ""),
                        new AppProperties.Discord(false, "")),
                new AppProperties.Vocab("none", "claude", "haiku", 120, "", "0 0 12 * * *", 3),
                new AppProperties.Backup(true, "0 30 4 * * *", 14, extra));
    }
}
