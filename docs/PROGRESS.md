# 진행 상황 (2026-11-16 08:3x)

> 이어서 할 일과 진행 중이던 전체 코드 리뷰는 [HANDOVER.md](HANDOVER.md)에 있다.

## 끝난 것
- 기준 문서: `CLAUDE.md`, `SPEC.md`(F-01~F-08), `.claude/skills/clean-blue-ui/SKILL.md`, `docs/architecture.md`, `docs/curriculum.md`.
- 백엔드(`backend/`): F-01~F-08 완성, 테스트 102개 통과(실제 `claude` CLI 실측 테스트 1개는 `HABIT66_LIVE_CLAUDE=1`일 때만 돈다).
- 프론트(`frontend/`): 전체 화면, 오디오 엔진, 오프라인 아웃박스·오버레이, 단어장 화면, 모닝 락 개선. typecheck 통과, `sloplint --scope app` FAIL 0(WARN 1: 바텀시트 배경 블러, 원래 있던 것).
- 콘텐츠: Day 1~66, 660문장·표현 연결 1,231개(고유 1,057개). Codex 교차 검수로 Day 6~66 필드 87개 수정(지적 152건 중 88건 반영, Day 1~5 시드는 변경 없음). `validate_content.py --all` ERROR 0, WARN 0. 검수 기록: 세션 노트 `content-review-2026-11-16.md`.
- 운영 반영(2026-11-16 03:15): `build-all.bat norun` → 운영 DB에 시드 + Day 6~66 임포트, 음성 660문장 전부 준비(`clips_missing 0`). 기존 Day 1~5 문장 id(1~50)와 기록 유지. 임포트 전 DB 백업: `data/backup/habit66-before-vocab-20261116.db`(+`-wal`, `-shm`).

## 이번에 한 일 (2026-11-16)
1. **단어장 백엔드**: `VocabService`(VOCAB_ADD·REMOVE·MASTER·EDIT, 처리한 이벤트 id를 `vocab_event`에 기록), `TermGenerationQueue`(단일 스레드, 하루 3회, 로그인 만료 시 대기열 중단, 생성 중 임포트가 이김), 생성기 3종(`claude-cli` 기본, `anthropic-api` = `claude-haiku-5-5`, `none`), 담긴 표현 예문 음성(1·3번 Ava, 2번 Andrew), `GET /api/v1/vocab`, `POST /api/v1/admin/vocab/retry`, 세션 문장 `terms`.
   - `ClaudeCliGenerator` 실측 조건: 빈 임시 폴더, `--setting-sources project`, 자식 환경의 `CLAUDE*`·`ANTHROPIC*` 제거, **Windows에서는 인자를 직접 인용**(ProcessBuilder가 큰따옴표를 이스케이프하지 않아 `--json-schema`가 깨졌다), 안전장치 거절은 그 표현만 FAILED.
   - Codex 리뷰 8건: 5건 수정, 2건은 SPEC 정정(메모만 준 담기는 생성함, 없는 문장을 가리킨 담기는 받고 연결만 버림), 1건 보류(아래 후속 과제).
2. **단어장 화면**: 담기 시트(미리 만든 표현, 단어 칩, 직접 입력), 세 번째 탭 `?view=vocab`, 필터·카드 넘기기·뒤집기·예문 듣기·외웠어요·빼기(되돌리기)·뜻/메모 고치기, PENDING·FAILED·빈 상태.
3. **모닝 락**(`docs/observed-issues-2026-11-15.md` 반영): 순서 반영 일치율(LCS), 받아 적기(영어 가림, 마이크 불가 시 자동), 긴급 건너뛰기는 마이크 불가이거나 한 문장 3번 실패 뒤에만 + 확인, 펼친 화면 두 열. **아침 첫 실행 때 전날 캐시로 판단해 락이 안 뜨던 버그** 수정.
   - 범위 밖: 폰의 다른 앱 제한(PWA로 불가), 발음·억양 평가(브라우저 인식 한계).
4. **폴드8 화면 검사**: 사양 확인(커버 5.5" 1248×1972, 메인 7.6" 2448×1848, DPR 3) → 실제 PWA 크기 커버 416×613, 펼친 가로 816×572, 세로 616×772로 홈·복습·단어장·담기 시트·모닝 락 측정(가로 넘침, 독 겹침, 화면 밖 잘림, 44px). 세로 펼침(600~699px)에서 타이머 라벨 줄바꿈과 문장 지도 칸(42px)을 고쳤다. UI 규칙 문서의 확인 크기를 이 값으로 바꿨다.
5. **배치 파일**: `build-all.bat`·`run.bat`이 LF 줄바꿈이라 cmd에서 깨졌다 → CRLF 고정(`.gitattributes`), `call .\gradlew.bat`.
6. **Codex 최종 리뷰(프론트)** 7건: 6건 수정 — 화면 복귀 때 모닝 락 판단(밤새 백그라운드였던 앱), 락의 포커스 가두기·뒤 화면 inert, 음성 인식기의 늦은 콜백 무시, 단어장 재조회 실패 시 보낸 VOCAB_* 유지, 단어장 폴링의 늦은 응답 버리기, 뺐다 다시 담은 표현 표시. 1건(좁은 화면 타이머 라벨의 접근성)은 버튼 `aria-label`에 전체 문구가 있어 해당 없음.
7. 운영 jar(`backend/build/libs/habit66.jar`)는 위 수정까지 포함해 다시 빌드했다.

## 남은 것 / 후속 과제
- 생성기가 돌려준 사전형(`grabbing` → `grab`)으로 표현 키를 합치기: 키를 바꾸면 쌓여 있는 오프라인 이벤트가 옛 키를 가리켜 보류.
- 실제 폴드8 기기 확인(지금은 브라우저 크기 흉내).
- 운영 서버는 원래 꺼져 있었다. 확인용으로 띄웠다가 다시 껐다 → 쓰려면 `run.bat`(또는 `build-all.bat`)으로 켠다.

## 사용자 결정 (2026-11-16)
- 장기 토큰 + 로그인 끊김 폰 알림: **구현함**(`9fe9ced`). 토큰 넣기는 선택(HANDOVER.md 3장).
- 클라우드 배포(Oracle Always Free): **보류**, 로컬 PC 운영 유지. 검토 결과는 `docs/deploy/oracle-always-free-review.md`.

## 전체 코드 리뷰 (2026-11-16, 끝)
- Fable 5.2 + Codex gpt-6.1-sol high 로 전체 리뷰 → 조율표 → 교차 검토 → 최종 확인을 네 차례 반복해 반영(`9b61dff`, `afc053e`, `e0a0287`, `8403d24`, `bff6c8c`). 기록: [review/code-review-2026-11-16.md](review/code-review-2026-11-16.md).
- 주요 변경: 서버 `127.0.0.1` 바인딩, 이벤트 id 원장(`applied_event`), 기기 음성 영어 음성 확인·실패 시 건너뛰기, 서비스 워커 오프라인·갱신 안정화, 아웃박스 창 사이 합치기·거절 기록 보관, 생성 대기열 재시도 보존.
- 백엔드 테스트 114개, typecheck, sloplint FAIL 0, 운영 jar 재빌드 완료.

## 미리보기 실행법
`.claude/launch.json`: `backend-preview`(8090, `.preview-data/`), `backend-preview-morning`(8090, 빈 `.preview-data/morning`, 모닝 락 시간 창 00:00~23:59, 생성기 끔), `frontend-preview`(5174). 미리보기 jar는 `.preview-data/habit66-preview.jar`(새 빌드 뒤 복사). 실제 `data/`와 8080은 건드리지 않는다.

## 참고
- Codex: 읽기 전용 샌드박스가 셸을 못 띄우므로 파일 전문을 stdin에 첨부. 작업 폴더는 계정 이름이 없는 `.codex-review/`(gitignore)를 `-C`로 지정한다.
- git 사용자 정보가 설정돼 있지 않아 커밋은 `git -c user.name=habit66 -c user.email=habit66@localhost commit`으로 한다.
- 디자인 시안·스크린샷: `docs/design/mockups/`, `docs/design/applied/`.
