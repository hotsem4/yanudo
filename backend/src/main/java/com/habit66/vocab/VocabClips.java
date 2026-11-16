package com.habit66.vocab;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.habit66.config.AppProperties;
import com.habit66.tts.ClipSource;
import com.habit66.tts.Utterance;
import com.habit66.vocab.TermStore.Entry;
import com.habit66.vocab.TermStore.Example;

/**
 * 단어장에 담긴 표현의 예문 음성(SPEC F-08): 1·3번은 Ava, 2번은 Andrew, 속도는 문장과 같다.
 * 담긴 표현의 예문만 내놓으므로, 빼면 작업 목록에서도 빠진다(이미 만든 파일은 남는다).
 */
@Component
public class VocabClips implements ClipSource {

    private final TermStore terms;
    private final AppProperties.Tts tts;

    public VocabClips(TermStore terms, AppProperties props) {
        this.terms = terms;
        this.tts = props.tts();
    }

    /** @param index 예문 순서(0부터) */
    public Utterance utterance(int index, String en) {
        String voice = index % 2 == 0 ? tts.voiceB() : tts.voiceA();
        return new Utterance(voice, tts.rate(), en, tts.variant());
    }

    @Override
    public List<Utterance> utterances() {
        List<Utterance> all = new ArrayList<>();
        for (Entry entry : terms.entries()) {
            List<Example> examples = entry.term().examples();
            for (int i = 0; i < examples.size(); i++) {
                all.add(utterance(i, examples.get(i).en()));
            }
        }
        return all;
    }
}
