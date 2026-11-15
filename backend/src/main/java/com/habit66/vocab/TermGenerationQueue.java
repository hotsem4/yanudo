package com.habit66.vocab;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.habit66.common.LogicalClock;
import com.habit66.content.CatalogService;
import com.habit66.tts.TtsService;
import com.habit66.vocab.TermGenerator.Generated;
import com.habit66.vocab.TermGenerator.Request;
import com.habit66.vocab.TermStore.Term;

import jakarta.annotation.PreDestroy;

/**
 * 미리 만들지 않은 표현의 생성 작업을 한 줄로 처리한다(docs/architecture.md "단어장").
 *
 * <ul>
 * <li>한 표현은 논리 날짜 하루에 {@value #MAX_ATTEMPTS_PER_DAY}번까지 시도한다. 넘으면 FAILED 로 두고 다음 날 시작·재시도 때 다시 한다.</li>
 * <li>사용자가 손봐야 풀리는 실패(로그인 만료 등)가 나면, 그때까지 큐에 있던 표현은 시도하지 않고 FAILED 로 둔다.
 * 그 뒤에 새로 들어온 표현은 다시 시도한다(그사이 로그인했을 수 있다).</li>
 * </ul>
 */
@Component
public class TermGenerationQueue {

    static final int MAX_ATTEMPTS_PER_DAY = 3;

    private static final Logger log = LoggerFactory.getLogger(TermGenerationQueue.class);

    private final TermStore terms;
    private final TermGenerator generator;
    private final LogicalClock clock;
    private final CatalogService catalog;
    private final JdbcTemplate jdbc;
    private final TtsService tts;
    private final GeneratorHealth health;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vocab-generator");
        t.setDaemon(true);
        return t;
    });
    /** queued·again 의 검사와 갱신, 작업 종료 인계를 한 번에 한다(따로 하면 다시 맡긴 표시가 주인 없이 남는다). */
    private final Object handoff = new Object();
    private final Set<Long> queued = new HashSet<>();
    /** 대기·실행 중에 다시 맡겨진 표현과 그 요청 번호. 지금 작업이 끝나면 한 번 더 시도한다. */
    private final Map<Long, Long> again = new HashMap<>();
    private volatile boolean stopped;
    private final AtomicLong sequence = new AtomicLong();
    /** 이 번호 이하로 들어온 작업은 시도하지 않는다(사용자 조치가 필요한 실패 이후). */
    private volatile long haltedThrough = -1;

    public TermGenerationQueue(TermStore terms, TermGenerator generator, LogicalClock clock, CatalogService catalog,
            JdbcTemplate jdbc, TtsService tts, GeneratorHealth health) {
        this.terms = terms;
        this.generator = generator;
        this.clock = clock;
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.tts = tts;
        this.health = health;
    }

    /** 생성을 맡긴다. 이미 큐에 있으면 무시한다. */
    public void enqueue(long termId) {
        long ticket;
        synchronized (handoff) {
            if (stopped) {
                return;
            }
            if (queued.contains(termId)) {
                // 이미 대기·실행 중이다. 버리면 로그인 복구 직후의 재시도처럼 의도한 재시도가 사라지므로,
                // 지금 작업이 끝난 뒤 한 번 더 시도한다. 요청 번호는 중단 정책(haltedThrough)과 비교하려고 둔다.
                again.put(termId, sequence.incrementAndGet());
                return;
            }
            queued.add(termId);
            ticket = sequence.incrementAndGet();
        }
        try {
            worker.execute(() -> run(termId, ticket));
        } catch (RejectedExecutionException e) {
            synchronized (handoff) {
                queued.remove(termId); // 서버 종료 중
            }
        }
    }

    private void run(long termId, long ticket) {
        try {
            process(termId, ticket);
        } catch (RuntimeException e) {
            log.warn("Vocab generation crashed for term {}: {}", termId, e.toString());
            terms.markFailed(termId);
        } finally {
            long next = -1;
            synchronized (handoff) {
                Long requested = again.remove(termId);
                // 로그인 실패로 멈추기 전에 들어온 요청(번호가 haltedThrough 이하)은 다시 하지 않는다. 그 뒤의 요청만 다시 맡긴다.
                // 다시 맡길 때는 queued 에 남긴 채 넘긴다(그사이 비어 보이면 idle() 이 잠깐 참이 된다).
                if (requested != null && requested > haltedThrough && !stopped) {
                    next = sequence.incrementAndGet();
                } else {
                    queued.remove(termId);
                }
            }
            if (next > 0) {
                long ticket2 = next;
                try {
                    terms.markPending(termId); // 이미 만들어졌으면(READY) 바뀌지 않고, 다음 실행이 바로 끝난다
                    worker.execute(() -> run(termId, ticket2));
                } catch (RuntimeException e) {
                    // 종료 중(RejectedExecution)이거나 DB 오류. 큐 표시를 남기면 이후 요청이 again 에만 쌓인다.
                    synchronized (handoff) {
                        queued.remove(termId);
                        again.remove(termId);
                    }
                    if (!(e instanceof RejectedExecutionException)) {
                        log.warn("Cannot requeue vocab term {}: {}", termId, e.toString());
                    }
                }
            }
        }
    }

    /** 맡은 작업이 모두 끝났는가(결과 저장까지). 테스트가 완료를 기다릴 때 쓴다. */
    boolean idle() {
        synchronized (handoff) {
            return queued.isEmpty() && again.isEmpty();
        }
    }

    /** 담겨 있고 아직 만들어지지 않은 표현을 모두 다시 맡긴다. 맡긴 수를 돌려준다. */
    public int retryAll() {
        List<Long> ids = terms.generationTargets();
        for (long id : ids) {
            terms.markPending(id);
            enqueue(id);
        }
        return ids.size();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        int count = retryAll();
        if (count > 0) {
            log.info("Retrying vocab generation for {} terms (generator: {})", count,
                    generator.getClass().getSimpleName());
        }
    }

    /** 로그인이 다시 살아나면 그사이 실패한 표현을 다시 만든다. */
    @EventListener(GeneratorHealth.Recovered.class)
    public void onRecovered() {
        retryAll();
    }

    @PreDestroy
    void stop() {
        stopped = true;
        worker.shutdownNow();
    }

    private void process(long termId, long ticket) {
        Term term = terms.findById(termId).orElse(null);
        if (term == null || !TermStore.PENDING.equals(term.status())) {
            return;
        }
        long now = clock.now();
        if (!generator.enabled() || ticket <= haltedThrough) {
            terms.markFailed(termId);
            return;
        }
        boolean sameDay = clock.dateOf(term.updatedAt()).equals(clock.dateOf(now));
        int attempts = sameDay ? term.attempts() : 0;
        if (attempts >= MAX_ATTEMPTS_PER_DAY) {
            log.info("Vocab generation for '{}' already tried {} times today", term.text(), attempts);
            terms.markFailed(termId);
            return;
        }
        terms.countAttempt(termId, attempts + 1, now);
        try {
            Generated generated = generator.generate(new Request(term.text(), contextSentence(termId))).validated();
            terms.saveGenerated(termId, generated.meaning(), generated.nuance(), generated.examples(), clock.now());
            log.info("Generated vocab entry for '{}'", term.text());
            health.generated();
            if (tts.autoGenerate()) {
                tts.generate();
            }
        } catch (GenerationException e) {
            log.warn("Vocab generation failed for '{}': {}", term.text(), e.getMessage());
            if (e.needsUserAction()) {
                // 로그인 같은 사용자 조치 실패는 하루 시도 횟수에서 뺀다. 세면 로그인을 고친 뒤에도 그날은 "이미 3번"으로 FAILED 가 된다.
                terms.countAttempt(termId, attempts, clock.now());
                haltedThrough = sequence.get();
                log.warn("Vocab generation paused until the next add or retry: {}", e.hint());
                // 알림(몇 초 걸릴 수 있다)을 FAILED 보다 먼저 보낸다. FAILED 가 보인 뒤 곧바로 온 재시도가
                // 아직 끝나지 않은 이 작업 때문에 무시되지 않게.
                health.loginFailed(e.hint());
            }
            terms.markFailed(termId);
        }
    }

    /** 이 표현을 담을 때 보고 있던 문장(생성 품질을 높인다). */
    private String contextSentence(long termId) {
        List<Long> ids = jdbc.queryForList("SELECT sentence_id FROM vocab_entry WHERE term_id = ?", Long.class,
                termId);
        if (ids.isEmpty() || ids.get(0) == null) {
            return null;
        }
        return catalog.current().sentence(ids.get(0)).map(s -> s.enText()).orElse(null);
    }
}
