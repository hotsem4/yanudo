package com.habit66.content;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeMap;

/** 콘텐츠의 불변 읽기 모델. 임포트가 끝날 때마다 통째로 새로 만든다. */
public final class Catalog {

    /** @param createdAt 문장을 처음 넣은 시각(epoch ms). 지난 날 보충을 판정할 때 그날 없던 문장을 빼는 데 쓴다. */
    public record SentenceView(long id, int day, int seq, String speaker, String enText, String koWordOrder,
            String koNatural, boolean core, long createdAt) {
    }

    public record LessonView(int day, String title, String theme, List<SentenceView> sentences) {

        public LessonView {
            sentences = List.copyOf(sentences);
        }

        public List<Long> sentenceIds() {
            return sentences.stream().map(SentenceView::id).toList();
        }
    }

    private static final Catalog EMPTY = new Catalog(List.of());

    private final NavigableMap<Integer, LessonView> byDay;
    private final Map<Long, SentenceView> byId;

    private Catalog(List<LessonView> lessons) {
        NavigableMap<Integer, LessonView> days = new TreeMap<>();
        Map<Long, SentenceView> ids = new HashMap<>();
        for (LessonView lesson : lessons) {
            days.put(lesson.day(), lesson);
            for (SentenceView sentence : lesson.sentences()) {
                ids.put(sentence.id(), sentence);
            }
        }
        this.byDay = Collections.unmodifiableNavigableMap(days);
        this.byId = Map.copyOf(ids);
    }

    public static Catalog of(List<LessonView> lessons) {
        return lessons.isEmpty() ? EMPTY : new Catalog(lessons);
    }

    public static Catalog empty() {
        return EMPTY;
    }

    /** 등록된 일차(오름차순). */
    public NavigableSet<Integer> days() {
        return byDay.navigableKeySet();
    }

    /** 일차 오름차순. */
    public List<LessonView> lessons() {
        return List.copyOf(byDay.values());
    }

    public Optional<LessonView> lesson(int day) {
        return Optional.ofNullable(byDay.get(day));
    }

    public Optional<SentenceView> sentence(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    public int maxDay() {
        return byDay.isEmpty() ? 0 : byDay.lastKey();
    }

    public int sentenceCount() {
        return byId.size();
    }

    public boolean isEmpty() {
        return byDay.isEmpty();
    }
}
