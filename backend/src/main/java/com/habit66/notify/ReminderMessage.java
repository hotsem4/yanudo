package com.habit66.notify;

/** 푸시 페이로드 모양 그대로: {"title","body","url","tag"}. */
public record ReminderMessage(String title, String body, String url, String tag) {

    public static final String TAG = "habit66-reminder";
    /**
     * 알림을 누르면 여는 곳: 오늘의 10문장(자동 재생 없음). 오늘 달성은 따라 말하기라서 재생으로 열면
     * 마이크가 없는 재생 독이 뜨고 달성에 보탬이 되지 않는다.
     */
    public static final String TODAY_URL = "/?view=today";
}
