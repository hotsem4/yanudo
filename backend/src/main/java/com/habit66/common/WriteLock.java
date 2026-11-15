package com.habit66.common;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * SQLite 는 쓰기가 한 번에 하나뿐이다. 모든 DB 쓰기를 프로세스 안에서 한 줄로 세워, 읽다가 쓰기로 넘어가는 트랜잭션이
 * 다른 연결의 커밋과 부딪히는 일(SQLITE_BUSY_SNAPSHOT)을 없앤다. 콘텐츠 임포트와 이벤트 수용이 서로의 중간 상태를
 * 보지 않게 하는 역할도 한다. 재진입 가능하다.
 */
@Component
public class WriteLock {

    private final ReentrantLock lock = new ReentrantLock();

    public <T> T call(Supplier<T> body) {
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    public void run(Runnable body) {
        call(() -> {
            body.run();
            return null;
        });
    }
}
