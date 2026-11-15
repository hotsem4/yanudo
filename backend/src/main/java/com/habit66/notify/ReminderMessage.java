package com.habit66.notify;

/** 푸시 페이로드 모양 그대로: {"title","body","url","tag"}. */
public record ReminderMessage(String title, String body, String url, String tag) {

    public static final String TAG = "habit66-reminder";
    public static final String AUTOPLAY_URL = "/?autoplay=true&mode=shadowing";
}
