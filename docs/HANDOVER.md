# 인수인계 (2026-11-16 07:0x)

## 1. 오늘 끝낸 것 (모두 커밋, push 안 함)

| 커밋 | 내용 |
|---|---|
| `9fe9ced` | 단어 생성기 **장기 토큰** 설정 + **로그인 끊김 폰 알림** |
| `ea7256b` | `.gitignore`의 `config/` 규칙이 백엔드 `com/habit66/config` 패키지(AppProperties 등 6개 + 테스트 2개)를 숨겨 한 번도 커밋되지 않았던 문제 수정 → `/config/`(루트 전용)로 변경 |
| `f6a29de` | 커밋돼 있던 `scripts/__pycache__` 제거, 무시 규칙 추가 |
| 그 전(어제 밤) | 단어장 백엔드·화면, 콘텐츠 검수, 모닝 락 수정, 폴드8 화면 검사, 운영 반영 — `docs/PROGRESS.md` 참고 |

검증: 백엔드 테스트 113개 통과(실제 claude 실측 1개는 `HABIT66_LIVE_CLAUDE=1`일 때만), `npm run typecheck` 통과, `sloplint --scope app` FAIL 0.

### 장기 토큰·끊김 알림 동작
- `app.vocab.claude-oauth-token`에 값이 있으면 자식 `claude`에 `CLAUDE_CODE_OAUTH_TOKEN`으로 넘긴다. 비어 있으면 PC에 로그인된 구독을 쓴다(지금 PC 로그인 만료 예정: 2026-12-13).
- 서버 시작 때 + 매일 12:00에 짧은 실제 호출로 점검(`claude auth status`는 가짜 토큰도 로그인됨으로 보여서 판단에 안 씀). 401·`authentication_error`면 `LOGIN_REQUIRED` → 웹 푸시·웹훅으로 종류별 하루 한 번 알림. 구독 로그인은 만료 3일 전부터 `EXPIRING` 알림. 다시 성공하면 실패한 단어 자동 재생성.
- API: `GET /api/v1/vocab`에 `generator {state, message, expires_at}`, `POST /api/v1/admin/vocab/check-login`(즉시 점검).
- 화면: 단어장에서 뜻을 기다리는/실패한 단어의 뒷면 문구에 "PC의 Claude 로그인이 끊겨…" 표시.
- 미리보기: `.claude/launch.json`의 `backend-preview-login`(가짜 401 claude `.preview-data/fake_claude_401.py`, gitignore 대상)로 끊김 상태를 재현할 수 있다.

## 2. 진행 중이던 것: 전체 코드 리뷰 (사용자 요청)

요청: "모든 코드를 Fable 5.2와 Codex sol high로 전체 코드리뷰".

| 리뷰어 | 범위 | 결과 위치 |
|---|---|---|
| Codex `gpt-6.1-sol`, `model_reasoning_effort=high` | 백엔드 | `.codex-review/full/out-backend.md` |
| 〃 | 프론트엔드 | `.codex-review/full/out-frontend.md` |
| 〃 | 스크립트 + 백엔드 테스트 | `.codex-review/full/out-scripts-tests.md` |
| Fable 5.2 서브에이전트 | 백엔드 + 스크립트 | 세션으로 돌아오면 `.codex-review/full/fable-backend.md`에 저장 |
| Fable 5.2 서브에이전트 | 프론트엔드 | 세션으로 돌아오면 `.codex-review/full/fable-frontend.md`에 저장 |

- `.codex-review/`는 gitignore 대상. 리뷰 기준·출력 형식: `.codex-review/full/brief.md`, 보낸 묶음: `bundle-*.txt`, 진행 로그: `log-*.txt`(맨 끝에 Codex 답이 붙는다). 묶음에 개인 정보 없음 확인함.
- **out 파일이 없거나 비어 있으면** 그 리뷰가 끝나지 않았거나 실패한 것이다. 다시 돌리는 명령(묶음별):
  ```bash
  cd .codex-review/full && codex exec -s read-only --skip-git-repo-check --ephemeral -m gpt-6.1-sol -c 'model_reasoning_effort="high"' -C "C:/dev/english-66-habit/.codex-review/full" -o out-backend.md - < bundle-backend.txt > log-backend.txt 2>&1
  ```
- **Fable 결과가 세션에 없으면**(세션이 끊긴 경우) Agent 도구 `model: "fable"`로 같은 범위를 다시 맡긴다. 프롬프트 요지: brief.md 기준, 읽기 전용, 수정·서버 실행·오디오 금지, `data/` 열지 않기, `경로:줄`+실패 시나리오+확신도로 보고.

### 리뷰 다음 순서
1. 다섯 결과를 모아 **지적마다 코드를 직접 열어 확인**(리뷰어 확신도를 그대로 믿지 않는다). 겹친 지적·엇갈린 지적을 표로 정리.
2. 실제 결함만 고치고 테스트 추가 → `cd backend && gradlew.bat test`, `cd frontend && npm run typecheck`, sloplint.
3. 화면을 고쳤으면 폴드8 크기(커버 416×613, 펼침 816×572·616×772)에서 확인. **브라우저 검사 때 소리 막기**(`speechSynthesis.speak`·`HTMLMediaElement.play` 덮어쓰기). 애니메이션이 끝난 뒤 캡처(미리보기 창이 숨겨져 있으면 rAF가 멈춰 반투명 겹침이 찍힌다).
4. `build-all.bat norun`으로 운영 jar 다시 빌드(지금 jar의 화면 파일은 이번 프론트 변경 전 것) → 커밋 → `docs/PROGRESS.md` 갱신.

## 3. 사용자가 할 일 (선택)
- **장기 토큰(선택, API 요금 아님)**: 한 달마다 다시 로그인하기 싫으면
  1. PC 터미널에서 `claude setup-token` 실행(구독 계정으로 1년짜리 토큰 발급)
  2. 프로젝트 루트에 `config/application.yml`을 만들고(커밋 안 됨) 아래처럼 넣기. **토큰을 채팅에 붙여 넣지 않는다.**
     ```yaml
     app:
       vocab:
         claude-oauth-token: "<setup-token 출력값>"
     ```
  3. `run.bat`으로 서버 재시작 → `POST /api/v1/admin/vocab/check-login` 결과가 `"state":"OK"`면 성공.
- 토큰을 안 넣으면: 2026-12-13 전에 폰으로 "곧 만료" 알림이 오면 PC에서 `claude` 실행 → `/login`.
- 폰에서 쓰려면 PC에서 `run.bat`(운영 서버는 지금 꺼져 있다).

## 4. 알고 있는 후속 과제
- 기기 음성(speechSynthesis) 대체 재생: 음성 목록이 늦게 로드되면 영어 음성을 지정하지 못해 OS 기본 음성(중국어 등)으로 읽는다. 2026-11-16 새벽 미리보기 테스트 중 노트북 스피커로 실제로 들림. `audio/engine.ts`의 `utterance()` — `voiceschanged`를 기다리거나 en 음성이 없으면 재생을 미루는 식으로 고칠 것(리뷰 결과와 함께 처리).
- 사전형으로 표현 키 합치기(보류: 쌓인 오프라인 이벤트가 옛 키를 가리킴).
- 실제 폴드8 기기 확인, 단어 카드 뒷면 새 문구의 화면 확인(미리보기 창이 숨겨져 못 봄).
- 클라우드 배포(Oracle)는 로그인 문제로 보류, 로컬 PC 운영 유지(사용자 결정 2026-11-16).
- 미추적 파일 `docs/deploy/`, `docs/observed-issues-2026-11-15.md`는 다른 작업자 파일이라 손대지 않았다.

## 5. 작업 규칙 메모
- 커밋: `git -c user.name=habit66 -c user.email=habit66@localhost commit`(git 사용자 정보 미설정, 출처 표기 줄 넣지 않음, push 안 함).
- 운영 `data/`와 8080은 신중히. 미리보기는 8090/5174와 `.preview-data/`.
- JDK: `C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`(PATH에 없음, `JAVA_HOME` 지정).
- bash heredoc은 한글·따옴표를 망가뜨릴 수 있어 큰 치환은 파이썬 스크립트 파일로 한다. 파이썬 출력 리다이렉트는 cp949가 되니 파일은 `open(..., encoding='utf-8')`로 쓴다.
- 운영 DB 백업: `data/backup/habit66-before-vocab-20261116.db`(+`-wal`, `-shm`).
