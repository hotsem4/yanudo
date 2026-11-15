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
}
