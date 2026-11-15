package com.habit66.content;

/** 임포트가 커밋되어 카탈로그가 바뀌었다(새 오디오가 필요할 수 있다). */
public record CatalogChanged(int created, int updated) {
}
