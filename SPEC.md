# SPEC — 66 Habit English (영어 회화 암송 · 66일 습관 PWA)

1인용 영어 회화 암송 앱. Windows PC 한 대가 서버이고, 갤럭시 Z 폴드에서 Tailscale HTTPS로 PWA를 설치해 쓴다.
이 문서는 기능과 API의 **계약**이다. 코드를 바꾸면 이 문서도 같이 고친다. 모듈 구조와 설계 근거는 [docs/architecture.md](docs/architecture.md).

---

## 0. 용어

| 용어 | 뜻 |
|---|---|
| 논리 날짜(`today`) | 서버 시간대(`app.timezone`, 기본 `Asia/Seoul`)에서 `app.day-rollover-hour`(기본 03시) 이전이면 전날로 친다. 새벽 1시 학습은 전날 기록이다. |
| `current_day` | 시작일(`start_date`)부터 오늘까지 지난 날 수 + 1. 1~66으로 자른다. 하루를 빠져도 Day는 달력대로 넘어간다. |
| `lesson_day` | 오늘 실제로 보여 줄 레슨 일차. 등록된 일차 중 `current_day` 이하의 최댓값(일차가 건너뛰어져도 동작). 콘텐츠가 모자라면 `content_missing = true`. 등록된 레슨이 없으면 0. |
| 세션 | `TODAY`(오늘의 10문장) 또는 `REVIEW`(누적 복습). |
| 열람(view) | 한 문장을 한 번 거쳤다는 기록. `via = AUDIO`(오디오 차례가 끝남) 또는 `CARD`(카드를 1.2초 이상 봄). 날짜·문장·세션·경로마다 한 번만 저장한다. |
| 이벤트 | 클라이언트가 만든 학습 사실 하나(VIEW, MARK, FOCUS, MORNING_LOCK). 클라이언트가 정한 `id`로 멱등. `POST /events` 하나로만 쓴다. |
| 오늘 구간 | 오늘 논리 날짜의 시작·끝 epoch ms(`valid_from`, `valid_until`). 프론트는 이 구간 안의 대기 이벤트만 오늘 화면에 반영한다. |

---

## 1. 기능

### F-01. 일일 학습 큐 & 누적 복습

- 하루 신규 10문장(레슨 1개). 66일 × 10 = 660문장까지 확장한다.
- 상단 세그먼트 탭: `오늘의 10문장 (Day N)` / `누적 복습`.
- 누적 복습 큐 = `lesson_day` 직전의 등록된 일차 최대 4개(보통 `Day N-4 ~ Day N-1`). **최근 일차가 먼저** 나온다(Day N-1 → N-2 → …). 일차 안에서는 `seq` 순서.
  - Day 2~4는 쌓인 전체 일차가 그대로 나온다.
  - Day 1은 복습할 이전 일차가 없으므로 Day 1 문장을 미리보기 복습으로 보여 준다(`preview: true`).
  - 콘텐츠가 모자라면 `current_day`가 아니라 `lesson_day` 기준이다(등록되지 않은 일차는 복습할 수 없다).

### F-02. 3단계 어순 인출 플래시카드

- 문장 필드: `en_text`(영어 원문), `ko_word_order`(영어 어순 직역, 덩어리를 ` / `로 구분), `ko_natural`(자연스러운 해석).
- 가리기 필터(원탭 전환):

| 필터 | en_text | ko_word_order | ko_natural |
|---|---|---|---|
| `all` 전체 | 보임 | 보임 | 보임 |
| `hint` 어순힌트 | 블러 | **보임** | 블러 |
| `korean` 한글만 | 블러 | 블러 | **보임** |

- 블러된 카드를 탭하면 부드러운 전환으로 2.5초 동안 블러가 풀린다(다시 탭하면 즉시 닫힘).
- 카드 좌우 스와이프 = 이전/다음 문장. 카드와 오디오 플레이어는 **같은 커서(index)** 를 쓴다.
- 문장별 상태 토글: `NEW` → `MEMORIZED`(암기 완료) ↔ `PRACTICE`(다시 연습). 서버에 저장한다.

### F-03. 3모드 핸즈프리 오디오 플레이어

| 모드 | 동작 |
|---|---|
| `full` 연속 | 큐를 처음부터 끝까지 재생. 문장 사이 0.6초 간격. |
| `shadowing` 끊어읽기 | 문장 재생이 끝나면(`ended`) `duration_ms × 1.3` 동안 무음 대기. 재생 버튼 테두리와 카드에 카운트다운 링 표시. |
| `roleplay` 역할극 | `speaker = A`는 정상 재생. `speaker = B`는 소리를 내지 않고 영어 원문을 가린 채 `duration_ms × 1.3` 동안 대기(사용자가 B 대사를 말함). |

- 큐 끝에 닿으면 멈춘다.
- **프리로딩**: 세션을 불러오면 그 큐의 MP3를 모두 `Cache API`(`audio-v1`)에 받아 두고 `Blob URL`로 재생한다. 지하철 터널에서 네트워크가 끊겨도 재생이 이어진다. Cache API가 없으면 `fetch → Blob`만 쓴다.
- **무음 대기 중 미디어 세션 유지**: 대기 동안 같은 `<audio>` 요소에 1초짜리 무음 WAV를 반복 재생해 잠금 화면 컨트롤과 버즈 버튼이 살아 있게 한다.
- **Media Session API**: 메타데이터(영어 문장, `Day N · A/B`), `play` / `pause` / `previoustrack` / `nexttrack` 핸들러.
- 배속: `0.85` / `1.0` / `1.15` 순환 토글. 무음 대기 시간은 배속과 무관하게 `duration_ms × 1.3`.
- 오디오 파일이 아직 없거나 받아 두지 못한 문장은 기기 `speechSynthesis`(en-US)로 대신 읽는다. `duration_ms`는 서버가 늘 채운다(클립이 없으면 단어 수 × 380ms 추정, `duration_estimated: true`).
- 무음 대기와 문장 간격은 그 길이의 무음 WAV를 같은 `<audio>` 요소에서 재생하고 `ended`로 끝낸다(화면이 꺼져도 타이머가 밀리지 않고, 일시정지하면 남은 시간이 보존된다).
- `AUDIO` 열람 보고 시점: full·shadowing·역할극 A는 발화가 끝날 때, 역할극 B는 대기가 끝날 때.
- 프리로드 상태(`준비 n/m`)를 화면에 작게 보인다.

### F-04. 부드러운 강제성 장치 4종

1. **URL 자동 실행**: `/?autoplay=true&mode=shadowing|roleplay|full`
   - 복습 게이트가 잠겨 있으면 `누적 복습`을 먼저 재생하고, 재생 중 게이트가 풀리면 큐가 끝난 뒤 `오늘의 10문장`으로 이어서 재생한다.
   - 자동 재생은 모닝 보이스 락보다 우선한다(오디오 재생 자체가 학습 시작).
   - 브라우저가 소리 재생을 막으면(`NotAllowedError`) "탭해서 재생 시작" 버튼을 띄운다. 홈 화면에 설치한 PWA는 Chrome 정책상 자동 재생이 허용된다.
   - 매니페스트 `shortcuts`에 세 모드의 자동 실행 URL을 등록한다(앱 아이콘 길게 누르기).
2. **모닝 보이스 락**: 벽시계 `06:00 ~ 09:30`에 앱을 열었고(앱 시작·화면 복귀 때 판단), 오늘 열람 기록이 하나도 없고(서버 + 대기 이벤트), 오늘 락을 아직 처리하지 않았으면 표시한다. 시간 창은 서버가 epoch ms로 내려 주므로 오프라인에서도 판단한다. 판정은 클라이언트가 하고 결과만 `MORNING_LOCK` 이벤트로 보낸다.
   - 오늘 레슨에서 `core = true`인 문장 3개(모자라면 `seq` 순으로 채움).
   - `SpeechRecognition`(en-US, interim 결과 사용)으로 인식. 정답 단어 중 **순서대로** 인식된 단어 비율(소문자·구두점 제거·축약형 펼침, 최장 공통 부분 수열)이 **60% 이상**이면 그 문장 클리어. 단어를 뒤섞어 말하면 통과하지 못한다. 발음·억양은 평가하지 않는다(브라우저 인식의 한계). 3문장이면 잠금 해제(`CLEARED`).
   - **받아 적기**: 말하기가 어려우면 "받아 적기로" 바꿔 문장을 듣고 영어로 입력한다. 통과 전까지 영어 문장은 가리고(맞힌 단어만 드러냄) 같은 60% 규칙으로 채점한다. 마이크·인식을 쓸 수 없으면(미지원, 권한 거부, 마이크 없음, 네트워크) 자동으로 받아 적기가 된다.
   - `긴급 건너뛰기`(`SKIPPED`)는 마이크·인식을 쓸 수 없을 때나 한 문장에서 3번 못 넘겼을 때만 보이고, 한 번 더 확인받는다. 문장 듣기 버튼 제공.
   - 범위는 PWA 화면 안이다. 폰의 다른 앱을 막지는 못한다(웹 앱 권한 밖).
3. **어제 복습 게이트**: `lesson_day`보다 작은 등록 일차가 있으면 그중 가장 큰 일차가 "어제 레슨"이다(보통 Day `lesson_day-1`, 일차가 건너뛰어졌으면 직전 등록 일차). 그 레슨 문장이 **오늘** `REVIEW` 세션에서 모두 열람(`AUDIO` 또는 `CARD`)될 때까지 `오늘의 10문장` 카드는 블러·잠금 상태다. 다 채우는 순간 즉시 풀린다. 어제 레슨이 없으면(Day 1) 게이트도 없다.
4. **22:00 마감 리마인드**: `@Scheduled(cron = "0 0 22 * * *", zone = app.timezone)` + 22:30 재시도 + 서버 시작 시 22:00~03:00이면 보충. **서버에 도착한 기록 기준**으로 오늘 습관이 미달성이면 Web Push(VAPID) + 선택적으로 Telegram/Discord 웹훅 발송. 하루 한 번만 보내고(`reminder_log`), 모든 채널이 실패하면 하루 3회까지 다시 시도한다.
   - VAPID 키가 설정에 없으면 첫 실행 때 만들어 `data/vapid.json`에 저장한다.
   - 푸시 서비스가 404/410을 돌려주면 그 구독을 지운다.
   - PC가 22시 전후에 꺼져 있으면 알림이 가지 않는다.
   - 프로그램이 끝난 뒤(`program_complete = true`)나 등록된 레슨이 하나도 없을 때는 보내지 않는다(달성할 대상이 없거나 66일이 끝났다).

### F-05. 66일 습관 트래커 & 스트릭 방어

- 오늘 달성 = 오늘 레슨 문장 전부 `TODAY`·`AUDIO` 열람 **또는** 전부 `TODAY`·`CARD` 열람. 완주하는 순간 서버가 `day_achievement`에 한 번 기록한다. 이 기록은 이후 콘텐츠가 바뀌어도 뒤집히지 않는다. 빈 레슨은 완주로 치지 않는다.
- TODAY 열람인데 그 문장이 그 날짜의 오늘 레슨에 속하지 않으면(롤오버 뒤에도 열어 둔 어제 큐 등) 서버가 REVIEW로 바꿔 기록한다.
- 스트릭 프리즈: 30일 단위 구간(`Day 1-30`, `31-60`, `61-66`)마다 2개. 쓰지 않은 프리즈는 다음 구간으로 넘어가지 않는다.
- 스트릭 계산(시작일부터 `min(어제, 시작일+65)`까지 순서대로, 결정적, 저장하지 않음):
  - 달성한 날: `streak += 1`
  - 미달성 + 스트릭 > 0 + 그 구간 프리즈 남음: 프리즈 1개 차감, 스트릭 유지(증가 없음), 그날은 `frozen`
  - 미달성 + (스트릭 0 또는 프리즈 없음): `streak = 0`
  - 오늘: 달성했으면 `+1`, 아직이면 그대로(오늘은 끊지 않는다).
- 시작일 + 66일 이후: `current_day = 66` 고정, `program_complete = true`.
- 관리자가 시작일을 바꾸면 Day 귀속·히트맵·스트릭이 새 시작일 기준으로 다시 계산된다(기록은 날짜로 저장되어 사라지지 않는다).
- 헤더 좌측 카드: `Day N / 66`, 진행률 바, `🔥 N`, 남은 프리즈. 탭하면 66칸 히트맵 모달.

### F-06. 25분 모래시계 타이머

- 헤더 우측 카드. 탭하면 25:00부터 카운트다운, 모래시계 게이지가 줄어든다.
- 0이 되면 알람·강제 종료 없이 `+00:01`부터 조용히 카운트업(초과 집중).
- 시작 시각은 `localStorage`에 저장해 새로고침해도 이어진다. 끝내기를 누르면 `FOCUS` 이벤트(`id = focus:<started_at>`)로 집중 시간을 기록한다.

### F-07. 콘텐츠 확장 파이프라인

- 첫 실행 때 레슨 테이블이 비어 있으면 `classpath:seed/lessons-day01-05.json`(Day 1~5, 50문장)을 넣는다.
- `POST /api/v1/admin/lessons/import`로 JSON 일괄 업서트. 같은 `(day, seq)`면 텍스트만 바꾸고 문장 id와 암기 상태(`sentence_mark`)는 그대로 둔다. JSON에 없는 기존 `seq`는 지우지 않는다. 텍스트·화자가 바뀌면 파일 이름이 바뀌어 새 오디오가 필요해진다(자동).
- `scripts/generate_tts.py`가 서버에서 작업 목록을 받아 MP3를 병렬로 만들고 서버에 올린다. 파일 이름·길이 규칙은 서버(Java)에만 있다.

### F-08. 단어장

- **표현(term)**: 단어 또는 짧은 구. 항목은 `text`(사전형, 예: `grab`), `meaning`(이 뜻의 짧은 한국어), `nuance`(언제·어떤 느낌으로 쓰는지 한국어 한 문장), `examples`(일상 예문 3개, 각각 `en`·`ko`).
- **미리 만든 표현**: 레슨 임포트 때 문장마다 배울 만한 표현 2~3개를 함께 넣는다(`terms`, 4.7 형식). 같은 표현은 `term_key` = 소문자·공백 정리한 `text`로 하나로 합친다.
- **담기**: 학습 카드의 단어 버튼 → 그 문장의 미리 만든 표현(뜻 포함)과 나머지 단어 칩 → 눌러 담는다. 오프라인에서도 담기는 이벤트(`VOCAB_ADD`)로 쌓였다가 전송된다.
- **미리 만들지 않은 단어**를 담으면 서버가 생성기로 뜻·느낌·예문 3개를 만든다(`status: PENDING` → `READY` / `FAILED`). 생성기는 `app.vocab.generator`로 고른다.
  - `claude-cli`(기본): 로그인된 Claude Code 구독으로 `claude -p` 헤드리스 실행. 사용자 설정 훅을 피하려고 `--setting-sources project`, 출력은 `--json-schema`로 강제, 도구 없음(`--tools ""`), 세션 저장 안 함. 실패하면 `FAILED`로 두고 다음 시작·재시도 때 다시 한다.
    - **장기 토큰(선택)**: `claude setup-token`(구독으로 만드는 1년짜리 토큰, API 요금 아님)을 `app.vocab.claude-oauth-token`에 넣으면 `CLAUDE_CODE_OAUTH_TOKEN`으로 넘긴다. 비우면 PC에 로그인된 구독을 쓴다(만료일이 있어 약 한 달마다 다시 로그인).
    - **로그인 끊김 알림**: 서버 시작 때와 매일 `login-check-cron`(기본 12:00)에 짧은 실제 호출 한 번으로 로그인을 점검한다(`claude auth status`는 토큰을 확인하지 않아 쓰지 않는다). 생성이나 점검이 로그인 문제(401·`authentication_error`)로 실패하면 폰(웹 푸시·웹훅)에 알리고, 구독 로그인은 만료 `login-warn-days`(기본 3)일 전부터 미리 알린다. 같은 알림은 논리 날짜 하루 한 번. 다시 성공하면 그사이 실패한 표현을 자동으로 다시 만든다. 단어장 화면은 뜻을 기다리는 단어의 뒷면에 로그인이 끊긴 이유를 보여 준다.
  - `anthropic-api`: API 키로 Claude Haiku 5.5. `none`: 생성하지 않고 사용자가 직접 뜻을 적는다.
  - 사용자는 어떤 표현이든 뜻·메모를 직접 고칠 수 있다(직접 입력이 생성값보다 우선).
- **예문 음성**: 단어장에 담는 순간 그 표현의 예문 3개 음성을 만든다(1·3번 Ava, 2번 Andrew, `-10%`). 준비 전에는 기기 음성으로 대신 읽는다.
- **단어장 탭**: 세션 탭 세 번째. 카드처럼 넘겨 본다(앞면: 표현 / 탭하면: 뜻·느낌·예문 3개·처음 본 학습 문장). 예문마다 듣기 버튼. "외웠어요" 표시, 외우는 중 / 외운 단어 / 전체 필터, 지우기.

---

## 2. TTS & 오디오 캐시

| 화자 | 보이스 | 속도 |
|---|---|---|
| A | `en-US-AndrewMultilingualNeural` | `-10%` |
| B | `en-US-AvaMultilingualNeural` | `-10%` |

- 파일 이름: `sha1("{voice}|{rate}|{en_text}")` 16진 앞 20자 + `.mp3`. **규칙은 Java(`tts.Utterance`)에만 있다.** Python 스크립트는 서버가 계산한 이름을 받아 쓴다.
- 저장 위치: `{app.data-dir}/audio/` (기본 `./data/audio/`). **디스크가 진실**이다. DB에 오디오 컬럼이 없고, 서버가 시작할 때 폴더를 읽어 길이를 메모리에 색인한다.
- `duration_ms`: MP3 프레임 헤더를 끝까지 세어 계산(외부 라이브러리 없음, Java `tts.Mp3Frames`). edge-tts 출력은 24kHz MPEG-2 Layer III.
- 받아들이는 조건: 프레임이 파일 바이트의 90% 이상을 덮고 길이 200ms 이상. 같은 이름의 유효한 파일이 이미 있으면 덮어쓰지 않고 성공으로 끝난다.
- 백엔드 생성: `app.tts.command`(기본 `python -m edge_tts`) + `--voice` `--rate=-10%` `--file <임시 txt>` `--write-media <임시 mp3>` → 검증 → 원자적 이동. 문장 텍스트는 파일로 넘겨 Windows 인자 따옴표 문제를 피한다. 단일 스레드이고, 작업 중 새로 생긴 작업은 끝난 뒤 한 번 더 스캔한다.
- 서버 시작 시 `app.tts.auto-generate: true`이면 없는 클립을 백그라운드에서 만든다.
- 서빙: `GET /api/v1/audio/{file}.mp3` (정적 리소스 핸들러, Range 지원, `Cache-Control: public, max-age=31536000, immutable`).

---

## 3. 데이터베이스 (SQLite, `data/habit66.db`)

스키마 원본: `backend/src/main/resources/schema.sql`. 날짜는 `TEXT 'YYYY-MM-DD'`, 시각은 epoch millis `INTEGER`. **사실만 저장하고 파생값(Day 번호, 게이트, 스트릭, 프리즈 사용)은 저장하지 않는다.** 테이블마다 쓰기 소유자는 하나다.

```
lesson(id PK, day_number UNIQUE, title, theme, created_at, updated_at)                 -- content
sentence(id PK, lesson_id FK, seq, speaker 'A'|'B', en_text, ko_word_order, ko_natural,
         is_core, created_at, updated_at, UNIQUE(lesson_id, seq))                       -- content
sentence_view(id PK, log_date, sentence_id FK, session 'TODAY'|'REVIEW', via 'AUDIO'|'CARD',
              event_id, created_at, UNIQUE(log_date, sentence_id, session, via))         -- progress
sentence_mark(sentence_id PK FK, status 'NEW'|'MEMORIZED'|'PRACTICE', changed_at)        -- progress, 최신값 우선
focus_session(started_at PK, log_date, seconds, updated_at)                              -- progress, 재전송 시 큰 값
morning_lock(log_date PK, state 'CLEARED'|'SKIPPED', decided_at)                         -- progress, 첫 결정 유지
day_achievement(log_date PK, lesson_day, via 'AUDIO'|'CARD', achieved_at)                -- progress, 완주 순간 1회
reminder_log(log_date PK, status 'SENDING'|'SENT'|'FAILED', attempts, push_sent,
             webhooks_sent, updated_at)                                                  -- notify
push_subscription(id PK, endpoint UNIQUE, p256dh, auth, user_agent, created_at)          -- notify
app_setting(key PK, value)                                                               -- common (start_date)
term(id PK, term_key UNIQUE, text, meaning, nuance, examples_json, source 'CONTENT'|'GENERATED'|'MANUAL',
     status 'READY'|'PENDING'|'FAILED', attempts, created_at, updated_at)                -- vocab (내용 임포트도 이 표에 업서트)
sentence_term(sentence_id FK, term_id FK, form, PRIMARY KEY(sentence_id, term_id))        -- content (form: 문장 속 실제 형태)
vocab_entry(term_id PK FK, sentence_id FK NULL, note, mastered, mastered_at, added_at)    -- vocab (담긴 표현)
vocab_event(id PK, applied_at)                                                           -- vocab (처리한 VOCAB_* 이벤트 id)
```

---

## 4. REST API (`/api/v1`, JSON은 snake_case)

모든 프론트엔드 호출은 **상대 경로**다. 오류 응답: `{"error": "코드", "message": "설명"}`. 시각 필드(`*_at`, `server_time`, `valid_*`, `window_*`)는 모두 epoch ms 숫자다.

### 4.1 문장 (세션 응답 안의 모양)

```json
{ "id": 21, "day": 3, "seq": 1, "speaker": "A",
  "en_text": "Hi, what can I get for you today?",
  "ko_word_order": "안녕하세요, / 무엇을 제가 드릴 수 있을까요 / 오늘?",
  "ko_natural": "안녕하세요, 오늘은 뭘로 드릴까요?",
  "core": false,
  "status": "NEW", "status_changed_at": null,
  "audio_url": "/api/v1/audio/3f2a9c0d1e2b3a4c5d6e.mp3",
  "duration_ms": 2304, "duration_estimated": false,
  "terms": [ { "term_key": "what can i get for you", "text": "What can I get for you", "form": "what can I get for you",
               "meaning": "뭘로 드릴까요" } ] }
```

`terms`는 단어 고르기 화면용 가벼운 모양이다(뜻까지만). 전체 항목은 4.9 단어장 API에 있다.

### 4.2 대시보드

`GET /api/v1/dashboard`

```json
{
  "today": "2026-11-15",
  "server_time": 1763180000000,
  "valid_from": 1763143200000, "valid_until": 1763229600000,
  "current_day": 3, "total_days": 66, "lesson_day": 3, "max_lesson_day": 5,
  "content_missing": false, "program_complete": false,
  "streak": { "current": 2, "if_achieved_today": 3, "longest": 2, "achieved_today": false,
              "freezes_left": 2, "freezes_per_period": 2 },
  "today_progress": { "total": 10, "sentence_ids": [21, 22],
                      "audio_seen_ids": [21], "card_seen_ids": [21, 22] },
  "review_gate": { "required": true, "cleared": false, "yesterday_day": 2,
                   "unlock_ids": [11, 12], "seen_review_ids": [11] },
  "review_progress": { "days": [2, 1], "total": 20, "seen": 3 },
  "morning_lock": { "window_start": 1763154000000, "window_end": 1763166600000,
                    "state": "NONE", "sentences": [ /* 문장 × 3 */ ] },
  "focus": { "seconds_today": 1500, "started_ats": [1763170000000] }
}
```

- `lesson_day = 0`이면(레슨 없음) 문장 목록은 비고 `content_missing = true`.
- `review_gate.required = false`면 `unlock_ids`는 빈 배열.
- `streak.if_achieved_today`: 오늘 달성하면 될 스트릭 값(오프라인 낙관 표시용).

### 4.3 세션

`GET /api/v1/sessions/today` · `GET /api/v1/sessions/review`

```json
{ "session": "TODAY", "days": [3], "title": "카페에서 주문하기", "preview": false,
  "sentences": [ /* 4.1 문장 */ ] }
```

잠금 여부는 세션에 없다. 게이트의 출처는 대시보드 `review_gate` 하나다.

### 4.4 이벤트 (모든 학습 쓰기)

`POST /api/v1/events`

```json
{ "events": [
  { "id": "6f1c…", "type": "VIEW", "occurred_at": 1763170000000,
    "sentence_id": 21, "session": "TODAY", "via": "AUDIO" },
  { "id": "a2b9…", "type": "MARK", "occurred_at": 1763170001000,
    "sentence_id": 21, "status": "MEMORIZED" },
  { "id": "focus:1763168000000", "type": "FOCUS", "occurred_at": 1763170002000,
    "started_at": 1763168000000, "seconds": 1620 },
  { "id": "c7d0…", "type": "MORNING_LOCK", "occurred_at": 1763155000000, "result": "CLEARED" }
] }
```

응답:

```json
{ "accepted": ["6f1c…", "a2b9…"], "rejected": [{ "id": "zz", "reason": "UNKNOWN_SENTENCE" }],
  "dashboard": { /* 4.2 */ } }
```

- `id`: 1~64자 `[A-Za-z0-9:._-]`. 같은 id를 다시 보내도 결과가 같다. 한 요청 최대 500개. 시각 필드에 소수가 오면 서버가 소수부를 버린다(클라이언트는 정수로 보낸다).
- `rejected[].reason`: `INVALID_ID` | `INVALID_TYPE` | `INVALID_FIELD` | `UNKNOWN_SENTENCE`.
- `occurred_at`은 서버 시계 추정값(클라이언트가 `server_time`으로 보정). 서버는 미래 값을 수신 시각으로 자른 뒤 논리 날짜로 바꾼다.
- VIEW(TODAY)의 문장이 그 날짜의 오늘 레슨이 아니면 REVIEW로 바꿔 기록한다. 그 결과 오늘 레슨을 한 경로로 완주하면 `day_achievement`를 기록한다.
- MARK는 `changed_at`(= `occurred_at`)이 같거나 더 새로울 때 바꾼다(같은 시각이면 나중에 온 것). FOCUS는 `started_at` 기준으로 큰 `seconds`만 남기고(1~21600초), 그 기록의 날짜는 `occurred_at`(끝낸 시각) 기준이다. MORNING_LOCK은 그날 첫 결정만 남는다.
- 형식 오류·없는 문장은 그 이벤트만 `rejected`, 나머지는 한 트랜잭션으로 수용한다.
- 단어장 이벤트(F-08):
  - `{"type":"VOCAB_ADD", "text":"grab", "sentence_id":21, "meaning":null, "note":null}`: `text`의 `term_key`로 표현을 찾고, 없으면 `PENDING`으로 만들어 생성기에 맡긴다. 이미 담겨 있으면 무시(멱등). `meaning`을 주면 직접 입력으로 저장(`source: MANUAL`, 생성 안 함). `note`는 메모로만 저장한다(생성은 그대로). `sentence_id`가 현재 콘텐츠에 없으면 거절하지 않고 문장 연결만 버린다(거절하면 담기가 아웃박스에서 사라진다).
  - `{"type":"VOCAB_REMOVE", "term_key":"grab"}`: 단어장에서 뺀다(표현 자체는 남는다).
  - `{"type":"VOCAB_MASTER", "term_key":"grab", "mastered":true}`: 외웠어요 표시. 같은 시각이면 나중 것.
  - `{"type":"VOCAB_EDIT", "term_key":"grab", "meaning":"…", "note":"…"}`: 직접 고친 뜻·메모.
  - 단어장 이벤트는 순서에 따라 결과가 달라서(담기 → 빼기) 서버가 처리한 id를 기록하고, 같은 id가 다시 오면 적용하지 않고 `accepted`로 돌려준다.

### 4.5 히트맵

`GET /api/v1/habit/heatmap`

```json
{
  "start_date": "2026-11-13", "current_day": 3, "program_complete": false,
  "current_streak": 2, "longest_streak": 2, "achieved_days": 2, "freezes_left": 2,
  "cells": [ { "day": 1, "date": "2026-11-13", "status": "ACHIEVED" } ]
}
```

`status`: `ACHIEVED` | `FROZEN` | `MISSED` | `TODAY` | `FUTURE`. 오늘 달성했으면 `ACHIEVED`. 정확히 66칸.

### 4.6 Web Push

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/push/vapid-public-key` | `{"public_key":"BP…"}` |
| `POST` | `/push/subscribe` | 브라우저 `PushSubscription.toJSON()` 그대로(`endpoint`, `keys.p256dh`, `keys.auth`). 엔드포인트 기준 업서트. |
| `POST` | `/push/unsubscribe` | `{"endpoint":"…"}` |
| `POST` | `/push/test` | 모든 구독 + 웹훅에 시험 알림. `{"push_sent":1,"webhooks_sent":0}` |

푸시 페이로드: `{"title":"…","body":"…","url":"/?autoplay=true&mode=shadowing","tag":"habit66-reminder"}`

### 4.7 관리자 (선택 토큰: `app.admin-token`이 있으면 `X-Admin-Token` 헤더 필수)

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/admin/lessons/import` | 아래 형식. 응답 `{"lessons":5,"created":50,"updated":0}` |
| `GET` | `/admin/tts/jobs` | 아직 없는 클립 목록 `{"jobs":[{"file":"…mp3","voice":"…","rate":"-10%","text":"…","sentence_ids":[1]}]}` |
| `PUT` | `/admin/audio/{file}` | 본문 `audio/mpeg` 바이트. `{"file":"…","duration_ms":2304,"created":true}`. 이미 있으면 `created:false`(200), 모르는 이름 404, 검증 실패 422 |
| `POST` | `/admin/tts/generate` | 백엔드 edge-tts로 없는 클립 생성 시작 `{"started":true}` |
| `GET` | `/admin/tts/status` | `{"running":false,"queued":0,"done":50,"failed":0,"last_error":null,"clips_total":50,"clips_missing":0}` |
| `POST` | `/admin/tts/sync` | 디스크를 다시 읽어 색인 `{"indexed":50}` |
| `GET` | `/admin/settings` | `{"start_date":"…","timezone":"Asia/Seoul","day_rollover_hour":3}` |
| `PUT` | `/admin/settings/start-date` | `{"start_date":"2026-11-13"}`. 오늘(논리 날짜)보다 미래면 400 |

임포트 형식(시드 파일과 같음):

```json
{
  "lessons": [
    {
      "day": 6, "title": "식당 예약", "theme": "Restaurant",
      "sentences": [
        { "speaker": "A", "en_text": "I'd like to make a reservation.",
          "ko_word_order": "저는 하고 싶어요 / 예약을",
          "ko_natural": "예약하고 싶어요.", "core": true,
          "terms": [
            { "text": "make a reservation", "form": "make a reservation",
              "meaning": "예약하다",
              "nuance": "식당·호텔·병원 자리를 미리 잡을 때 가장 흔한 표현. 구어에서는 book도 많이 쓴다.",
              "examples": [
                { "en": "I'd like to make a reservation for two.", "ko": "두 명 예약하고 싶어요." },
                { "en": "Did you make a reservation for tonight?", "ko": "오늘 저녁 예약했어?" },
                { "en": "You need to make a reservation in advance.", "ko": "미리 예약해야 해요." }
              ] }
          ] }
      ]
    }
  ]
}
```

`terms`(선택): 문장마다 0~3개. `text`는 사전형, `form`은 문장에 실제로 나온 형태(같으면 생략 가능, 대소문자 무시하고 `en_text` 안에 있어야 함). `examples`는 정확히 3개.

검증: `day` 1~66, 문장 1개 이상, `speaker`는 `A`/`B`, 세 텍스트 필드는 비어 있으면 안 된다. 문장 순서가 `seq`(1부터)가 된다. 일차는 건너뛰어도 된다(`lesson_day` 정의 참고).

### 4.8 오디오

`GET /api/v1/audio/{file}.mp3`

### 4.9 단어장

`GET /api/v1/vocab`

```json
{ "entries": [
  { "term_key": "grab", "text": "grab", "meaning": "가볍게 먹다·사다", "nuance": "친구 사이 구어체. 격식 자리에서는 have나 get.",
    "examples": [ { "en": "Let's grab some coffee.", "ko": "커피 한잔하자.",
                    "audio_url": "/api/v1/audio/….mp3", "duration_ms": 1500, "duration_estimated": false } ],
    "status": "READY", "source": "CONTENT", "note": null,
    "mastered": false, "added_at": 1763170000000,
    "sentence": { "id": 43, "day": 5, "en_text": "How about grabbing dinner together?", "ko_natural": "같이 저녁 먹는 거 어때요?" } } ],
  "generator": { "state": "OK", "message": null, "expires_at": null } }
```

- 담긴 순서의 역순(최근 먼저). `status: PENDING`이면 뜻·예문이 비어 있을 수 있다(생성 중). `FAILED`면 직접 입력을 안내한다.
- 예문 음성은 `GET /admin/tts/jobs`에도 나와 스크립트로 채울 수 있다(담긴 표현의 예문만).
- `generator.state`: `OK` | `EXPIRING`(구독 로그인 만료 임박, `expires_at`) | `LOGIN_REQUIRED`(로그인이 끊겨 생성이 멈춤, `message`에 할 일) | `OFF`(`generator: none`). 메모리에만 있는 값이라 서버를 다시 켜면 시작 점검 결과로 다시 정해진다.
- `POST /api/v1/admin/vocab/retry`: `FAILED`·`PENDING` 표현 생성을 다시 시도. `{"queued": 2}`
- `POST /api/v1/admin/vocab/check-login`: 로그인을 지금 점검하고 `generator`와 같은 모양을 돌려준다(몇 초 걸린다).

설정(`application.yml`):

```yaml
app:
  vocab:
    generator: claude-cli        # claude-cli | anthropic-api | none
    claude-command: claude
    claude-model: haiku          # claude -p --model 값
    timeout-seconds: 120
    claude-oauth-token: ""       # claude setup-token 값. 프로젝트 루트 config/application.yml(커밋 안 됨)에 넣는다
    login-check-cron: "0 0 12 * * *"
    login-warn-days: 3
```

---

## 5. 프론트엔드 화면

```
┌──────────── max-w-md, bg #F5F7FB ────────────┐
│ [히어로 카드: Day N/66 · 진행바 · 🔥 · ❄]  [타이머 카드: 모래시계 · 25:00 · 복습 n/m] │
│ [ 오늘의 10문장 · Day N | 누적 복습 · Day a–b ]   (세그먼트 탭)          │
│ ┌ 학습 카드 ───────────────────────────────┐ │
│ │ (A · Andrew)  3/10        [✓ 암기 완료]  │ │
│ │                                          │ │
│ │  What do you do in your free time?       │ │
│ │  무엇을 하나요 / 당신의 자유 시간에?        │ │
│ │  여가 시간에는 무엇을 하시나요?            │ │
│ │                                          │ │
│ │  ▂▅▇▅▂ 파형 / 따라 말해 보세요 2.1s        │ │
│ └──────────────────────────────────────────┘ │
│ [전체|어순힌트|한글만]                 (1.0x) │
│ [연속|끊어읽기|역할극]                  (⚙)  │
│        ( ◀    (● 56px 링)    ▶ )  플로팅 독     │
└──────────────────────────────────────────────┘
```

오버레이: 모닝 보이스 락(전면), 히트맵 바텀시트, 설정 바텀시트(푸시 알림 켜기·시험 발송, 루틴용 URL 복사), 자동 재생 차단 시 "탭해서 재생", 토스트.

---

## 6. PWA

- `manifest.json`: `display: standalone`, `start_url: /`, `theme_color: #2B59FF`, `background_color: #F5F7FB`, 아이콘 192/512(maskable 포함), 모드별 `shortcuts`.
- `sw.js`(직접 작성):
  - 앱 셸: 네비게이션은 네트워크 우선, 실패 시 캐시된 `/index.html`.
  - `/assets/*`, 폰트: 캐시 우선.
  - `/api/v1/audio/*`: 캐시 우선(`audio-v1`, 페이지 프리로더와 같은 캐시).
  - 그 밖의 `/api/*`: 네트워크만.
  - `push` → `showNotification`, `notificationclick` → 기존 창 포커스 또는 `url` 열기.
- Screen Wake Lock: 재생·대기 중이거나 타이머가 돌 때 요청, 화면 복귀 시 다시 요청.
- 실패한 열람 보고는 `localStorage` 큐에 쌓았다가 다음 성공 때 함께 보낸다.

---

## 7. 배포

- 개발: `backend`는 `gradlew bootRun`(작업 디렉터리 = 프로젝트 루트), `frontend`는 `npm run dev`(5173, `/api` → `localhost:8080` 프록시).
- 운영: `build-all.bat` → 프론트 빌드 → `dist/`를 `backend/src/main/resources/static/`로 복사 → `bootJar` → 루트에서 `java -jar`. 8080 한 포트로 SPA + API + 오디오를 모두 서빙한다.
- SPA 라우팅: `/api`가 아닌 확장자 없는 경로는 `index.html`로 보낸다.
- 외부 접속: `tailscale serve --bg 8080` → `https://<pc-name>.<tailnet>.ts.net/`.
