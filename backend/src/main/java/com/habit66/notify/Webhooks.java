package com.habit66.notify;

import java.time.Duration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class Webhooks {

    private Webhooks() {
    }

    /** 웹훅이 응답하지 않아도 스케줄러 스레드가 오래 묶이지 않게 시간 제한을 둔다. */
    static RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }
}
