package com.habit66.common;

import java.io.IOException;

/** 요청 본문이 상한을 넘었다. 본문을 읽는 도중에 던져진다. */
public class BodyTooLargeException extends IOException {

    public BodyTooLargeException(long limit) {
        super("Request body exceeds " + limit + " bytes");
    }
}
