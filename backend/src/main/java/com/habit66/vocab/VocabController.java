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

    public VocabController(VocabService vocab) {
        this.vocab = vocab;
    }

    @GetMapping("/vocab")
    public VocabList list() {
        return vocab.list();
    }

    @PostMapping("/admin/vocab/retry")
    public Map<String, Integer> retry() {
        return Map.of("queued", vocab.retry());
    }
}
