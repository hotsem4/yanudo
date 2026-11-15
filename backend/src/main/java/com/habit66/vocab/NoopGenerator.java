package com.habit66.vocab;

/** {@code app.vocab.generator: none}. 만들지 않는다. 사용자가 뜻을 직접 적는다. */
public class NoopGenerator implements TermGenerator {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Generated generate(Request request) throws GenerationException {
        throw new GenerationException("generation is turned off (app.vocab.generator=none)");
    }
}
