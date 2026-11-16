package com.habit66.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.habit66.config.AppProperties;

import jakarta.annotation.PostConstruct;

/**
 * schema.sql 은 IF NOT EXISTS 라 이미 있는 표의 제약을 바꾸지 못한다. SQLite 는 CHECK 를 ALTER 할 수 없어 표를 새로 만들어
 * 옮긴다. 시작할 때마다 돌지만 이미 바뀐 표는 건너뛴다(멱등). JdbcTemplate 을 쓰는 빈은 schema.sql 실행 뒤에 만들어진다.
 */
@Component
public class SchemaMigrations {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrations.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    /** 표를 다시 만들기 직전 DB 사본을 두는 곳(null 이면 남기지 않음, 테스트). */
    private final Path backupDir;
    private boolean backedUp;

    @org.springframework.beans.factory.annotation.Autowired
    public SchemaMigrations(JdbcTemplate jdbc, PlatformTransactionManager txManager, AppProperties props) {
        this(jdbc, txManager, props.dataPath().resolve("backup"));
    }

    SchemaMigrations(JdbcTemplate jdbc, PlatformTransactionManager txManager, Path backupDir) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.backupDir = backupDir;
    }

    @PostConstruct
    void migrate() {
        // 2026-11-16: 따라 말하기 통과(via SPEAK)를 열람·달성에 남긴다.
        widenVia("sentence_view", """
                CREATE TABLE sentence_view_new (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    log_date     TEXT    NOT NULL,
                    sentence_id  INTEGER NOT NULL REFERENCES sentence(id) ON DELETE CASCADE,
                    session      TEXT    NOT NULL CHECK (session IN ('TODAY', 'REVIEW')),
                    via          TEXT    NOT NULL CHECK (via IN ('AUDIO', 'CARD', 'SPEAK')),
                    event_id     TEXT,
                    created_at   INTEGER NOT NULL,
                    UNIQUE (log_date, sentence_id, session, via)
                )""", "id, log_date, sentence_id, session, via, event_id, created_at",
                "WHERE sentence_id IN (SELECT id FROM sentence)",
                List.of("CREATE INDEX IF NOT EXISTS idx_view_date ON sentence_view (log_date)"));
        widenVia("day_achievement", """
                CREATE TABLE day_achievement_new (
                    log_date     TEXT    PRIMARY KEY,
                    lesson_day   INTEGER NOT NULL,
                    via          TEXT    NOT NULL CHECK (via IN ('AUDIO', 'CARD', 'SPEAK')),
                    achieved_at  INTEGER NOT NULL
                )""", "log_date, lesson_day, via, achieved_at", "", List.of());
    }

    private void widenVia(String table, String createNew, String columns, String where, List<String> after) {
        String sql = jdbc.queryForList("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", String.class,
                table).stream().findFirst().orElse("");
        if (sql.isEmpty() || sql.contains("'SPEAK'")) {
            return;
        }
        backupOnce();
        tx.executeWithoutResult(status -> {
            jdbc.execute("DROP TABLE IF EXISTS " + table + "_new");
            jdbc.execute(createNew);
            // 외래 키가 켜져 있어 예전에 지워진 문장을 가리키는 행이 있으면 옮기다 실패한다. 그런 행은 버린다.
            jdbc.execute("INSERT INTO " + table + "_new (" + columns + ") SELECT " + columns + " FROM " + table + " "
                    + where);
            jdbc.execute("DROP TABLE " + table);
            jdbc.execute("ALTER TABLE " + table + "_new RENAME TO " + table);
            after.forEach(jdbc::execute);
        });
        log.info("Schema migrated: {} via now allows SPEAK", table);
    }

    /**
     * 표를 다시 만들기 전에 DB 사본을 남긴다(이전이 실패하면 서버가 뜨지 않으므로, 되살릴 수 있게).
     * 되살리기: 서버를 멈추고 이 파일을 data/habit66.db 로 복사(-wal·-shm 은 치운다).
     */
    private void backupOnce() {
        if (backedUp || backupDir == null) {
            return;
        }
        try {
            Files.createDirectories(backupDir);
            Path file = backupDir.resolve("habit66-before-migration-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".db");
            jdbc.execute("VACUUM INTO '" + file.toAbsolutePath().toString().replace("'", "''") + "'");
            backedUp = true;
            log.info("DB copied before schema migration: {}", file);
        } catch (Exception e) {
            log.warn("DB copy before schema migration failed (migrating anyway): {}", e.toString());
        }
    }
}
