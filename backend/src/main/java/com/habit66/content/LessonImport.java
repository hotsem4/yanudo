package com.habit66.content;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.habit66.common.ApiException;
import com.habit66.vocab.TermKey;

/** 임포트 본문(시드 파일과 같은 모양). {@link #validate()}를 통과한 것만 저장한다. */
public record LessonImport(List<LessonIn> lessons) {

    public record LessonIn(Integer day, String title, String theme, List<SentenceIn> sentences) {
    }

    /** @param terms 키가 없으면 null(이 문장의 기존 표현 연결을 건드리지 않는다), 있으면 그 목록으로 바꾼다 */
    public record SentenceIn(String speaker, String enText, String koWordOrder, String koNatural, Boolean core,
            List<TermIn> terms) {
    }

    /** @param form 문장에 실제로 나온 형태. 생략하면 text 와 같다 */
    public record TermIn(String text, String form, String meaning, String nuance, List<ExampleIn> examples) {

        public String formOrText() {
            return (blank(form) ? text : form).strip();
        }
    }

    public record ExampleIn(String en, String ko) {
    }

    /** 66일은 라운드 길이일 뿐이라 그 뒤 Day 의 레슨도 넣을 수 있다. */
    public static final int MAX_DAY = 999;
    public static final int MAX_TERMS = 3;
    public static final int EXAMPLES = 3;

    public void validate() {
        if (lessons == null || lessons.isEmpty()) {
            throw invalid("lessons 배열이 비어 있습니다.");
        }
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < lessons.size(); i++) {
            LessonIn lesson = lessons.get(i);
            String where = "lessons[" + i + "]";
            if (lesson == null || lesson.day() == null || lesson.day() < 1 || lesson.day() > MAX_DAY) {
                throw invalid(where + ".day 는 1~" + MAX_DAY + " 사이여야 합니다.");
            }
            where = "Day " + lesson.day();
            if (!seen.add(lesson.day())) {
                throw invalid(where + " 가 두 번 들어 있습니다.");
            }
            if (lesson.sentences() == null || lesson.sentences().isEmpty()) {
                throw invalid(where + ": 문장이 1개 이상 있어야 합니다.");
            }
            for (int j = 0; j < lesson.sentences().size(); j++) {
                SentenceIn s = lesson.sentences().get(j);
                String at = where + " " + (j + 1) + "번째 문장";
                if (s == null) {
                    throw invalid(at + "이 비어 있습니다.");
                }
                if (!"A".equals(s.speaker()) && !"B".equals(s.speaker())) {
                    throw invalid(at + ": speaker 는 A 또는 B 여야 합니다.");
                }
                if (blank(s.enText()) || blank(s.koWordOrder()) || blank(s.koNatural())) {
                    throw invalid(at + ": en_text, ko_word_order, ko_natural 은 비어 있으면 안 됩니다.");
                }
                validateTerms(s, at);
            }
        }
    }

    /** scripts/validate_content.py 의 terms 규칙과 같다(스크립트를 통과한 파일은 임포트도 통과해야 한다). */
    private static void validateTerms(SentenceIn sentence, String at) {
        List<TermIn> terms = sentence.terms();
        if (terms == null) {
            return;
        }
        if (terms.size() > MAX_TERMS) {
            throw invalid(at + ": terms 는 " + MAX_TERMS + "개까지입니다.");
        }
        String en = sentence.enText().toLowerCase(Locale.ROOT);
        Set<String> keys = new HashSet<>();
        for (TermIn term : terms) {
            if (term == null || blank(term.text())) {
                throw invalid(at + ": 표현의 text 가 비어 있습니다.");
            }
            String tag = at + " 표현 \"" + term.text().strip() + "\"";
            if (!keys.add(TermKey.of(term.text()))) {
                throw invalid(tag + ": 같은 문장에 두 번 들어 있습니다.");
            }
            String form = term.formOrText();
            if (!en.contains(form.toLowerCase(Locale.ROOT))) {
                throw invalid(tag + ": form \"" + form + "\" 이 en_text 에 없습니다.");
            }
            if (blank(term.meaning()) || blank(term.nuance())) {
                throw invalid(tag + ": meaning, nuance 는 비어 있으면 안 됩니다.");
            }
            if (term.examples() == null || term.examples().size() != EXAMPLES) {
                throw invalid(tag + ": examples 는 정확히 " + EXAMPLES + "개여야 합니다.");
            }
            for (ExampleIn example : term.examples()) {
                if (example == null || blank(example.en()) || blank(example.ko())) {
                    throw invalid(tag + ": 예문의 en, ko 는 비어 있으면 안 됩니다.");
                }
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("INVALID_IMPORT", message);
    }
}
