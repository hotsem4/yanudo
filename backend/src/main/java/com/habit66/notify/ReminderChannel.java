package com.habit66.notify;

public interface ReminderChannel {

    enum Kind { PUSH, WEBHOOK }

    Kind kind();

    /** 전달에 성공한 수를 돌려준다. 꺼져 있거나 실패하면 0 이고, 예외를 던지지 않는다. */
    int send(ReminderMessage message);
}
