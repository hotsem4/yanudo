"""레슨 JSON 검증(SPEC 4.7 + docs/curriculum.md 작성 규칙).

사용:
  python scripts/validate_content.py content/day07.json content/day08.json
  python scripts/validate_content.py content            # 폴더 안 모든 dayNN.json
  python scripts/validate_content.py --all              # 시드 + content 전체(일차 간 중복 문장 검사 포함)

ERROR 가 하나라도 있으면 종료 코드 1. WARN 은 사람이 판단한다.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SEED = ROOT / "backend" / "src" / "main" / "resources" / "seed" / "lessons-day01-05.json"

# 단계별 권장 영어 단어 수(문장 하나). 범위를 벗어나면 WARN.
PHASES = [(1, 21, 4, 12), (22, 35, 5, 14), (36, 50, 6, 17), (51, 66, 7, 20)]


def phase_range(day: int) -> tuple[int, int]:
    for lo, hi, mn, mx in PHASES:
        if lo <= day <= hi:
            return mn, mx
    return 4, 20


def text_of(value: object) -> str:
    """문자열이면 앞뒤 공백을 뺀 값, 아니면(null·숫자 등) 빈 문자열. str(None) == "None" 이 통과하지 않게 한다."""
    return value.strip() if isinstance(value, str) else ""


def term_key(text: str) -> str:
    """서버 TermKey 와 같은 규칙: 공백을 하나로 정리하고 소문자."""
    return " ".join(text.split()).lower()


def words(text: str) -> int:
    return len(re.findall(r"[A-Za-z0-9']+", text))


def load(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    lessons = data.get("lessons")
    if not isinstance(lessons, list):
        raise ValueError("최상위에 lessons 배열이 없습니다")
    return lessons


def check_lesson(lesson: dict, where: str, errors: list[str], warns: list[str]) -> None:
    day = lesson.get("day")
    tag = f"{where} Day {day}"
    if not isinstance(day, int) or not 1 <= day <= 66:
        errors.append(f"{tag}: day 는 1~66 정수")
        return
    for key in ("title", "theme"):
        if not str(lesson.get(key, "")).strip():
            errors.append(f"{tag}: {key} 비어 있음")
    sentences = lesson.get("sentences")
    if not isinstance(sentences, list) or len(sentences) != 10:
        errors.append(f"{tag}: 문장은 정확히 10개 (지금 {len(sentences) if isinstance(sentences, list) else '없음'})")
        return
    mn, mx = phase_range(day)
    cores = 0
    prev_speaker = None
    for i, s in enumerate(sentences, 1):
        st = f"{tag} #{i}"
        sp = s.get("speaker")
        if sp not in ("A", "B"):
            errors.append(f"{st}: speaker 는 A/B")
        elif sp == prev_speaker:
            warns.append(f"{st}: 같은 화자가 연속 ({sp})")
        prev_speaker = sp
        en = text_of(s.get("en_text"))
        wo = text_of(s.get("ko_word_order"))
        kn = text_of(s.get("ko_natural"))
        if not en or not wo or not kn:
            errors.append(f"{st}: en_text·ko_word_order·ko_natural 은 비어 있으면 안 됨")
            continue
        n = words(en)
        if not mn <= n <= mx:
            warns.append(f"{st}: 영어 {n}단어 (Day {day} 권장 {mn}~{mx}) \"{en}\"")
        chunks = [c for c in wo.split(" / ") if c.strip()]
        if not 2 <= len(chunks) <= 6:
            warns.append(f"{st}: 어순 덩어리 {len(chunks)}개 (권장 2~6, 구분자는 ' / ') \"{wo}\"")
        if "/" in wo and " / " not in wo:
            errors.append(f"{st}: 어순 구분자는 공백 포함 ' / '")
        core = s.get("core", False)
        if not isinstance(core, bool):
            errors.append(f"{st}: core 는 true/false")
        elif core:
            cores += 1
        terms = s.get("terms", [])
        if not isinstance(terms, list) or len(terms) > 3:
            errors.append(f"{st}: terms 는 0~3개 배열")
            continue
        seen_keys: set[str] = set()
        for t in terms:
            if not isinstance(t, dict):
                errors.append(f"{st}: terms 원소는 객체여야 함")
                continue
            tt = f"{st} term \"{t.get('text')}\""
            text = text_of(t.get("text"))
            form = text_of(t.get("form")) or text
            if not text:
                errors.append(f"{tt}: text 비어 있음")
            elif term_key(text) in seen_keys:
                # 서버 임포트(LessonImport)가 한 문장 안의 같은 표현을 거절한다.
                errors.append(f"{tt}: 같은 문장에 같은 표현이 두 번 있음")
            else:
                seen_keys.add(term_key(text))
            if form.lower() not in en.lower():
                errors.append(f"{tt}: form \"{form}\" 이 en_text 에 없음")
            for key in ("meaning", "nuance"):
                if not text_of(t.get(key)):
                    errors.append(f"{tt}: {key} 비어 있음")
            ex = t.get("examples")
            if not isinstance(ex, list) or len(ex) != 3:
                errors.append(f"{tt}: examples 는 정확히 3개")
                continue
            for e in ex:
                if not isinstance(e, dict) or not text_of(e.get("en")) or not text_of(e.get("ko")):
                    errors.append(f"{tt}: 예문 en·ko 비어 있음")
                elif words(e["en"]) > 18:
                    warns.append(f"{tt}: 예문이 김 ({words(e['en'])}단어) \"{e['en']}\"")
    if cores != 3:
        errors.append(f"{tag}: core 는 정확히 3개 (지금 {cores})")


def main(argv: list[str]) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    paths: list[Path] = []
    if not argv or argv == ["--all"]:
        paths = [SEED, *sorted((ROOT / "content").glob("day*.json"))]
    else:
        for a in argv:
            p = Path(a)
            paths.extend(sorted(p.glob("day*.json")) if p.is_dir() else [p])
    errors: list[str] = []
    warns: list[str] = []
    seen_en: dict[str, str] = {}
    days: dict[int, str] = {}
    for path in paths:
        try:
            lessons = load(path)
        except Exception as exc:  # noqa: BLE001
            errors.append(f"{path.name}: 읽을 수 없음 ({exc})")
            continue
        for lesson in lessons:
            check_lesson(lesson, path.name, errors, warns)
            d = lesson.get("day")
            if isinstance(d, int):
                if d in days and days[d] != path.name:
                    warns.append(f"Day {d}: {days[d]} 와 {path.name} 에 중복 정의")
                days[d] = path.name
            for s in lesson.get("sentences") or []:
                key = re.sub(r"\W+", " ", str(s.get("en_text", "")).lower()).strip()
                if key and key in seen_en:
                    warns.append(f"{path.name} Day {d}: 다른 일차와 같은 문장 \"{s.get('en_text')}\" ({seen_en[key]})")
                seen_en.setdefault(key, f"Day {d}")
    for w in warns:
        print("WARN ", w)
    for e in errors:
        print("ERROR", e)
    print(f"검사: 파일 {len(paths)}개, 일차 {len(days)}개, ERROR {len(errors)}, WARN {len(warns)}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
