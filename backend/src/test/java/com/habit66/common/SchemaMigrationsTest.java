package com.habit66.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class SchemaMigrationsTest {

    @TempDir
    Path dir;

    @Test
    void widensTheViaCheckOnAnOldDatabaseAndKeepsRows() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("old.db"), true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE sentence (id INTEGER PRIMARY KEY)");
        jdbc.execute("INSERT INTO sentence VALUES (1)");
        jdbc.execute("""
                CREATE TABLE sentence_view (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, log_date TEXT NOT NULL,
                    sentence_id INTEGER NOT NULL REFERENCES sentence(id) ON DELETE CASCADE,
                    session TEXT NOT NULL CHECK (session IN ('TODAY', 'REVIEW')),
                    via TEXT NOT NULL CHECK (via IN ('AUDIO', 'CARD')), event_id TEXT, created_at INTEGER NOT NULL,
                    UNIQUE (log_date, sentence_id, session, via))""");
        jdbc.execute("""
                CREATE TABLE day_achievement (log_date TEXT PRIMARY KEY, lesson_day INTEGER NOT NULL,
                    via TEXT NOT NULL CHECK (via IN ('AUDIO', 'CARD')), achieved_at INTEGER NOT NULL)""");
        jdbc.update("INSERT INTO sentence_view (log_date, sentence_id, session, via, event_id, created_at) "
                + "VALUES ('2026-11-16', 1, 'TODAY', 'CARD', 'e1', 1)");
        jdbc.update("INSERT INTO day_achievement VALUES ('2026-11-15', 1, 'AUDIO', 1)");

        jdbc.update("INSERT INTO sentence_view (log_date, sentence_id, session, via, event_id, created_at) "
                + "VALUES ('2026-11-14', 99, 'REVIEW', 'CARD', 'orphan', 1)"); // 지워진 문장을 가리키는 옛 행
        SchemaMigrations m = new SchemaMigrations(jdbc, new DataSourceTransactionManager(ds), dir.resolve("backup"));
        m.migrate();
        m.migrate(); // 두 번 돌아도 그대로

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sentence_view", Integer.class)).isEqualTo(1);
        try (java.util.stream.Stream<Path> copies = java.nio.file.Files.list(dir.resolve("backup"))) {
            assertThat(copies.map(p -> p.getFileName().toString()).toList()).singleElement().asString()
                    .startsWith("habit66-before-migration-");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM day_achievement", Integer.class)).isEqualTo(1);
        jdbc.update("INSERT INTO sentence_view (log_date, sentence_id, session, via, event_id, created_at) "
                + "VALUES ('2026-11-16', 1, 'TODAY', 'SPEAK', 'e2', 2)");
        jdbc.update("INSERT INTO day_achievement VALUES ('2026-11-16', 1, 'SPEAK', 2)");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sentence_view WHERE via = 'SPEAK'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_view_date'",
                String.class)).hasSize(1);
        ds.destroy();
    }
}
