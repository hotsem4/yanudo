package com.habit66.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.habit66.common.LogicalClock;
import com.habit66.config.AppProperties;

/**
 * 운영 DB 자동 백업. 학습 기록은 이 PC 의 SQLite 파일 하나뿐이라 매일 한 벌을 남긴다.
 *
 * <p>실행 중인 WAL DB 파일을 그대로 복사하면 깨진 사본이 될 수 있어 SQLite 의 {@code VACUUM INTO} 로 일관된 사본을 만들고,
 * 그 사본을 따로 열어 {@code PRAGMA integrity_check} 를 통과한 것만 남긴다. {@code data/backup/habit66-YYYY-MM-DD.db}
 * (논리 날짜) 로 두고 최근 {@code keep} 개만 남긴다. 이름 규칙이 다른 수동 백업은 건드리지 않는다.
 * {@code extra-dir} 를 정하면(예: 클라우드 동기화 폴더) 같은 사본과 VAPID 키를 그곳에도 둔다.
 *
 * <p>실패해도 서버 동작에 영향을 주지 않는다(로그만 남긴다).
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final String PREFIX = "habit66-";
    private static final Pattern NAME = Pattern.compile("habit66-\\d{4}-\\d{2}-\\d{2}\\.db");

    /** @param extraCopy 추가 위치에 둔 사본(설정하지 않았거나 실패했으면 null) */
    public record Result(String file, long bytes, String extraCopy) {
    }

    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final LogicalClock clock;

    public BackupService(JdbcTemplate jdbc, AppProperties props, LogicalClock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.backup.cron:0 30 4 * * *}", zone = "${app.timezone}")
    public void daily() {
        if (props.backup().enabled()) {
            runQuietly();
        }
    }

    /** 서버가 꺼져 있어 새벽 백업을 놓쳤으면 켜질 때 오늘 것을 만든다. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (props.backup().enabled() && !Files.exists(backupDir().resolve(fileName(clock.today())))) {
            runQuietly();
        }
    }

    private void runQuietly() {
        try {
            Result r = backupNow();
            log.info("DB backup written: {} ({} bytes){}", r.file(), r.bytes(),
                    r.extraCopy() == null ? "" : ", copy: " + r.extraCopy());
        } catch (Exception e) {
            log.warn("DB backup failed: {}", e.toString());
        }
    }

    /** 지금 백업을 만든다(같은 날 다시 하면 덮어쓴다). */
    public synchronized Result backupNow() throws IOException, SQLException {
        Path dir = backupDir();
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName(clock.today()));
        Path tmp = dir.resolve(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        jdbc.execute("VACUUM INTO '" + tmp.toAbsolutePath().toString().replace("'", "''") + "'");
        try {
            verify(tmp);
            move(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
        prune(dir, props.backup().keep());
        return new Result(target.toString(), Files.size(target), copyToExtra(target));
    }

    /** 추가 위치에 사본과 VAPID 키를 둔다. 실패해도 로컬 백업은 유효하므로 null 만 돌려준다. */
    private String copyToExtra(Path backup) {
        String extra = props.backup().extraDir();
        if (extra == null || extra.isBlank()) {
            return null;
        }
        try {
            Path dir = Paths.get(extra).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path copy = dir.resolve(backup.getFileName());
            Files.copy(backup, copy, StandardCopyOption.REPLACE_EXISTING);
            Path vapid = props.dataPath().resolve("vapid.json");
            if (Files.exists(vapid)) {
                Files.copy(vapid, dir.resolve("vapid.json"), StandardCopyOption.REPLACE_EXISTING);
            }
            prune(dir, props.backup().keep());
            return copy.toString();
        } catch (IOException e) {
            log.warn("DB backup copy to extra dir failed: {}", e.toString());
            return null;
        }
    }

    Path backupDir() {
        return props.dataPath().resolve("backup");
    }

    static String fileName(LocalDate date) {
        return PREFIX + date + ".db";
    }

    /** 사본이 SQLite 로 열리고 무결성 검사를 통과하는지. */
    static void verify(Path file) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            String result = rs.next() ? rs.getString(1) : "no result";
            if (!"ok".equalsIgnoreCase(result)) {
                throw new SQLException("integrity_check: " + result);
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 이름 규칙(habit66-YYYY-MM-DD.db)에 맞는 파일 중 최근 keep 개만 남긴다. */
    static void prune(Path dir, int keep) throws IOException {
        List<Path> backups = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> NAME.matcher(p.getFileName().toString()).matches()).forEach(backups::add);
        }
        backups.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        for (int i = Math.max(1, keep); i < backups.size(); i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }
}
