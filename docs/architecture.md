# 아키텍처 — 합성 설계 (architect · arena)

이 문서는 구현의 계약이다. 기능 계약은 [SPEC.md](../SPEC.md), 컨벤션은 [CLAUDE.md](../CLAUDE.md). 구현 중 이 문서와 다르게 해야 할 이유가 생기면 조용히 바꾸지 말고 문서를 먼저 고친다.

## 문제

같은 "하루 상태"(Day N, 어제 복습 게이트, 오늘 달성, 스트릭·프리즈, 모닝 락)를 화면, 자동 재생, 22시 리마인더가 모두 읽는다. 그런데 기록은 지하철에서 늦게 도착하고, 콘텐츠·시작일·오디오는 나중에 바뀐다. 규칙이 두 곳에 있거나 파생값을 저장하면 서로 어긋난다. TTS 파일 이름 규칙도 Java·Python 두 곳에 두면 어긋난다.

## 사용 예 (호출자 관점)

```ts
// main.tsx
const launch = readLaunch(location, history);          // ?autoplay=true&mode=shadowing → URL 에서 지우고 메모리에 유지
studyStore.start();                                     // localStorage 스냅샷으로 즉시 그리고 서버 새로고침
const playback = createPlayback(studyStore, audioEngine, launch);

// StudyCard
const study = useStudy();          // { kind: 'ready', view, sync } — view 는 서버 스냅샷 + 대기 이벤트 오버레이
const eng = useEngine();           // { cursor, item, phase, hold, hideEn, ... }
onSwipe(d)  => audioEngine.go(d > 0 ? 'next' : 'prev');   // 독 버튼·잠금화면·버즈도 같은 go()
onMark(st)  => studyStore.record({ type: 'MARK', sentence_id, status: st });
useCardDwell(item, session);       // 1.2초 보면 record({ type: 'VIEW', via: 'CARD', ... })
```

```java
// 쓰기는 하나, 응답은 늘 최신 대시보드
EventsResponse events(EventsRequest req) {
    ApplyResult r = eventService.apply(req.parse(), clock.now());
    return new EventsResponse(r.accepted(), r.rejected(), views.dashboard(dayState.snapshot()));
}
// 22시 리마인더도 같은 스냅샷 하나만 본다
if (dayState.snapshot().streak().achievedToday() || !reminderLog.claim(today)) return;
```

```sh
python scripts/generate_tts.py                          # GET /admin/tts/jobs → edge-tts 병렬 → PUT /admin/audio/{file}
python scripts/generate_tts.py --import content/day06.json
```

## 모양

### 데이터: 사실만 저장한다. 테이블마다 쓰기 소유자는 하나

| 테이블 | 쓰기 소유자 | 내용 |
|---|---|---|
| `lesson`, `sentence` | `content.CatalogService` | 콘텐츠. `sentence`에 voice·status·audio 컬럼 없음 |
| `sentence_view` | `progress.EventService` | `UNIQUE(log_date, sentence_id, session, via)` + `INSERT OR IGNORE` = 멱등 |
| `sentence_mark` | `progress.EventService` | 암기 상태. `changed_at` 최신값 우선(LWW). 임포트가 덮어쓰지 못하게 콘텐츠와 분리 |
| `focus_session` | `progress.EventService` | `started_at` PK, 재전송 시 큰 값 유지 |
| `morning_lock` | `progress.EventService` | `log_date` PK, 첫 결정 유지 |
| `day_achievement` | `progress.EventService` | **그날 완주한 순간 한 번 기록되는 사실**. `log_date` PK, `lesson_day`, `via`. 이후 콘텐츠가 바뀌어도 과거 달성이 뒤집히지 않는다 |
| `reminder_log` | `notify.ReminderService` | 하루 한 번 발송 선점 |
| `push_subscription` | `notify` | 구독 |
| `app_setting` | `common.SettingsStore` | `start_date` |
| `data/audio/*.mp3` | `tts.AudioLibrary` | **디스크가 진실**. 길이는 시작 때 재색인해 메모리에 둔다 |

저장하지 않는 것: Day 번호, 게이트, 스트릭, 프리즈 사용, 오늘 진행 수. 모두 매 요청 순수 함수로 계산한다(사실 수천 행, 수 ms).

### 하루 상태: `DayStateService.snapshot()` 하나

`DayStateService`가 사실을 읽어 package-private 순수 함수 `HabitRules`에 넘기고 `DaySnapshot`을 받는다. 대시보드, 세션 2개, 히트맵, `/events` 응답, 리마인더가 모두 이 값의 투영(`ViewAssembler`)이다.

- `lessonDay` = 등록된 일차 중 `currentDay` 이하의 최댓값(일차가 건너뛰어져도 동작). 없으면 0 → 빈 상태.
- 게이트: `lessonDay`보다 작은 등록 일차 중 가장 큰 것이 "어제 레슨". 없으면 게이트 없음. `unlock_ids` = 그 레슨 문장 id.
- 오늘 달성 = `day_achievement`에 오늘 행이 있음. 과거 날도 같은 표로 판정한다.
- 달성 기록 규칙(`HabitRules.completes`): VIEW(TODAY) 수용 직후, 그 문장이 속한 레슨의 모든 문장이 같은 날 TODAY·같은 via로 열람됐으면 `day_achievement`를 `INSERT OR IGNORE`. 빈 레슨은 완주로 치지 않는다.
- 스트릭: 시작일~어제를 순서대로 걷는다. 30일 구간마다 프리즈 2개, 스트릭이 0이면 프리즈를 쓰지 않는다. 결정적이라 저장하지 않는다.
- 응답에 `valid_from`·`valid_until`(오늘 논리 날짜의 시작·끝 epoch ms)을 싣는다.

### 쓰기: `POST /api/v1/events` 하나

클라이언트 이벤트는 `{ id, type, occurred_at, ... }`. `id`는 클라이언트 UUID(타이머는 `focus:<started_at>`).

- **시각 보정**: 클라이언트는 모든 응답의 `server_time`으로 `offset = server_time − Date.now()`(왕복 시간의 절반 보정)를 갱신하고, 이벤트의 `occurred_at`을 서버 시계 추정값(`Date.now() + offset`)으로 찍는다. 서버는 `min(occurred_at, received_at)`으로 미래만 자른 뒤 논리 날짜로 바꾼다. 늦게 도착한 어제 기록은 어제 날짜에 들어간다.
- **세션 귀속**: TODAY 열람인데 그 문장이 그 날짜의 오늘 레슨(그 날짜 기준 `lessonDay`)에 속하지 않으면 서버가 REVIEW로 바꿔 기록한다. 롤오버 뒤에도 열어 둔 어제 큐는 오늘 달성이 아니라 복습으로 남는다.
- **수용**: 형식 오류·없는 문장은 그 이벤트만 `rejected`로 돌려주고, 나머지는 한 트랜잭션으로 수용한다. 같은 id로 다시 보내도 결과가 같다(각 표의 자연 키가 멱등을 보장한다).
- 응답: `{ accepted: [id], rejected: [{id, reason}], dashboard }`.

### 프론트 상태: `StudyStore` + 순수 오버레이

- 아웃박스: `localStorage`. `record()`는 **아웃박스에 먼저 동기 저장**하고 화면을 갱신한 뒤 전송을 예약한다. 압축하거나 버리지 않는다.
- 전송: 온라인 복귀, 화면 복귀, 1.5초 디바운스, 그리고 `pagehide`/화면 숨김 때 `fetch(keepalive)`. 한 번에 하나만 보낸다. 응답의 `accepted ∪ rejected` id만 아웃박스에서 지우고, **같은 단계에서** 스냅샷을 교체한다(전송 중 새로 쌓인 것은 남는다).
- 순서 보장: 요청마다 증가하는 순번을 붙이고, 이미 더 새 응답을 반영했다면 늦게 온 응답은 버린다.
- 스냅샷 캐시: 마지막 대시보드 + 세션 2개를 `localStorage`에 저장해 오프라인 재시작에도 바로 그린다.
- 오버레이 `applyPending(snapshot, pending, now)`(순수 함수, 프론트의 유일한 학습 규칙). **모든 규칙이 멱등**이라, 서버가 이미 반영한 이벤트가 아웃박스에 남아 있어도 두 번 세지 않는다.
  - `valid_from ≤ occurred_at < valid_until`인 대기 이벤트만 적용한다(MARK는 날짜와 무관).
  - 게이트: `unlock_ids − (seen_review_ids ∪ 대기 VIEW·REVIEW)`가 비면 열림(집합).
  - 오늘 진행: `audio_seen_ids`/`card_seen_ids` ∪ 대기 VIEW·TODAY(집합). 한쪽이 오늘 문장 전부를 덮으면 달성 표시, 스트릭은 `if_achieved_today`.
  - 암기 상태: 대기 MARK의 `occurred_at`이 서버 `changed_at`보다 새로울 때만 덮는다.
  - 집중 시간: 대기 FOCUS 중 `started_at`이 서버의 `focus.started_ats`에 없는 것만 더한다.
  - 모닝 락: 대기 VIEW·MORNING_LOCK이 있으면 숨김.
  - `now ≥ valid_until`이면 `stale` 표시 + 새로고침. 날짜·Day·스트릭 걷기는 하지 않는다.
- 서비스 워커는 아웃박스를 모른다(Background Sync 없음). 그래서 sw와 앱이 공유하는 상수는 `audio-v1` 하나뿐이다.

### 오디오: `AudioEngine`(브라우저 어려움) + `planTurn`(모드 규칙)

- `planTurn(item, mode): Turn` 순수 함수가 3모드 규칙 전부를 가진다.

| 모드 | speak | hide_en | hold_ms | gap_ms | heard 보고 시점 |
|---|---|---|---|---|---|
| full | 예 | 아니오 | 0 | 600 | 발화 끝 |
| shadowing | 예 | 아니오 | `duration_ms × 1.3` | 0 | 발화 끝 |
| roleplay A | 예 | 아니오 | 0 | 600 | 발화 끝 |
| roleplay B | 아니오 | 예 | `duration_ms × 1.3` | 0 | 대기 끝 |

- **대기 시계는 미디어 시계**: hold·gap은 그 길이의 무음 WAV를 같은 `<audio>`에서 재생하고 `ended`로 끝낸다. 화면이 꺼져도 타이머 스로틀을 받지 않고, 일시정지하면 남은 시간이 보존되며, 링 진행률은 `currentTime/duration`에서 읽는다(`holdProgress()`, 컴포넌트가 rAF).
- **실행 토큰**: `go`/`setMode`/`load`(다른 key)/`pause`/`dispose` 때 토큰을 바꾸고, 모든 비동기 완료(클립 Blob, `play()` 결과, 합성 콜백, `ended`)는 토큰이 바뀌었으면 조용히 그만둔다.
- **턴 상태 보존**: 턴은 `step: 'speak' | 'hold' | 'gap'`과 `heardReported`를 가진다. 일시정지·`blocked`에서 재개하면 같은 단계부터 이어 간다(speak는 문장 처음부터, hold·gap은 무음 트랙의 `currentTime`부터). 이미 보고한 열람은 다시 보고하지 않는다.
- **재진입 금지**: 엔진 이벤트(`heard`, `queueEnd`)는 엔진이 자기 상태 전이를 끝낸 뒤 `queueMicrotask`로 보낸다. 같은 key로 `load`하면 항목·`playable`만 바꾸고 재생 흐름은 건드리지 않는다. 이어 재생은 엔진이 완전히 멈춘 `queueEnd`에서만 컨트롤러가 시작한다.
- 커서 이동 입구는 `go()` 하나. 카드와 오디오가 같은 커서를 쓴다.
- 엔진은 게이트를 모른다. 큐마다 `playable` 비트만 받는다(잠긴 TODAY는 카드 넘기기는 되고 재생은 안 됨).
- 프리로드: `clips.prefetch()`가 Cache API `audio-v1`(sw와 같은 캐시) → Blob URL. 상태 `{ready, total}`를 공개하고 화면에 "오프라인 준비 10/10"을 작게 보인다. 재생 때 클립이 없으면 네트워크를 한 번 시도하고, 실패하면 기기 `speechSynthesis`로 읽는다(그동안 무음 루프로 Media Session 유지).
- `duration_ms`는 서버가 늘 채운다. 클립이 없으면 단어 수 × 380ms 추정과 `duration_estimated: true`.
- `NotAllowedError` → `phase: 'blocked'` → "탭해서 재생".
- `playback/controller.ts`가 엔진과 스토어를 잇는 유일한 접착층: `heard` → `record(VIEW·AUDIO)`, `queueEnd` + 자동 실행 + 게이트 열림(오버레이) → 오늘 큐를 load 하고 이어 재생.
- **자동 실행 의도**는 컨트롤러가 실제로 재생을 시작할 때까지 보관한다. 캐시 스냅샷만 있으면 서버 새로고침을 최대 2.5초 기다리고, 그래도 없으면(오프라인) 캐시로 시작한다.
- **검증 범위**: 화면 꺼짐·블루투스 버튼·오디오 포커스 변화에서의 동작은 실제 갤럭시 설치형 PWA에서만 확인할 수 있다. README의 수동 검증 목록에 둔다.

### TTS: 규칙은 Java 한 곳

`tts.Utterance(voice, rate, text).fileName()` = `sha1("{voice}|{rate}|{text}")` 앞 20자 + `.mp3`, `Mp3Frames.durationMs()`는 Java에만 있다. Python은 `GET /admin/tts/jobs`로 `{file, voice, rate, text}`를 받아 합성하고 `PUT /admin/audio/{file}`로 올린다. 업로드·백엔드 생성·재색인 모두 `AudioLibrary.accept()` 하나로 들어와 검증 → 임시 파일 → 원자적 이동 → 길이 색인을 거친다. 텍스트나 화자가 바뀌면 이름이 바뀌어 자동으로 "없는 클립"이 된다.

- 검증: MP3 프레임이 파일 바이트의 90% 이상을 덮고 길이가 200ms 이상이어야 받는다.
- 멱등: 이미 유효한 같은 이름의 파일이 있으면 덮어쓰지 않고 성공(200)을 돌려준다. 현재 카탈로그가 모르는 이름은 404. 동시에 둘이 저장하면 먼저 이동한 쪽이 확정되고 나머지는 성공으로 끝난다.
- 백엔드 생성(`TtsService`)은 단일 스레드이고, 작업 중 새로 생긴 작업은 끝난 뒤 한 번 더 스캔한다.

### 리마인더

`ReminderService`: `@Scheduled` 22:00과 22:30, 그리고 서버 시작 시 22:00~03:00 사이면 보충 실행. **서버에 도착한 기록 기준**으로 `snapshot().streak().achievedToday()`이거나, 오늘 레슨이 없거나(`todayLesson() == null`), 프로그램이 끝났으면(`programComplete()`) 보내지 않는다. 마지막 조건이 없으면 66일 뒤에도 Day 66을 매일 다시 하라는 알림이 끝없이 간다. `reminder_log(log_date PK, status SENDING|SENT|FAILED, attempts)`:
- 보내기 전 `SENDING`으로 선점(이미 `SENT`면 중단). 한 채널이라도 성공하면 `SENT`, 모두 실패하면 `FAILED`로 두어 다음 실행(22:30, 재시작 보충)이 다시 시도한다. 시도는 하루 3회까지.
- 채널은 `ReminderChannel` 구현 3개(WebPush: 404/410이면 구독 삭제, Telegram, Discord). 실패해도 예외를 던지지 않는다.
- PC가 22시 전후에 꺼져 있으면 알림은 없다(1대 서버의 한계, README에 명시).

### 모닝 락

일치율 판정은 클라이언트(`lib/wordMatch.ts`). 서버에는 결과만 `MORNING_LOCK` 이벤트로 보낸다(1인 앱이라 클라이언트의 CLEARED 주장을 막을 이유가 약하고, 오프라인에서도 동작해야 한다).
- 서버는 `morning_lock: { window_start, window_end, state, sentences }`를 내려 준다(오늘의 06:00·09:30 epoch ms, 오늘 결정 상태).
- 표시 판단(클라이언트, 앱을 열 때와 화면 복귀 때): `window_start ≤ now < window_end` ∧ `state = NONE` ∧ 오늘 열람 없음(서버 + 대기) ∧ 자동 실행으로 열지 않음.
- 자동 실행(`?autoplay`)으로 연 동안은 메모리의 `launch`가 오버레이를 억제한다.

### 단어장 (F-08)

- **소유**: 새 패키지 `vocab`이 `term`·`vocab_entry`를 쓴다. `sentence_term`은 콘텐츠 임포트(`content.CatalogService`)가 쓰되, 표현 업서트는 `vocab.TermStore.upsertFromContent()` 한 입구로 한다. 같은 `term_key`면 `source: MANUAL` 값은 덮지 않고, `CONTENT`·`GENERATED`는 최신 임포트가 덮는다.
- **쓰기**: `VOCAB_ADD`·`VOCAB_REMOVE`·`VOCAB_MASTER`·`VOCAB_EDIT`는 `/events`로 들어와 `EventService`가 `vocab.VocabService`에 넘긴다(같은 트랜잭션, 멱등). 오프라인 담기는 기존 아웃박스가 그대로 처리한다.
- **생성기**: `vocab.TermGenerator` 인터페이스 하나와 구현 3개(`ClaudeCliGenerator`, `AnthropicApiGenerator`, `NoopGenerator`), `app.vocab.generator`로 하나를 고른다. 단일 스레드 큐, 실패하면 `FAILED`와 `attempts`, 시작 시와 `/admin/vocab/retry`로 재시도(하루 3회 상한).
  - `ClaudeCliGenerator`: `claude -p --setting-sources project --no-session-persistence --model <claude-model> --tools "" --output-format json --json-schema <스키마> "<프롬프트>"`를 빈 임시 폴더에서 실행해 결과 JSON의 `structured_output`을 읽는다. `--bare`는 구독 로그인(OAuth)을 막으므로 쓰지 않는다. 사용자 전역 훅은 `--setting-sources project`로 빠진다. 로그인 만료(`is_error: true`, "OAuth session expired")는 `FAILED`로 두고 로그에 "터미널에서 `claude` 로그인 필요"를 남긴 뒤, 그때 큐에 있던 표현은 시도하지 않는다(이후 담기·재시도는 다시 시도). 안전장치 거절(오탐 포함)은 그 표현만 `FAILED`.
  - 실행 조건(2026-11-16 실측): 자식 환경에서 `CLAUDE*`·`ANTHROPIC*` 변수를 지운다(Claude Code 안에서 띄운 서버가 그 세션 변수를 물려주면 인증이 실패한다). Windows에서는 `ProcessBuilder`가 인자 속 큰따옴표를 이스케이프하지 않아 `--json-schema`가 깨지므로 C 런타임 규칙으로 직접 감싼다.
  - `AnthropicApiGenerator`: `claude-haiku-5-5`, 레코드에서 만든 구조화 출력, `stop_reason: refusal`은 `FAILED`.
  - 장기 토큰: `app.vocab.claude-oauth-token`이 있으면 위에서 지운 뒤 `CLAUDE_CODE_OAUTH_TOKEN`만 다시 넣는다. 토큰은 `AppProperties.Vocab.toString()`에서 가린다.
  - 로그인 상태(`vocab.GeneratorHealth`): 상태는 메모리(파생값), 보낸 알림만 `app_setting.vocab_login_alert`(`종류|논리 날짜`)에 남겨 하루 한 번으로 묶는다. 입력은 셋이다. 큐의 생성 결과(사용자 조치가 필요한 실패 → `LOGIN_REQUIRED`, 성공 → 복구), 시작 시 점검, `@Scheduled` 점검(`LoginProbe.checkLogin()`: `claude auth status --json`으로 구독 로그인 만료 예정만 읽고, 판단은 `-p` 짧은 호출의 401·`authentication_error`로 한다. 가짜 토큰도 auth status에서는 로그인됨으로 나온다). 네트워크·시간 초과처럼 판단할 수 없는 실패는 상태를 바꾸지 않는다. 복구되면 `Recovered` 이벤트로 큐가 `retryAll()`. 알림은 `ReminderService.deliver()`(웹 푸시·텔레그램·디스코드)로 보내고 `tag: habit66-vocab-login`, `url: /?view=vocab`.
  - 큐는 로그인 실패 알림을 보낸 뒤에 표현을 `FAILED`로 바꾼다. `FAILED`가 보이자마자 들어온 재시도가 아직 끝나지 않은 작업 때문에 무시되지 않게 하려는 순서다.
- **예문 음성**: 담긴 표현의 예문만 `TtsService` 작업에 들어간다(이름 규칙은 같은 `Utterance`). 담기 직후 백엔드가 생성을 시작하고, `GET /admin/tts/jobs`에도 나와 스크립트로 채울 수 있다.
- **프론트**: `GET /api/v1/vocab`을 `StudyStore`가 스냅샷과 함께 캐시한다. 대기 중인 `VOCAB_*` 이벤트는 오버레이(`applyPending`)가 단어장 목록에 반영한다(담기 → 즉시 목록에 `PENDING`으로, 빼기 → 즉시 숨김, 외움·수정 → 최신값). 단어장 탭은 엔진 큐와 무관한 별도 화면이고, 예문 듣기는 `audioEngine.audition()`을 쓴다.

### 프로그램 범위

- `current_day = clamp(시작일부터 지난 날 + 1, 1, 66)`. 시작일 + 66일 이후에는 `program_complete = true`, Day 66 콘텐츠를 계속 보여 준다.
- 스트릭 순회는 `시작일 ~ min(어제, 시작일 + 65)`. 프리즈 구간은 Day 1-30, 31-60, 61-66.
- 관리자가 시작일을 바꾸면 Day 귀속과 히트맵이 새 시작일 기준으로 다시 계산된다(사실은 날짜로 저장되므로 기록은 잃지 않는다). 의도한 동작이다.
- 콘텐츠가 모자라면 복습 큐도 `lessonDay` 기준이다(등록되지 않은 일차를 복습할 수 없으므로).

## 모듈 맵

```
backend/src/main/java/com/habit66/
  common/   LogicalClock(dateOf, today, bounds), SettingsStore, ApiException, GlobalExceptionHandler
  config/   AppProperties, WebConfig(오디오·assets·SPA), AdminTokenFilter, DataDirEnvironmentPostProcessor
  content/  Lesson, Sentence(엔티티), Catalog(불변 읽기 모델), CatalogService(current, importLessons), LessonImport(검증), ContentSeeder
  tts/      Utterance, Mp3Frames, AudioLibrary(find, accept, rescan), TtsService(jobs, generate, status), AdminTtsController
  progress/ ClientEvent(sealed), EventService(apply), HabitRules(pkg-private 순수), DayStateService(snapshot), DaySnapshot,
            ViewAssembler, ProgressController(GET dashboard·sessions·heatmap, POST events), AdminController
  notify/   VapidKeys, ReminderChannel + WebPushChannel/TelegramChannel/DiscordChannel, ReminderService, PushController

frontend/src/
  api/       types.ts(와이어, snake_case), client.ts
  state/     outbox.ts, overlay.ts(applyPending), store.ts(StudyStore), useStudy.ts
  audio/     engine.ts(AudioEngine), turn.ts(planTurn), clips.ts, silence.ts, useEngine.ts
  playback/  controller.ts
  hooks/     useWakeLock, useFocusTimer, useSpeechMatch, usePush, useCardDwell
  lib/       wordMatch.ts, launch.ts, format.ts
  components/ 화면(clean-blue-ui)
scripts/generate_tts.py   jobs → 합성 → 업로드 (이름·길이 규칙 없음)
```

## 합성 결정

- **방법**: architect 스킬, arena 러너 2개(Opus xhigh = 후보 1 "서버가 규칙 소유", 부모 모델 = 후보 2 "이벤트 로그 + 오프라인 우선"). 교차 심사는 다른 계열인 Codex(파일 전문 첨부, 읽기 전용).
- **채점**: Codex 후보 1 21/30, 후보 2 20/30. 오케스트레이터 후보 2 우세(표면 크기·모닝 락). 근소한 차이에 의견이 갈려, 기준별로 강한 쪽을 바탕으로 영역을 나눠 합성했다.
- **수렴(두 후보 공통, 그대로 채택)**: 파생값 비저장 + 순수 함수 재계산, 쓰기 단일 경로 + 멱등 키, TTS 규칙 Java 단일 + Python 업로드 워커, 리마인더 선점.
- **후보 1에서**: 사실 테이블 분리와 날짜 포함 UNIQUE(멱등을 DB가 보장), `sentence_mark` 분리, 엔진 실행 토큰, 미디어 시계 대기, `go()` 단일 입구, `PlaybackController` 접착층, 리마인더 시작 시 보충, 디스크가 진실인 `AudioLibrary.accept()`.
- **후보 2에서**: 3모드 규칙을 클라이언트 순수 함수 `planTurn`에 두기(서버 cue 계약 제거), `heard` 보고 시점 명시, 이벤트 id + 이벤트별 `rejected`, 스냅샷 캐시와 `valid_until`, 오프라인 달성·스트릭 낙관 표시(`if_achieved_today`).
- **Codex가 짚은 공통 누락 → 추가**: 과거 달성 불변 기록(`day_achievement`), 오버레이의 날짜 하한·상한(`valid_from`), 아웃박스 무압축·무폐기, 프리로드 상태 공개, 빈 레슨·건너뛴 일차 정의, 불필요한 API 변경 되돌리기(읽기 API·푸시 API·`/admin/tts/sync` 이름 유지).
- **버림**: 서버 모닝 락 판정(오프라인 불가, 왕복 지연), 서버 cue 계산, `autoplay_plan`·`content_key` 계약, 단일 이벤트 로그 표(날짜 UNIQUE를 DB가 못 지킴), IndexedDB + Background Sync(sw와 상수 공유, 1인 앱에 과함).
- **Codex 적대적 리뷰(후보 2 대상, 판정 "재설계 필요") 중 합성안에도 해당한 지적 → 반영**: 폰 시계 날짜 권위(서버 오프셋 보정 + TODAY→REVIEW 재분류), 응답·오버레이 중복 적용(오버레이 전 규칙 멱등화 + 응답 순번), 콜백 재진입(마이크로태스크 이벤트 + `queueEnd`에서만 이어 재생), 중단 단계 보존, 자동 실행 의도 보관, 모닝 락 창 오프라인 판단, 66일 이후·시작일 변경 정책, 리마인더 실패 재시도, TTS 업로드 멱등·MP3 검증. 이미 합성안이 피한 지적: sw 아웃박스, 저장 전 화면 반영, 날짜 하한, 과거 달성 소급 변경, 비동기 취소.

## 받아들인 트레이드오프

- 매 요청 스트릭을 다시 걷는 비용을 받아들이고 동기화 코드를 없앤다.
- 22시에 폰에 미전송 기록이 남아 있으면 리마인더가 잘못 갈 수 있다(Background Sync를 버린 대가). 알림을 눌러 앱을 열면 즉시 전송된다.
- `day_achievement`는 "완주 순간"의 사실이라, 나중에 레슨 문장을 고쳐도 과거 달성은 그대로다(의도).
- 클라이언트 시계를 믿되 미래 시각만 자른다.
- Python 스크립트는 서버가 떠 있어야 돈다(규칙 중복 0의 대가).

## 검토한 대안

- SPEC 원안(파생값 `daily_log` 저장 + 쓰기 4개): 쓰기마다 `daily_log` 갱신(분산 소유), 오프라인 게이트를 위해 프론트가 규칙을 복제해야 한다.
- 단일 append-only 이벤트 로그(후보 2): 표면은 가장 작지만 날짜 UNIQUE를 DB가 못 지켜 중복 억제를 fold에 맡긴다.
- 서버가 재생 cue를 계산(후보 1): 엔진에 모드 분기가 없지만 응답이 커지고 모드 추가 시 세 곳을 고쳐야 한다.

## 열린 질문

- 시작일 + 66일 이후: `current_day = 66` 고정, 히트맵 완료 화면. (현재 구현 기준)

## 다음 구현 단계

`HabitRules`와 JUnit 사실 고정 테스트(스트릭·구간 프리즈·게이트·Day 1 미리보기·03시 롤오버·건너뛴 일차·완주 기록 불변)부터 쓰고, 그 위에 `DayStateService`와 `GET /dashboard`를 올린다.
