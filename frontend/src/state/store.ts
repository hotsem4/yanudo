import { api, ApiError, sendEventsKeepalive, serverNow } from "../api/client";
import type { Dashboard, EventsResponse, NewEvent, SessionData, StudyEvent, VocabEntry, VocabGenerator } from "../api/types";
import { applyPending, type DayView } from "./overlay";
import { newEventId, outbox, snapshotCache, type Snapshot } from "./outbox";

/** 단어장 응답 한 번. ok=false 면 받지 못해 이전 값을 담았다. */
interface VocabFetch {
  ok: boolean;
  entries: VocabEntry[] | null;
  generator: VocabGenerator | null;
}

export interface SyncState {
  pending: number;
  online: boolean;
  syncing: boolean;
  lastError: string | null;
  /** 서버가 계속 거절해 따로 보관한 기록 수. */
  held: number;
  /** 기기 저장소에 아웃박스를 저장하지 못했다(공간 부족 등). */
  saveFailed: boolean;
}

export type StudyState =
  | { kind: "booting" }
  | { kind: "error"; message: string }
  | { kind: "ready"; view: DayView; sync: SyncState };

const BATCH = 500;
/** 서버가 묶음을 이만큼 연속으로 거절하면 하나씩 보내 문제 기록을 가려낸다. */
const SINGLE_AFTER = 3;
/** 하나씩 보내서도 이만큼(누적) 거절되면 그 기록을 따로 보관하고 다음 기록을 보낸다. */
const HOLD_AFTER = 6;

/** 서버가 요청을 보고 거절한 응답인가. 502·503·504 는 서버가 꺼졌거나 앞단(tailscale serve) 문제라 연결 실패와 같다. */
function isServerRejection(status: number): boolean {
  if (status === 408 || status === 429) return false;
  return (status >= 400 && status < 500) || status === 500;
}
const DEBOUNCE_MS = 1500;

/**
 * 서버 스냅샷 + 아웃박스의 단일 소유자.
 * 공개 표면: subscribe / getState / start / record / refresh.
 * 숨기는 것: localStorage 영속화, 전송 순서·재시도, keepalive, 오래된 응답 버리기, 세션 재조회 시점.
 */
export class StudyStore {
  private snapshot: Snapshot | null = null;
  private pending: StudyEvent[] = [];
  /** 이 창이 서버에 보내 끝낸 이벤트 id. 다른 창의 저장소에서 다시 합치지 않으려고 둔다(최근 것만). */
  private sent = new Set<string>();
  private state: StudyState = { kind: "booting" };
  private listeners = new Set<() => void>();
  private sync: SyncState = { pending: 0, online: true, syncing: false, lastError: null, held: 0, saveFailed: false };
  private serverRejections = 0;
  /** 다른 창에 "이 기록은 보냈다"를 알린다. 모르면 그 창이 메모리에 든 기록을 다시 저장해 되살린다. */
  private channel: BroadcastChannel | null = null;
  private seq = 0;
  private appliedSeq = 0;
  private inFlight: Promise<void> | null = null;
  private again = false;
  private timer: number | null = null;
  private backoff = 2000;
  private started = false;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getState = (): StudyState => this.state;

  /** 앱 시작 때 한 번. 캐시로 즉시 그리고, 서버에서 새로 받는다. */
  start(): void {
    if (this.started) return;
    this.started = true;
    this.pending = outbox.load();
    this.snapshot = snapshotCache.load();
    this.sync.online = navigator.onLine;
    // 따로 보관한 기록은 시작할 때마다 아웃박스로 되돌려 한 번 더 보낸다(서버를 고쳤거나 일시 장애였을 수 있다).
    const held = outbox.loadHeld();
    if (held.length > 0) {
      const known = new Set(this.pending.map((p) => p.id));
      // 맨 뒤에 둔다. 앞에 두면 서버가 정말 못 받는 기록이 다시 보관될 때까지 정상 기록 전송이 밀린다.
      this.pending = [...this.pending, ...held.filter((e) => !known.has(e.id))];
      this.sync.saveFailed = !outbox.save(this.pending);
      if (!this.sync.saveFailed) outbox.clearHeld();
    }
    this.sync.held = outbox.heldCount();
    if ("BroadcastChannel" in window) {
      this.channel = new BroadcastChannel("habit66-outbox");
      this.channel.onmessage = (e: MessageEvent) => {
        const ids = (e.data as { sent?: unknown } | null)?.sent;
        if (!Array.isArray(ids)) return;
        ids.forEach((id) => {
          if (typeof id === "string") this.sent.add(id);
        });
        const before = this.pending.length;
        this.pending = this.pending.filter((p) => !this.sent.has(p.id));
        if (this.pending.length !== before) {
          this.persist(); // 그사이 다른 창이 저장한 새 기록을 덮지 않게 합친 뒤 저장한다
          this.publish();
        }
      };
    }
    this.publish();

    window.addEventListener("online", () => {
      this.sync.online = true;
      this.publish();
      void this.refresh();
    });
    window.addEventListener("offline", () => {
      this.sync.online = false;
      this.publish();
    });
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "hidden") sendEventsKeepalive(this.pending);
      else void this.refresh();
    });
    window.addEventListener("pagehide", () => sendEventsKeepalive(this.pending));
    // PWA 창과 브라우저 탭이 같은 아웃박스를 쓴다. 다른 창이 쌓은 미전송 기록을 내 목록에 합친다.
    window.addEventListener("storage", (e) => {
      if (e.key !== null && e.key !== outbox.key) return;
      if (this.mergeOthers()) this.publish();
    });
    // 자정 롤오버(논리 날짜 경계)를 넘기면 stale 이 되어 다시 받는다.
    window.setInterval(() => {
      if (this.snapshot && serverNow() >= this.snapshot.dashboard.valid_until) {
        this.publish();
        void this.refresh();
      }
    }, 30_000);
    // 담은 단어의 뜻·예문을 서버가 만드는 동안(PENDING) 15초마다 단어장만 다시 받는다.
    window.setInterval(() => {
      const pendingTerm = this.snapshot?.vocab?.some((v) => v.status === "PENDING");
      if (pendingTerm && this.sync.online && document.visibilityState === "visible") void this.refreshVocab();
    }, 15_000);

    void this.refresh();
  }

  /** 이벤트 기록: 아웃박스에 먼저 동기 저장 → 화면 반영 → 전송 예약. */
  record(e: NewEvent): void {
    const event = { ...e, id: e.id ?? newEventId(), occurred_at: serverNow() } as StudyEvent;
    if (event.type === "VIEW" && this.isDuplicateView(event)) return;
    if (event.type === "FOCUS") this.pending = this.pending.filter((p) => p.id !== event.id);
    this.pending.push(event);
    this.persist();
    this.publish();
    this.schedule(DEBOUNCE_MS);
  }

  /** 대기 이벤트가 있으면 보내고(응답이 대시보드), 없으면 대시보드를 받는다. 동시에 하나만. */
  refresh(): Promise<void> {
    if (this.inFlight) {
      this.again = true;
      return this.inFlight;
    }
    this.clearTimer();
    this.inFlight = (this.pending.length > 0 ? this.flush() : this.fetchAll())
      .then(() => {
        this.backoff = 2000;
        this.sync.lastError = null;
      })
      .catch((err: unknown) => {
        this.sync.lastError = err instanceof Error ? err.message : "동기화하지 못했어요.";
        if (!this.snapshot) this.state = { kind: "error", message: this.sync.lastError };
        if (this.pending.length > 0 || !this.snapshot) this.schedule(this.backoff);
        this.backoff = Math.min(this.backoff * 2, 60_000);
      })
      .finally(() => {
        this.inFlight = null;
        this.sync.syncing = false;
        this.publish();
        if (this.again) {
          this.again = false;
          void this.refresh();
        }
      });
    this.sync.syncing = true;
    this.publish();
    return this.inFlight;
  }

  // ── 내부 ───────────────────────────────────────────────────────

  private async fetchAll(): Promise<void> {
    const mine = ++this.seq;
    const [dashboard, vocab] = await Promise.all([api.dashboard(), this.fetchVocab()]);
    const sessions = await this.sessionsFor(dashboard, true);
    this.apply(mine, dashboard, sessions, vocab);
  }

  /** 단어장 받기. 실패하면(오프라인 등) 이전 값을 쓰고 ok=false 로 알린다. */
  private fetchVocab(): Promise<VocabFetch> {
    return api
      .vocab()
      .then((v) => ({ ok: true, entries: v.entries, generator: v.generator ?? null }))
      .catch(() => ({ ok: false, entries: this.snapshot?.vocab ?? null, generator: this.snapshot?.vocabGenerator ?? null }));
  }

  /** 뜻·예문을 만드는 중인 단어가 있을 때 그것만 다시 받는다. 그사이 다른 동기화가 시작됐으면 늦은 응답을 버린다. */
  private async refreshVocab(): Promise<void> {
    if (!this.snapshot || this.inFlight) return;
    const started = this.seq;
    const { ok, entries: vocab, generator } = await this.fetchVocab();
    if (!ok || !this.snapshot || this.seq !== started || this.inFlight) return;
    this.snapshot = { ...this.snapshot, vocab, vocabGenerator: generator };
    snapshotCache.save(this.snapshot);
    this.publish();
  }

  private async flush(): Promise<void> {
    const mine = ++this.seq;
    const batch = this.pending.slice(0, this.serverRejections >= SINGLE_AFTER ? 1 : BATCH);
    let res: EventsResponse;
    try {
      res = await api.events(batch);
      this.serverRejections = 0;
    } catch (err) {
      if (err instanceof ApiError && isServerRejection(err.status)) this.onBatchRejected(batch);
      throw err;
    }
    const done = new Set([...res.accepted, ...res.rejected.map((r) => r.id)]);
    // 암기 상태는 세션 응답에만 있으므로, MARK 를 보냈으면 세션을 다시 받아 오버레이가 빠진 뒤에도 값이 유지되게 한다.
    const sentMark = batch.some((e) => e.type === "MARK" && done.has(e.id));
    const sentVocab = batch.some((e) => e.type.startsWith("VOCAB_") && done.has(e.id));
    const prev = this.snapshot;
    const [sessions, vocab] = await Promise.all([
      this.sessionsFor(res.dashboard, sentMark).catch(() => null),
      sentVocab
        ? this.fetchVocab()
        : Promise.resolve<VocabFetch>({ ok: true, entries: prev?.vocab ?? null, generator: prev?.vocabGenerator ?? null }),
    ]);
    // 서버는 받았지만 새 세션을 못 받았다. 새 대시보드와 옛 세션을 한 스냅샷에 섞어 굳히지 않도록 아무것도 바꾸지 않고,
    // 보낸 이벤트도 아웃박스에 남긴 채 실패로 끝내 백오프 뒤 다시 보낸다(서버는 같은 이벤트를 두 번 세지 않는다).
    if (sessions === null) throw new Error("학습 목록을 받지 못했어요.");
    // 서버가 받은 단어장 이벤트라도 새 단어장을 못 받았으면 아웃박스에 남긴다. 지우면 옛 단어장이 그대로 보여
    // 뺀 단어가 되살아난 것처럼 보인다. 다시 보내도 서버가 처리한 id 는 무시하므로(vocab_event) 안전하다.
    // 확인받은 것은 보낸 그 객체뿐이다. 전송 중 같은 id 로 새로 기록된 것(더 긴 FOCUS 등)은 남긴다.
    const sentNow = new Set<StudyEvent>(batch);
    const keep = (p: StudyEvent) => !(done.has(p.id) && sentNow.has(p)) || (!vocab.ok && p.type.startsWith("VOCAB_"));
    // 아웃박스 정리와 스냅샷 교체를 한 동기 구간에서 함께 한다. 전송 중 새로 쌓인 이벤트는 남는다.
    this.pending = this.pending.filter(keep);
    // 같은 id 의 새 버전(더 긴 FOCUS 등)이 남아 있으면 그 id 는 아직 끝나지 않았다. 다른 창에 지우라고 알리지 않는다.
    const cleared = batch.filter((p) => done.has(p.id) && !this.pending.some((q) => q.id === p.id)).map((p) => p.id);
    cleared.forEach((id) => this.sent.add(id));
    if (this.sent.size > 5000) this.sent = new Set(Array.from(this.sent).slice(-2000));
    this.persist();
    if (cleared.length > 0) this.channel?.postMessage({ sent: cleared });
    this.apply(mine, res.dashboard, sessions, vocab);
    // 단어장을 못 받았으면 실패로 끝내 백오프 뒤에 다시 시도한다(바로 되풀이하지 않는다).
    if (!vocab.ok) throw new Error("단어장을 받지 못했어요.");
    if (this.pending.length > 0) this.again = true;
  }

  /** 레슨 구성이 바뀌었을 때만 세션을 다시 받는다. */
  private async sessionsFor(d: Dashboard, force: boolean): Promise<{ today: SessionData | null; review: SessionData | null }> {
    const prev = this.snapshot;
    const todayChanged = force || !prev?.today || prev.dashboard.lesson_day !== d.lesson_day || prev.dashboard.today !== d.today;
    const reviewChanged =
      force || !prev?.review || prev.dashboard.today !== d.today || prev.dashboard.review_progress.days.join() !== d.review_progress.days.join();
    const [today, review] = await Promise.all([
      todayChanged ? (d.lesson_day > 0 ? api.session("today") : Promise.resolve(null)) : Promise.resolve(prev?.today ?? null),
      reviewChanged ? (d.lesson_day > 0 ? api.session("review") : Promise.resolve(null)) : Promise.resolve(prev?.review ?? null),
    ]);
    return { today, review };
  }

  private apply(
    mine: number,
    dashboard: Dashboard,
    sessions: { today: SessionData | null; review: SessionData | null },
    vocab: VocabFetch,
  ): void {
    if (mine < this.appliedSeq) {
      this.publish(); // 더 새 응답을 이미 반영했다. 아웃박스 변화만 화면에 반영한다.
      return;
    }
    this.appliedSeq = mine;
    this.snapshot = { dashboard, today: sessions.today, review: sessions.review, vocab: vocab.entries, vocabGenerator: vocab.generator };
    snapshotCache.save(this.snapshot);
    this.publish();
  }

  /** 아웃박스를 저장한다. 다른 창이 그사이 쌓은 미전송 기록을 먼저 합쳐서 덮어쓰지 않게 한다. */
  private persist(): void {
    this.mergeOthers();
    this.sync.saveFailed = !outbox.save(this.pending);
  }

  /**
   * 서버가 요청 전체를 거절했다(형식·서버 오류). 같은 묶음을 끝없이 다시 보내면 뒤의 기록이 모두 막히므로,
   * 연속으로 거절되면 하나씩 보내고, 하나만 보내도 계속 거절되는 기록은 따로 보관한 뒤 다음 기록으로 넘어간다.
   */
  private onBatchRejected(batch: StudyEvent[]): void {
    this.serverRejections += 1;
    if (batch.length !== 1 || this.serverRejections < HOLD_AFTER) return;
    const [bad] = batch;
    if (!outbox.hold(bad)) return; // 보관하지 못하면 버리지 않고 계속 시도한다
    this.pending = this.pending.filter((p) => p !== bad);
    this.sent.add(bad.id);
    this.persist();
    this.sync.held += 1;
    this.serverRejections = SINGLE_AFTER;
  }

  /**
   * 저장소에 있는데 내 목록에 없는 이벤트(다른 창이 쌓은 것)를 합친다. 내가 이미 보낸 것은 되살리지 않는다.
   * 모든 이벤트는 서버에서 멱등이라, 두 창이 같은 이벤트를 각각 보내도 한 번만 반영된다. 합친 것이 있으면 true.
   */
  private mergeOthers(): boolean {
    const mine = new Map(this.pending.map((p) => [p.id, p]));
    let changed = false;
    for (const e of outbox.load()) {
      if (this.sent.has(e.id)) continue;
      const own = mine.get(e.id);
      if (!own) {
        this.pending.push(e);
        changed = true;
      } else if (e.type === "FOCUS" && own.type === "FOCUS" && e.seconds > own.seconds) {
        // 같은 집중의 더 긴 버전이 저장소에 있으면 그것을 남긴다(짧은 것으로 덮어쓰지 않게).
        this.pending = this.pending.map((p) => (p === own ? e : p));
        changed = true;
      }
    }
    if (changed) this.pending.sort((a, b) => a.occurred_at - b.occurred_at);
    return changed;
  }

  /**
   * 같은 날·문장·세션·경로의 VIEW 를 다시 쌓지 않는다(서버 UNIQUE 와 같은 키). 확신할 수 없으면 보존한다:
   * 새 이벤트가 스냅샷의 오늘 구간 밖이면(날짜가 바뀜) 검사하지 않고, REVIEW 는 서버 집합이 경로를 구분하지 않으므로 pending 만 본다.
   */
  private isDuplicateView(e: Extract<StudyEvent, { type: "VIEW" }>): boolean {
    const d = this.snapshot?.dashboard;
    if (!d || e.occurred_at < d.valid_from || e.occurred_at >= d.valid_until) return false;
    const inWindow = (p: StudyEvent) => p.occurred_at >= d.valid_from && p.occurred_at < d.valid_until;
    if (
      this.pending.some(
        (p) => p.type === "VIEW" && p.sentence_id === e.sentence_id && p.session === e.session && p.via === e.via && inWindow(p),
      )
    ) {
      return true;
    }
    if (e.session === "REVIEW") return false;
    const seen = e.via === "AUDIO" ? d.today_progress.audio_seen_ids : d.today_progress.card_seen_ids;
    return seen.includes(e.sentence_id);
  }

  private schedule(ms: number): void {
    this.clearTimer();
    this.timer = window.setTimeout(() => {
      this.timer = null;
      void this.refresh();
    }, ms);
  }

  private clearTimer(): void {
    if (this.timer !== null) {
      window.clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private publish(): void {
    this.sync = { ...this.sync, pending: this.pending.length };
    if (this.snapshot) {
      this.state = { kind: "ready", view: applyPending(this.snapshot, this.pending, serverNow()), sync: this.sync };
    } else if (this.state.kind === "ready") {
      this.state = { kind: "booting" };
    }
    this.listeners.forEach((l) => l());
  }
}

export const studyStore = new StudyStore();

export function isOfflineError(err: unknown): boolean {
  return !(err instanceof ApiError);
}
