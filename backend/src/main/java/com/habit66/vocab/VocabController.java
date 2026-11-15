package com.habit66.vocab;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.habit66.vocab.VocabService.VocabList;

/** 단어장 읽기와 생성 재시도(SPEC 4.9). 쓰기는 POST /events 의 VOCAB_* 이벤트다. */
@RestController
@RequestMapping("/api/v1")
public class VocabController {

    private final VocabService vocab;
    private final GeneratorHealth health;

    public VocabController(VocabService vocab, GeneratorHealth health) {
        this.vocab = vocab;
        this.health = health;
    }

    @GetMapping("/vocab")
    public VocabList list() {
        return vocab.list();
    }

    @PostMapping("/admin/vocab/retry")
    public Map<String, Integer> retry() {
        return Map.of("queued", vocab.retry());
    }

    /** 생성기 로그인을 지금 점검한다(장기 토큰을 넣고 서버를 다시 켠 뒤 확인용). 몇 초 걸린다. */
    @PostMapping("/admin/vocab/check-login")
    public GeneratorHealth.View checkLogin() {
        return health.check();
    }
}
