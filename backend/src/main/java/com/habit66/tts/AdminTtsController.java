package com.habit66.tts;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.habit66.common.ApiException;
import com.habit66.tts.AudioLibrary.Accepted;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminTtsController {

    private static final int MAX_CLIP_BYTES = 5 * 1024 * 1024;

    private final TtsService tts;
    private final AudioLibrary library;

    public AdminTtsController(TtsService tts, AudioLibrary library) {
        this.tts = tts;
        this.library = library;
    }

    @GetMapping("/tts/jobs")
    public Map<String, Object> jobs() {
        return Map.of("jobs", tts.jobs(), "engine", tts.engine());
    }

    @PutMapping("/audio/{file}")
    public Accepted upload(@PathVariable String file, @RequestBody(required = false) byte[] body) {
        if (!AudioLibrary.isClipName(file) || !tts.knows(file)) {
            throw ApiException.notFound("UNKNOWN_CLIP", "현재 콘텐츠에 없는 오디오 파일 이름입니다.");
        }
        if (body == null || body.length == 0 || body.length > MAX_CLIP_BYTES) {
            throw ApiException.unprocessable("INVALID_AUDIO", "오디오 본문이 비어 있거나 너무 큽니다.");
        }
        return library.accept(file, body);
    }

    @PostMapping("/tts/generate")
    public Map<String, Boolean> generate() {
        return Map.of("started", tts.generate());
    }

    @GetMapping("/tts/status")
    public TtsService.Status status() {
        return tts.status();
    }

    @PostMapping("/tts/sync")
    public Map<String, Integer> sync() {
        return Map.of("indexed", library.rescan());
    }
}
