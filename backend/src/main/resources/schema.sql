-- 66 Habit English SQLite 스키마 (SPEC 3장). 매 시작마다 실행되므로 모두 IF NOT EXISTS.
-- 날짜는 TEXT YYYY-MM-DD, 시각은 epoch millis INTEGER.
-- 사실만 저장한다. Day 번호, 게이트, 스트릭, 프리즈 사용은 저장하지 않는다.
-- 이 파일의 주석에는 세미콜론을 쓰지 않는다 (Spring 이 세미콜론으로 문장을 나눈다).

-- 쓰기 소유자: content.CatalogService
CREATE TABLE IF NOT EXISTS lesson (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    day_number  INTEGER NOT NULL UNIQUE,
    title       TEXT    NOT NULL,
    theme       TEXT,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

-- 쓰기 소유자: content.CatalogService (voice, status, audio 컬럼 없음)
CREATE TABLE IF NOT EXISTS sentence (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    lesson_id      INTEGER NOT NULL REFERENCES lesson(id) ON DELETE CASCADE,
    seq            INTEGER NOT NULL,
    speaker        TEXT    NOT NULL CHECK (speaker IN ('A', 'B')),
    en_text        TEXT    NOT NULL,
    ko_word_order  TEXT    NOT NULL,
    ko_natural     TEXT    NOT NULL,
    is_core        INTEGER NOT NULL DEFAULT 0,
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL,
    UNIQUE (lesson_id, seq)
);

-- 쓰기 소유자: progress.EventService (INSERT OR IGNORE 로 멱등)
CREATE TABLE IF NOT EXISTS sentence_view (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    log_date     TEXT    NOT NULL,
    sentence_id  INTEGER NOT NULL REFERENCES sentence(id) ON DELETE CASCADE,
    session      TEXT    NOT NULL CHECK (session IN ('TODAY', 'REVIEW')),
    via          TEXT    NOT NULL CHECK (via IN ('AUDIO', 'CARD', 'SPEAK')),
    event_id     TEXT,
    created_at   INTEGER NOT NULL,
    UNIQUE (log_date, sentence_id, session, via)
);

CREATE INDEX IF NOT EXISTS idx_view_date ON sentence_view (log_date);

-- 쓰기 소유자: progress.EventService (changed_at 최신값 우선)
CREATE TABLE IF NOT EXISTS sentence_mark (
    sentence_id  INTEGER PRIMARY KEY REFERENCES sentence(id) ON DELETE CASCADE,
    status       TEXT    NOT NULL CHECK (status IN ('NEW', 'MEMORIZED', 'PRACTICE')),
    changed_at   INTEGER NOT NULL
);

-- 쓰기 소유자: progress.EventService (재전송 시 큰 seconds 유지)
CREATE TABLE IF NOT EXISTS focus_session (
    started_at  INTEGER PRIMARY KEY,
    log_date    TEXT    NOT NULL,
    seconds     INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_focus_date ON focus_session (log_date);

-- 쓰기 소유자: progress.EventService (그날 첫 결정 유지)
CREATE TABLE IF NOT EXISTS morning_lock (
    log_date    TEXT    PRIMARY KEY,
    state       TEXT    NOT NULL CHECK (state IN ('CLEARED', 'SKIPPED')),
    decided_at  INTEGER NOT NULL
);

-- 쓰기 소유자: progress.EventService (완주 순간 한 번, 이후 콘텐츠가 바뀌어도 유지)
CREATE TABLE IF NOT EXISTS day_achievement (
    log_date     TEXT    PRIMARY KEY,
    lesson_day   INTEGER NOT NULL,
    via          TEXT    NOT NULL CHECK (via IN ('AUDIO', 'CARD', 'SPEAK')),
    achieved_at  INTEGER NOT NULL
);

-- 쓰기 소유자: notify.ReminderService (하루 한 번 발송 선점)
CREATE TABLE IF NOT EXISTS reminder_log (
    log_date       TEXT    PRIMARY KEY,
    status         TEXT    NOT NULL CHECK (status IN ('SENDING', 'SENT', 'FAILED')),
    attempts       INTEGER NOT NULL DEFAULT 0,
    push_sent      INTEGER NOT NULL DEFAULT 0,
    webhooks_sent  INTEGER NOT NULL DEFAULT 0,
    updated_at     INTEGER NOT NULL
);

-- 쓰기 소유자: notify.PushSubscriptionStore
CREATE TABLE IF NOT EXISTS push_subscription (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    endpoint    TEXT    NOT NULL UNIQUE,
    p256dh      TEXT    NOT NULL,
    auth        TEXT    NOT NULL,
    user_agent  TEXT,
    created_at  INTEGER NOT NULL
);

-- 쓰기 소유자: common.SettingsStore (start_date)
CREATE TABLE IF NOT EXISTS app_setting (
    key    TEXT PRIMARY KEY,
    value  TEXT
);

-- 쓰기 소유자: vocab.TermStore (콘텐츠 임포트도 TermStore.upsertFromContent 로만 쓴다)
-- term_key 는 소문자, 앞뒤 공백 제거, 연속 공백 하나로 정리한 text
-- 생성 전(PENDING)이나 실패(FAILED)면 meaning, nuance, examples_json 이 비어 있을 수 있다
CREATE TABLE IF NOT EXISTS term (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    term_key       TEXT    NOT NULL UNIQUE,
    text           TEXT    NOT NULL,
    meaning        TEXT,
    nuance         TEXT,
    examples_json  TEXT,
    source         TEXT    NOT NULL CHECK (source IN ('CONTENT', 'GENERATED', 'MANUAL')),
    status         TEXT    NOT NULL CHECK (status IN ('READY', 'PENDING', 'FAILED')),
    attempts       INTEGER NOT NULL DEFAULT 0,
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);

-- 쓰기 소유자: content.CatalogService (form 은 문장에 실제로 나온 형태)
CREATE TABLE IF NOT EXISTS sentence_term (
    sentence_id  INTEGER NOT NULL REFERENCES sentence(id) ON DELETE CASCADE,
    term_id      INTEGER NOT NULL REFERENCES term(id) ON DELETE CASCADE,
    form         TEXT    NOT NULL,
    PRIMARY KEY (sentence_id, term_id)
);

-- 쓰기 소유자: vocab.VocabService (담긴 표현. 빼면 행을 지우고 term 은 남긴다)
CREATE TABLE IF NOT EXISTS vocab_entry (
    term_id      INTEGER PRIMARY KEY REFERENCES term(id) ON DELETE CASCADE,
    sentence_id  INTEGER REFERENCES sentence(id) ON DELETE SET NULL,
    note         TEXT,
    mastered     INTEGER NOT NULL DEFAULT 0,
    mastered_at  INTEGER,
    added_at     INTEGER NOT NULL
);

-- 쓰기 소유자: vocab.VocabService (처리한 VOCAB_* 이벤트 id. 같은 id 를 다시 받으면 무시한다)
-- 다른 이벤트는 자연 키로 멱등하지만 담기와 빼기는 순서에 따라 결과가 달라 id 로 막는다
-- 쓰기 소유자: progress.EventService. 말하기 연습(부가 기능): 한 문장을 5번 맞게 말한 사실. 습관·게이트와 무관하다.
CREATE TABLE IF NOT EXISTS speak_practice (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    sentence_id  INTEGER NOT NULL REFERENCES sentence(id) ON DELETE CASCADE,
    log_date     TEXT    NOT NULL,
    reps         INTEGER NOT NULL,
    event_id     TEXT    NOT NULL UNIQUE,
    created_at   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_speak_sentence ON speak_practice (sentence_id);

-- 쓰기 소유자: progress.EventService. 그날 안에 "오늘은 어려워요"라고 미리 알린 날(SPEC F-05).
-- 그날을 빼먹어도 보충이 가벼워진다(10문장 1번씩). 30일 구간마다 4번까지.
CREATE TABLE IF NOT EXISTS rest_day (
    log_date     TEXT    PRIMARY KEY,
    declared_at  INTEGER NOT NULL
);

-- 쓰기 소유자: progress.EventService. 처리한 VIEW·MORNING_LOCK 이벤트 id(재전송이 다른 날짜로 다시 기록되지 않게)
CREATE TABLE IF NOT EXISTS applied_event (
    id          TEXT    PRIMARY KEY,
    applied_at  INTEGER NOT NULL
);
-- 원장 이전에 저장된 VIEW 의 id 도 원장에 옮긴다(업그레이드 직전에 응답을 잃은 VIEW 의 재전송을 막는다). 다시 돌아도 같은 결과.
INSERT OR IGNORE INTO applied_event (id, applied_at)
    SELECT event_id, created_at FROM sentence_view WHERE event_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS vocab_event (
    id          TEXT    PRIMARY KEY,
    applied_at  INTEGER NOT NULL
);
