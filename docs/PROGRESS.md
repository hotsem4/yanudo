# 진행 상황 (2026-11-15 22:5x 중단 시점)

사용량 한도로 작업을 멈췄다. 2026-11-16 01:10 이후 이 문서부터 읽고 이어간다.

## 끝난 것
- 기준 문서: `CLAUDE.md`, `SPEC.md`(F-01~F-08), `.claude/skills/clean-blue-ui/SKILL.md`, `docs/architecture.md`(architect·arena + Codex 교차 심사로 합성), `docs/curriculum.md`(66일 4단계).
- 백엔드(`backend/`): F-01~F-07 완성, 테스트 79개 통과(단어장 이전 기준). 실행 확인 완료.
- 프론트(`frontend/`): 전체 화면, 오디오 엔진, 오프라인 아웃박스·오버레이, Codex 리뷰 13건 반영, Fable 5.2 디자인 개선(문장 지도, 어순 조각 등), 포롱 캐릭터. Hallmark FAIL 0.
- 스크립트: `scripts/generate_tts.py`(Codex 작성), `scripts/validate_content.py`.
- 콘텐츠: Day 1~66 전부(시드 `backend/src/main/resources/seed/lessons-day01-05.json` + `content/day06.json`~`day66.json`), 660문장·표현 1,231개(뜻·느낌·예문 3개). `validate_content.py --all` → ERROR 0, WARN 0.
- 구독 생성기 실측 성공: `claude -p --setting-sources project --no-session-persistence --model haiku --tools "" --output-format json --json-schema …`, 빈 폴더에서, **자식 환경에서 CLAUDE*·ANTHROPIC* 변수 제거 필수**(데스크톱 앱 상속 변수 때문에 인증 실패). CLI 로그인은 2026-11-15 21:43에 갱신됨.

## 멈춘 작업 (이어서 할 것)
1. **단어장 백엔드**(백엔드 세션 `local_3aeea3cd…`, 중간 정지): 표 3개(term·sentence_term·vocab_entry), `app.vocab` 설정, 임포트 terms 저장, `anthropic-java:3.3.0` 의존성까지 진행. 남은 것: VocabService·이벤트 4종·생성기(ClaudeCli/Api/Noop)·예문 음성·`GET /api/v1/vocab`·`/admin/vocab/retry`·세션 문장 `terms`·테스트. **빌드가 깨져 있을 수 있음** → `cd backend && gradlew.bat test` 먼저.
   - 전달 못 했을 수 있는 내용: 위 "자식 환경 변수 제거"와 `--setting-sources project` 필수, 안전장치 오탐 응답도 FAILED 처리.
2. **단어장 화면**(Fable 5.2, 중간 정지): `frontend/src/lib/vocab.ts`, `components/VocabList.tsx`, `VocabPickSheet.tsx`, `VocabStage.tsx`가 만들어지는 중이었음. 타입 검사는 통과하지만 미완성일 수 있음 → 파일을 읽고 이어서 완성(카드 단어 버튼 → 단어 담기 시트, 세 번째 탭 `?view=vocab`, 필터·카드 넘기기·뜻/느낌/예문 3개 듣기·외웠어요·빼기·직접 입력). 데이터 계층(`api/types.ts`, `state/overlay.ts` `applyVocab`, `state/store.ts` 단어장 받기·15초 갱신)은 완성.
3. **콘텐츠 Codex 검수** → 고치기 → 임포트(`python scripts/generate_tts.py --import content/day06.json …`) → 음성 생성(660문장).
4. **통합 검증**: `build-all.bat`, 실제 데이터로 화면 확인, Codex 최종 리뷰.
5. **사용자 결정 대기**: 서버용 장기 토큰(`claude setup-token`) + 로그인 끊김 감지·폰 알림 추가 여부. 클라우드 배포(Oracle Always Free) 여부 — Codex 검토 결과: `docs/deploy/oracle-always-free-review.md`.

## 미리보기 실행법
`.claude/launch.json`: `backend-preview`(8090, `.preview-data/`, jar 사본 `.preview-data/habit66-preview.jar`), `frontend-preview`(5174, `.env.preview`). 실제 `data/`와 8080은 건드리지 않는다.

## 참고
- Codex 사용: 읽기 전용 샌드박스가 셸을 못 띄우므로 파일 전문을 stdin에 첨부(`scratchpad/arena-architect/bundle.py` 방식). 메모리 `codex-sandbox-windows.md`.
- 디자인 시안·스크린샷: `docs/design/mockups/`, `docs/design/applied/`.
