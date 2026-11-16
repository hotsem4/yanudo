package com.habit66.progress;

/** 클라이언트가 만든 학습 사실 하나. 형식 검증을 통과한 것만 이 타입이 된다. */
public sealed interface ClientEvent {

    enum Session { TODAY, REVIEW }

    enum Via { AUDIO, CARD }

    enum MarkStatus { NEW, MEMORIZED, PRACTICE }

    enum LockResult { CLEARED, SKIPPED }

    String id();

    /** 서버 시계 추정값(클라이언트가 server_time 으로 보정). */
    long occurredAt();

    record View(String id, long occurredAt, long sentenceId, Session session, Via via) implements ClientEvent {
    }

    record Mark(String id, long occurredAt, long sentenceId, MarkStatus status) implements ClientEvent {
    }

    record Focus(String id, long occurredAt, long startedAt, int seconds) implements ClientEvent {
    }

    record MorningLock(String id, long occurredAt, LockResult result) implements ClientEvent {
    }

    /** 말하기 연습: 한 문장을 reps 번 맞게 말해 끝냈다. 빼먹은 날의 보충도 이것으로 센다. */
    record Speak(String id, long occurredAt, long sentenceId, int reps) implements ClientEvent {
    }

    /** "오늘은 어려워요": occurredAt 의 논리 날짜를 쉬는 날로 미리 알린다. */
    record RestDay(String id, long occurredAt) implements ClientEvent {
    }

    /** @param sentenceId 담을 때 보고 있던 문장(없으면 null) @param meaning 직접 적은 뜻(있으면 생성하지 않는다) */
    record VocabAdd(String id, long occurredAt, String text, Long sentenceId, String meaning, String note)
            implements ClientEvent {
    }

    record VocabRemove(String id, long occurredAt, String termKey) implements ClientEvent {
    }

    record VocabMaster(String id, long occurredAt, String termKey, boolean mastered) implements ClientEvent {
    }

    /** @param meaning null 이면 뜻은 그대로 둔다 @param note 메모(null 이면 지운다) */
    record VocabEdit(String id, long occurredAt, String termKey, String meaning, String note) implements ClientEvent {
    }
}
