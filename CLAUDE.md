# CLAUDE.md — 66 Habit English

1인용 영어 회화 암송 + 66일 습관 형성 PWA. 기능·API 계약은 [SPEC.md](SPEC.md)가 기준이다. 코드와 SPEC이 어긋나면 둘 중 하나를 고쳐 맞춘다.

## 아키텍처

```
갤럭시 Z 폴드 (PWA, Chrome WebAPK)
        │ https://<pc>.<tailnet>.ts.net/   (tailscale serve --bg 8080)
        ▼
Windows PC ─ Spring Boot :8080 (단일 포트)
             ├─ /                → React dist (src/main/resources/static)
             ├─ /api/v1/**       → REST
             ├─ /api/v1/audio/** → ./data/audio/*.mp3 (edge-tts 캐시)
             └─ ./data/habit66.db (SQLite)
```

- **단일 포트 원칙**: 프론트엔드는 `/api/v1/...` 상대 경로만 호출한다. 절대 URL·호스트·포트를 코드에 넣지 않는다. 개발 때는 Vite가 `/api`를 `localhost:8080`으로 프록시한다.
- **데이터 디렉터리**: `app.data-dir`(기본 `./data`, 작업 디렉터리 = 프로젝트 루트). DB, 오디오, VAPID 키가 모두 여기 있다. `bootRun`도 루트에서 돈다.
- **TTS**: 파일 이름 규칙(`sha1(voice|rate|text)[:20].mp3`)과 MP3 길이 계산은 Java `tts` 패키지에만 있다. 백엔드는 `python -m edge_tts` CLI로 직접 만들 수 있고, `scripts/generate_tts.py`는 서버에서 작업 목록(`GET /admin/tts/jobs`)을 받아 병렬로 합성한 뒤 업로드(`PUT /admin/audio/{file}`)만 한다. Python에 이름 규칙을 복제하지 않는다.
- **시간 판단은 서버가 한다**: 논리 날짜, Day N, 모닝 락 시간대, 게이트, 스트릭은 모두 서버 계산값을 쓴다. 프론트엔드는 날짜를 직접 계산하지 않는다(타이머 제외). 오프라인 낙관 표시는 `state/overlay.ts`의 집합 연산뿐이다.
- **사실만 저장**: 파생값(달성 여부 외의 스트릭·프리즈·게이트)은 저장하지 않고 매 요청 순수 함수(`progress.HabitRules`)로 계산한다. 학습 쓰기는 `POST /api/v1/events` 하나다. 설계 근거는 [docs/architecture.md](docs/architecture.md).

## 디렉터리

```
backend/                     Spring Boot 3.4 · Java 17+ · Gradle
  src/main/java/com/habit66/
    config/                  AppProperties, Web(SPA·오디오), Admin 토큰 필터
    content/                 Lesson·Sentence 엔티티, 임포트, 시더, 세션 API
    progress/                열람 기록, 대시보드, 스트릭·히트맵, 모닝 락, 집중 시간
    tts/                     edge-tts 호출, MP3 길이 계산, 생성 작업
    notify/                  Web Push(VAPID), 웹훅, 22시 스케줄러
    common/                  시계(LogicalClock), 설정 저장소, 오류 응답
  src/main/resources/
    schema.sql               SQLite 스키마 원본
    seed/lessons-day01-05.json
frontend/                    React 18 · TS · Vite · Tailwind 3 · framer-motion · lucide-react
  public/                    manifest.json, sw.js, icons/
  src/
    api/                     types.ts(와이어, snake_case 그대로), client.ts(fetch, 서버 시계 보정)
    state/                   outbox.ts, overlay.ts(applyPending 순수 함수), store.ts(StudyStore), useStudy.ts
    audio/                   engine.ts(AudioEngine), turn.ts(planTurn), clips.ts(프리로더), silence.ts(무음 WAV), useEngine.ts
    playback/                controller.ts(엔진 ↔ 스토어 접착층, 탭·자동 실행)
    hooks/                   useWakeLock, useFocusTimer, useSpeechMatch, usePush, useCardDwell
    components/              화면 조각 (clean-blue-ui 규칙)
    lib/                     wordMatch, launch, prefs, toast, format
scripts/generate_tts.py      MP3 일괄 생성 + 서버 동기화 (+ --import)
content/                     Day 6 이후 레슨 JSON 예시
build-all.bat / run.bat      Windows 빌드·실행
```

## 명령

| 할 일 | 명령 |
|---|---|
| 전체 빌드 후 실행 | `build-all.bat` |
| 빌드 없이 실행 | `run.bat` |
| 백엔드 개발 | `cd backend && gradlew.bat bootRun` |
| 프론트 개발 | `cd frontend && npm run dev` → http://localhost:5173 |
| 프론트 타입 검사 | `cd frontend && npm run typecheck` |
| 백엔드 테스트 | `cd backend && gradlew.bat test` |
| 오디오 일괄 생성 | `python scripts/generate_tts.py` |
| 레슨 추가 | `python scripts/generate_tts.py --import content/day06.json` |

## 코딩 컨벤션

### 공통
- JSON은 **snake_case**(Jackson `SNAKE_CASE`). 프론트 타입도 snake_case 필드를 그대로 쓴다. 변환 레이어를 만들지 않는다.
- 사용자에게 보이는 문구는 한국어. 코드 식별자·로그는 영어.
- 주석은 "왜"가 코드만으로 안 보일 때만 쓴다.

### 백엔드
- 기능별 패키지(package-by-feature). 컨트롤러는 얇게, 규칙은 서비스에.
- DTO는 `record`. 엔티티를 컨트롤러 밖으로 직접 내보내지 않는다.
- 날짜 컬럼은 `String`(`YYYY-MM-DD`), 시각은 `long` epoch millis. SQLite 방언의 날짜 매핑에 기대지 않는다.
- 스키마는 `schema.sql`이 원본(`ddl-auto: none`). 매 시작마다 실행되므로 모든 문장은 `IF NOT EXISTS`로 쓴다. 기존 DB에 컬럼을 더해야 하면 `ALTER TABLE`을 시작 시 한 번만 도는 코드로 넣는다(SQLite는 `ADD COLUMN IF NOT EXISTS`가 없다).
- "지금"이 필요하면 `LogicalClock`을 주입받는다(`LocalDate.now()` 직접 호출 금지). 테스트에서 시간을 고정하기 위해서다.
- 외부 호출(edge-tts, 푸시, 웹훅)은 실패해도 요청 흐름을 깨지 않는다. 로그를 남기고 상태로 보고한다.

### 프론트엔드
- TypeScript `strict`. `any` 금지(브라우저 비표준 API 타입은 `src/types/web.d.ts`에 선언).
- 오디오 재생 상태 기계는 React 밖 `AudioEngine` 클래스에 두고 `useSyncExternalStore`로 구독한다. 컴포넌트에서 `<audio>`를 직접 만들지 않는다.
- 서버 상태와 미전송 이벤트는 `StudyStore` 한 곳이 소유한다. 학습 기록은 `studyStore.record()`만 쓰고, 화면은 `useStudy()`로 읽는다. 쓰기 응답(대시보드)으로 바로 갱신한다.
- 화면 컴포넌트를 만들거나 고칠 때는 **반드시 `.claude/skills/clean-blue-ui/SKILL.md`를 먼저 읽고** 토큰·레시피를 따른다.

## UI 강제 규칙 (요약 — 상세는 clean-blue-ui 스킬)

- 캔버스 `#F5F7FB`, 카드 `#FFFFFF` `rounded-3xl` + `shadow-[0_8px_30px_rgba(43,89,255,0.06)]`. **1px 검은 테두리 금지.**
- 강조색은 로열 블루 `#2B59FF` 하나. 보조면 `#EEF2FF`. 히어로 그라데이션 `from-[#2B59FF] to-[#6B8BFF]`.
- 금지: 어두운 배경 기본값(플로팅 독 `#111827`만 예외), 형광 노랑·주황, 하단 바 버튼 2줄 이상.
- 레이아웃 `max-w-md mx-auto min-h-dvh px-5 py-6`, 화면의 40% 이상은 여백.
- 영어 원문 `text-[25px] font-bold tracking-tight leading-[1.35] text-[#111827]`, 어순 힌트 `text-[16px] font-semibold text-[#2B59FF]`, 해석 `text-[14px] text-[#64748B]`.
- 전환은 framer-motion 스프링. 터치 하이라이트 제거. 터치 대상 최소 44px.

## 하지 말 것

- 프론트엔드에 `http://localhost:8080` 같은 절대 주소 넣기.
- 오디오 파일 이름 규칙을 Python이나 프론트엔드에 복제하기.
- 파생값(스트릭, 프리즈 사용, 게이트 상태)을 DB에 저장하기.
- `static/` 폴더를 손으로 고치기(빌드 산출물이다. `build-all.bat`가 지우고 다시 채운다).
- `data/` 폴더를 커밋하기.
