package com.habit66.tts;

import java.util.List;

/**
 * 레슨 문장 말고도 음성이 필요한 발화를 내놓는 쪽(단어장에 담긴 표현의 예문).
 * {@link TtsService}는 여기서 받은 발화를 작업 목록과 "아는 파일 이름"에 더할 뿐, 누가 왜 필요로 하는지는 모른다.
 */
public interface ClipSource {

    List<Utterance> utterances();
}
