import { api, ApiError, sendEventsKeepalive, serverNow } from "../api/client";
import type { Dashboard, NewEvent, SessionData, StudyEvent, VocabEntry } from "../api/types";
import { applyPending, type DayView } from "./overlay";
import { newEventId, outbox, snapshotCache, type Snapshot } from "./outbox";

export interface SyncState {
  pending: number;
  online: boolean;
  syncing: boolean;
  lastError: string | null;
}

export type StudyState =
  | { kind: "booting" }
  | { kind: "error"; message: string }
  | { kind: "ready"; view: DayView; sync: SyncState };

const BATCH = 500;
const DEBOUNCE_MS = 1500;

/**
 * 서버 스냅샷 + 아웃박스의 단일 소유자.
 * 공개 표면: subscribe / getState / start / record / refresh.
 * 숨기는 것: localStorage 영속화, 전송 순서·재시도, keepalive, 오래된 응답 버리기, 세션 재조회 시점.
 */
export class StudyStore {
  private snapshot: Snapshot | null = null;
  private pending: StudyEvent[] = [];
  private state: StudyState = { kind: "booting" };
  private listeners = new Set<() => void>();
  private sync: SyncState = { pending: 0, online: true, syncing: false, lastError: null };
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
    outbox.save(this.pending);
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

  /** 단어장 받기. 실패하면(오프라인, 서버가 아직 단어장을 모름) 이전 값을 그대로 쓴다. */
  private fetchVocab(): Promise<VocabEntry[] | null> {
    return api
      .vocab()
      .then((v) => v.entries)
      .catch(() => this.snapshot?.vocab ?? null);
  }

  /** 뜻·예문을 만드는 중인 단어가 있을 때 그것만 다시 받는다. */
  private async refreshVocab(): Promise<void> {
    if (!this.snapshot || this.inFlight) return;
    const vocab = await this.fetchVocab();
    if (!this.snapshot) return;
    this.snapshot = { ...this.snapshot, vocab };
    snapshotCache.save(this.snapshot);
    this.publish();
  }

  private async flush(): Promise<void> {
    const mine = ++this.seq;
    const batch = this.pending.slice(0, BATCH);
    const res = await api.events(batch);
    const done = new Set([...res.accepted, ...res.rejected.map((r) => r.id)]);
    // 암기 상태는 세션 응답에만 있으므로, MARK 를 보냈으면 세션을 다시 받아 오버레이가 빠진 뒤에도 값이 유지되게 한다.
    const sentMark = batch.some((e) => e.type === "MARK" && done.has(e.id));
    const sentVocab = batch.some((e) => e.type.startsWith("VOCAB_") && done.has(e.id));
    const prev = this.snapshot;
    const [sessions, vocab] = await Promise.all([
      this.sessionsFor(res.dashboard, sentMark).catch(() => ({
        today: prev?.today ?? null,
        review: prev?.review ?? null,
      })),
      sentVocab ? this.fetchVocab() : Promise.resolve(prev?.vocab ?? null),
    ]);
    // 아웃박스 정리와 스냅샷 교체를 한 동기 구간에서 함께 한다. 전송 중 새로 쌓인 이벤트는 남는다.
    this.pending = this.pending.filter((p) => !done.has(p.id));
    outbox.save(this.pending);
    this.apply(mine, res.dashboard, sessions, vocab);
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
    vocab: VocabEntry[] | null,
  ): void {
    if (mine < this.appliedSeq) {
      this.publish(); // 더 새 응답을 이미 반영했다. 아웃박스 변화만 화면에 반영한다.
      return;
    }
    this.appliedSeq = mine;
    this.snapshot = { dashboard, today: sessions.today, review: sessions.review, vocab };
    snapshotCache.save(this.snapshot);
    this.publish();
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
