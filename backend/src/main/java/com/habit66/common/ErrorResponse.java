package com.habit66.common;

/** 모든 오류 응답의 모양: {"error": "코드", "message": "설명"}. */
public record ErrorResponse(String error, String message) {
}
