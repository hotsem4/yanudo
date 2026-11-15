package com.habit66.vocab;

import java.util.List;

import com.habit66.vocab.TermStore.Example;

/** 미리 만들지 않은 표현의 뜻·느낌·예문을 만든다. 구현은 {@code app.vocab.generator}로 하나를 고른다. */
public interface TermGenerator {

    int EXAMPLES = 3;

    /** @param contextSentence 사용자가 이 표현을 담을 때 보고 있던 문장. 없으면 null */
    record Request(String text, String contextSentence) {
    }

    record Generated(String meaning, String nuance, List<Example> examples) {

        /** 단어장에 그대로 넣을 수 있는 모양인지 확인한다. 생성기가 무엇을 돌려주든 여기를 통과해야 저장한다. */
        public Generated validated() throws GenerationException {
            if (blank(meaning) || blank(nuance)) {
                throw new GenerationException("generator returned an empty meaning or nuance");
            }
            if (examples == null || examples.size() != EXAMPLES) {
                throw new GenerationException("generator returned " + (examples == null ? 0 : examples.size())
                        + " examples instead of " + EXAMPLES);
            }
            for (Example example : examples) {
                if (example == null || blank(example.en()) || blank(example.ko())) {
                    throw new GenerationException("generator returned an empty example");
                }
            }
            return new Generated(meaning.strip(), nuance.strip(),
                    examples.stream().map(e -> new Example(e.en().strip(), e.ko().strip())).toList());
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    /** 생성을 하지 않는 구현이면 false. 그때는 시도 횟수를 세지 않고 바로 직접 입력을 안내한다. */
    default boolean enabled() {
        return true;
    }

    Generated generate(Request request) throws GenerationException;
}
