import type { Session } from "../api/types";
import { ClipStore, type PreloadStatus } from "./clips";
import { silenceLoopUrl, silenceUrl } from "./silence";
import { PLAYBACK_RATES, planTurn, type PlayItem, type PlayMode, type PlaybackRate, type Turn } from "./turn";

/**
 * AudioEngine — 재생 상태 기계의 단일 소유자(docs/architecture.md "오디오").
 *
 * 불변식
 *  - <audio> 요소는 엔진 안에 하나. 잠금 화면·버즈 버튼(Media Session)이 이 요소에 붙는다.
 *  - 커서는 엔진만 갖는다. 카드 스와이프·독 버튼·미디어 키가 모두 go() 로 들어온다.
 *  - 대기·간격의 시계는 무음 WAV 의 `ended` 다(화면이 꺼져도 밀리지 않고, 일시정지하면 남은 시간이 보존된다).
 *  - go/setMode/load(다른 key)/pause 는 실행 토큰을 바꾼다. 모든 비동기 완료는 토큰이 바뀌었으면 조용히 그만둔다.
 *  - 이벤트(heard, queueEnd)는 상태 전이를 끝낸 뒤 마이크로태스크로 보낸다(재진입 금지).
 *  - 엔진은 게이트를 모른다. 큐의 playable 비트만 본다.
 */

export interface PlayQueue {
  readonly key: string;
  readonly session: Session;
  readonly items: readonly PlayItem[];
  readonly playable: boolean;
}

export type EnginePhase = "idle" | "loading" | "speaking" | "holding" | "gap" | "blocked" | "finished";

export interface EngineSnapshot {
  readonly queueKey: string | null;
  readonly session: Session | null;
  readonly items: readonly PlayItem[];
  readonly cursor: number;
  readonly item: PlayItem | null;
  readonly mode: PlayMode;
  readonly rate: PlaybackRate;
  /** 사용자 의도. 일시정지된 대기(phase idle + paused true)도 있다. */
  readonly playing: boolean;
  readonly paused: boolean;
  readonly playable: boolean;
  readonly phase: EnginePhase;
  /** 사용자 차례 대기일 때만. 진행률은 holdProgress() 로 읽는다(60fps 리렌더 방지). */
  readonly hold: { readonly totalMs: number } | null;
  readonly hideEn: boolean;
  readonly source: "file" | "speech" | null;
  readonly preload: PreloadStatus;
}

export type EngineEvent =
  | { readonly type: "heard"; readonly item: PlayItem; readonly session: Session }
  | { readonly type: "queueEnd"; readonly queueKey: string; readonly session: Session };

type Step = "speak" | "hold" | "gap" | "advance";
type Outcome = "done" | "stale" | "blocked";
type Loaded = { kind: "speak"; itemId: number } | { kind: "silence"; ms: number } | null;

export class AudioEngine {
  readonly clips = new ClipStore();
  private readonly el: HTMLAudioElement;
  private queue: PlayQueue | null = null;
  private cursor = 0;
  private mode: PlayMode;
  private rate: PlaybackRate;
  private playing = false;
  private phase: EnginePhase = "idle";
  private token = 0;
  private cancels = new Set<() => void>();
  private step: Step | null = null;
  private turn: Turn | null = null;
  private heardReported = false;
  private loaded: Loaded = null;
  private source: "file" | "speech" | null = null;
  private holdTotal: number | null = null;
  private expectPause = 0;
  private snap: EngineSnapshot;
  private listeners = new Set<() => void>();
  private eventListeners = new Set<(e: EngineEvent) => void>();

  constructor(init: { mode: PlayMode; rate: PlaybackRate }) {
    this.mode = init.mode;
    this.rate = init.rate;
    this.el = new Audio();
    this.el.preload = "auto";
    this.el.addEventListener("pause", () => this.onElementPause());
    this.clips.subscribe(() => this.publish());
    this.installMediaSession();
    this.snap = this.buildSnapshot();
  }

  // ── 구독 ────────────────────────────────────────────────────────

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): EngineSnapshot => this.snap;

  on(listener: (e: EngineEvent) => void): () => void {
    this.eventListeners.add(listener);
    return () => this.eventListeners.delete(listener);
  }

  // ── 명령 ────────────────────────────────────────────────────────

  /**
   * 큐 교체. 같은 key 면 항목·playable 만 바꾸고 재생 흐름은 건드리지 않는다.
   * 다른 key 면 멈추고 커서를 startAt 으로. 프리로드는 여기서 시작한다.
   */
  load(queue: PlayQueue, startAt = 0): void {
    if (this.queue && this.queue.key === queue.key) {
      this.queue = queue;
      if (!queue.playable && (this.playing || this.step !== null)) this.stopTurn("idle");
      this.publish();
      return;
    }
    this.bump();
    this.pauseElement();
    this.queue = queue;
    this.cursor = clamp(startAt, 0, Math.max(0, queue.items.length - 1));
    this.playing = false;
    this.resetTurn();
    this.phase = "idle";
    this.clips.prefetch(queue.items.flatMap((i) => (i.audio_url ? [i.audio_url] : [])));
    this.updateMetadata();
    this.publish();
  }

  /** 사용자 제스처 안에서 부르면 blocked 를 푼다. finished 에서 부르면 처음부터. */
  async play(): Promise<void> {
    const q = this.queue;
    if (!q || q.items.length === 0 || !q.playable) return;
    if (this.phase === "finished") {
      this.cursor = 0;
      this.resetTurn();
    }
    const resume = this.step !== null;
    this.playing = true;
    const token = this.bump();
    this.publish();
    await this.run(token, resume);
  }

  pause(): void {
    if (!this.playing && this.phase !== "blocked") return;
    this.stopTurn("idle");
  }

  toggle(): void {
    if (this.playing) this.pause();
    else void this.play();
  }

  /** 커서 이동의 유일한 입구. 재생 중이면 새 문장을 처음부터, 아니면 멈춘 채 이동. */
  go(to: number | "next" | "prev"): void {
    const q = this.queue;
    if (!q || q.items.length === 0) return;
    const target = to === "next" ? this.cursor + 1 : to === "prev" ? this.cursor - 1 : to;
    const next = clamp(target, 0, q.items.length - 1);
    if (next === this.cursor && typeof to !== "number") return;
    const wasPlaying = this.playing;
    const token = this.bump();
    this.pauseElement();
    this.cursor = next;
    this.resetTurn();
    this.phase = wasPlaying ? "loading" : "idle";
    this.updateMetadata();
    this.publish();
    if (wasPlaying) void this.run(token, false);
  }

  setMode(mode: PlayMode): void {
    if (mode === this.mode) return;
    this.mode = mode;
    if (this.step !== null || this.playing) this.go(this.cursor);
    else this.publish();
  }

  cycleRate(): void {
    const i = PLAYBACK_RATES.indexOf(this.rate);
    this.rate = PLAYBACK_RATES[(i + 1) % PLAYBACK_RATES.length];
    if (this.source === "file" && this.phase === "speaking") this.el.playbackRate = this.rate;
    this.publish();
  }

  /** 큐·커서·보고와 무관하게 한 문장 들려주기(모닝 락 "문장 듣기"). */
  async audition(item: PlayItem): Promise<void> {
    if (this.playing) this.pause();
    const token = this.bump();
    this.resetTurn();
    let url: string | null = null;
    try {
      url = item.audio_url ? await this.clips.objectUrl(item.audio_url) : null;
    } catch {
      url = null;
    }
    if (token !== this.token) return;
    if (url) {
      this.el.loop = false;
      this.el.src = url;
      this.el.playbackRate = 1;
      try {
        await this.el.play();
      } catch {
        /* 무시 */
      }
    } else if ("speechSynthesis" in window) {
      speechSynthesis.cancel();
      speechSynthesis.speak(utterance(item, 1));
    }
  }

  /** 대기 중 0..1. 링 컴포넌트가 rAF 로 읽는다. */
  holdProgress(): number {
    if (this.loaded?.kind !== "silence" || (this.step !== "hold" && this.step !== "gap")) return 0;
    const d = this.el.duration;
    return Number.isFinite(d) && d > 0 ? Math.min(1, this.el.currentTime / d) : 0;
  }

  // ── 실행 루프 ───────────────────────────────────────────────────

  private async run(token: number, resume: boolean): Promise<void> {
    let resuming = resume;
    while (token === this.token) {
      const q = this.queue;
      const item = q?.items[this.cursor];
      if (!q || !item) return this.finish();

      if (this.step === null) {
        this.turn = planTurn(item, this.mode);
        this.step = this.turn.speak ? "speak" : "hold";
        this.heardReported = false;
        resuming = false;
        this.updateMetadata();
      }
      const turn = this.turn as Turn;

      if (this.step === "speak") {
        const r = await this.speak(item, token, resuming);
        resuming = false;
        if (r !== "done") return this.settle(r);
        if (turn.heardAfter === "speak") this.reportHeard(item, q.session);
        this.step = turn.holdMs > 0 ? "hold" : turn.gapMs > 0 ? "gap" : "advance";
      }
      if (this.step === "hold") {
        const r = await this.silence(turn.holdMs, "holding", token, resuming);
        resuming = false;
        if (r !== "done") return this.settle(r);
        if (turn.heardAfter === "hold") this.reportHeard(item, q.session);
        this.step = turn.gapMs > 0 ? "gap" : "advance";
      }
      if (this.step === "gap") {
        const r = await this.silence(turn.gapMs, "gap", token, resuming);
        resuming = false;
        if (r !== "done") return this.settle(r);
        this.step = "advance";
      }
      if (token !== this.token) return;
      this.resetTurn();
      if (this.cursor + 1 < q.items.length) {
        this.cursor += 1;
        this.publish();
        continue;
      }
      return this.finish();
    }
  }

  private async speak(item: PlayItem, token: number, resume: boolean): Promise<Outcome> {
    this.phase = "loading";
    this.publish();
    let url: string | null = null;
    if (item.audio_url) {
      try {
        url = await this.clips.objectUrl(item.audio_url);
      } catch {
        url = null; // 오프라인이거나 아직 없는 클립 → 기기 음성으로
      }
    }
    if (token !== this.token) return "stale";

    if (url) {
      this.source = "file";
      this.phase = "speaking";
      this.holdTotal = null;
      this.publish();
      const sameClip = resume && this.loaded?.kind === "speak" && this.loaded.itemId === item.id;
      if (!sameClip) {
        this.el.loop = false;
        this.el.src = url;
        this.loaded = { kind: "speak", itemId: item.id };
      }
      this.el.playbackRate = this.rate;
      const ended = this.waitEnded();
      const p = await this.playElement(token);
      if (p === "blocked" || p === "stale") return p;
      if (p === "ok") {
        const r = await ended;
        if (r === "cancelled" || token !== this.token) return "stale";
        if (r === "ended") return "done";
      }
      // 디코딩 오류 → 기기 음성으로 대신 읽는다.
    }
    return this.speakWithSynth(item, token);
  }

  private async speakWithSynth(item: PlayItem, token: number): Promise<Outcome> {
    if (!("speechSynthesis" in window)) return this.silence(item.duration_ms, "gap", token, false);
    this.source = "speech";
    this.phase = "speaking";
    this.publish();
    // 기기 음성 동안 무음을 반복 재생해 Media Session 을 유지한다.
    this.el.loop = true;
    this.el.src = silenceLoopUrl();
    this.loaded = null;
    const p = await this.playElement(token);
    if (p === "blocked" || p === "stale") return p;
    const result = await new Promise<Outcome>((resolve) => {
      let settled = false;
      const finish = (r: Outcome) => {
        if (settled) return;
        settled = true;
        this.cancels.delete(cancel);
        window.clearTimeout(guard);
        resolve(r);
      };
      const cancel = () => finish("stale");
      this.cancels.add(cancel);
      const u = utterance(item, this.rate);
      u.onend = () => finish("done");
      u.onerror = () => finish(token === this.token ? "done" : "stale");
      // 일부 기기는 onend 를 빠뜨린다. 넉넉한 상한을 둔다.
      const guard = window.setTimeout(() => finish("done"), Math.max(4000, item.duration_ms * 2.5));
      speechSynthesis.cancel();
      speechSynthesis.speak(u);
    });
    // 취소된 실행은 공유 요소를 건드리지 않는다(새 실행의 오디오를 멈추지 않게).
    if (token !== this.token) return "stale";
    this.el.loop = false;
    this.pauseElement();
    return result;
  }

  private async silence(ms: number, phase: "holding" | "gap", token: number, resume: boolean): Promise<Outcome> {
    this.phase = phase;
    this.holdTotal = phase === "holding" ? ms : null;
    this.publish();
    const sameSilence = resume && this.loaded?.kind === "silence" && this.loaded.ms === ms;
    if (!sameSilence) {
      this.el.loop = false;
      this.el.src = silenceUrl(ms);
      this.loaded = { kind: "silence", ms };
    }
    this.el.playbackRate = 1;
    const ended = this.waitEnded();
    const p = await this.playElement(token);
    if (p === "blocked" || p === "stale") return p;
    if (p === "error") {
      if (!(await this.sleep(ms, token))) return "stale";
    } else if ((await ended) === "cancelled") {
      return "stale";
    }
    return token === this.token ? "done" : "stale";
  }

  // ── 보조 ────────────────────────────────────────────────────────

  private waitEnded(): Promise<"ended" | "error" | "cancelled"> {
    return new Promise((resolve) => {
      const done = (r: "ended" | "error" | "cancelled") => {
        this.el.removeEventListener("ended", onEnded);
        this.el.removeEventListener("error", onError);
        this.cancels.delete(cancel);
        resolve(r);
      };
      const onEnded = () => done("ended");
      const onError = () => done("error");
      const cancel = () => done("cancelled");
      this.el.addEventListener("ended", onEnded);
      this.el.addEventListener("error", onError);
      this.cancels.add(cancel);
    });
  }

  private async playElement(token: number): Promise<"ok" | "blocked" | "stale" | "error"> {
    try {
      await this.el.play();
      return token === this.token ? "ok" : "stale";
    } catch (err) {
      if (token !== this.token) return "stale";
      const name = err instanceof DOMException ? err.name : "";
      if (name === "NotAllowedError") return "blocked";
      if (name === "AbortError") return "stale";
      return "error";
    }
  }

  private sleep(ms: number, token: number): Promise<boolean> {
    return new Promise((resolve) => {
      const t = window.setTimeout(() => {
        this.cancels.delete(cancel);
        resolve(token === this.token);
      }, ms);
      const cancel = () => {
        window.clearTimeout(t);
        resolve(false);
      };
      this.cancels.add(cancel);
    });
  }

  private settle(r: Outcome): void {
    if (r === "blocked") {
      this.playing = false;
      this.phase = "blocked";
      this.publish();
    }
  }

  private finish(): void {
    const q = this.queue;
    this.playing = false;
    this.resetTurn();
    this.phase = q && q.items.length > 0 ? "finished" : "idle";
    this.pauseElement();
    this.publish();
    if (q) this.emit({ type: "queueEnd", queueKey: q.key, session: q.session });
  }

  private reportHeard(item: PlayItem, session: Session): void {
    if (this.heardReported) return;
    this.heardReported = true;
    this.emit({ type: "heard", item, session });
  }

  /** 멈추되 턴 단계는 보존한다(다시 재생하면 같은 단계부터). 기기 음성은 문장 처음부터. */
  private stopTurn(phase: EnginePhase): void {
    this.playing = false;
    this.bump();
    this.pauseElement();
    if (this.source === "speech") this.loaded = null;
    this.phase = phase;
    this.publish();
  }

  private resetTurn(): void {
    this.step = null;
    this.turn = null;
    this.heardReported = false;
    this.loaded = null;
    this.source = null;
    this.holdTotal = null;
  }

  private bump(): number {
    this.token += 1;
    const pending = Array.from(this.cancels);
    this.cancels.clear();
    pending.forEach((c) => c());
    if ("speechSynthesis" in window && (speechSynthesis.speaking || speechSynthesis.pending)) speechSynthesis.cancel();
    return this.token;
  }

  private pauseElement(): void {
    if (!this.el.paused) {
      this.expectPause += 1;
      this.el.pause();
    }
  }

  /** 블루투스 끊김·오디오 포커스 상실처럼 브라우저가 멈춘 경우 엔진 상태를 맞춘다. */
  private onElementPause(): void {
    if (this.expectPause > 0) {
      this.expectPause -= 1;
      return;
    }
    if (this.el.ended || !this.playing) return;
    this.stopTurn("idle");
  }

  private emit(e: EngineEvent): void {
    queueMicrotask(() => this.eventListeners.forEach((l) => l(e)));
  }

  private installMediaSession(): void {
    if (!("mediaSession" in navigator)) return;
    const set = (action: MediaSessionAction, handler: MediaSessionActionHandler) => {
      try {
        navigator.mediaSession.setActionHandler(action, handler);
      } catch {
        /* 지원하지 않는 동작 */
      }
    };
    set("play", () => void this.play());
    set("pause", () => this.pause());
    set("stop", () => this.pause());
    set("previoustrack", () => this.go("prev"));
    set("nexttrack", () => this.go("next"));
  }

  private updateMetadata(): void {
    const item = this.queue?.items[this.cursor];
    if (!item || !("mediaSession" in navigator) || typeof MediaMetadata === "undefined") return;
    navigator.mediaSession.metadata = new MediaMetadata({
      title: item.en_text,
      artist: `Day ${item.day} ${item.speaker === "A" ? "Andrew" : "Ava"}`,
      album: "66 Habit English",
      artwork: [
        { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png" },
        { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png" },
      ],
    });
  }

  private buildSnapshot(): EngineSnapshot {
    const q = this.queue;
    const item = q?.items[this.cursor] ?? null;
    return {
      queueKey: q?.key ?? null,
      session: q?.session ?? null,
      items: q?.items ?? [],
      cursor: this.cursor,
      item,
      mode: this.mode,
      rate: this.rate,
      playing: this.playing,
      paused: !this.playing && this.step !== null && this.phase !== "blocked",
      playable: q?.playable ?? false,
      phase: this.phase,
      hold: this.holdTotal !== null ? { totalMs: this.holdTotal } : null,
      hideEn: !!this.turn?.hideEn && this.step !== null && this.step !== "advance",
      source: this.source,
      preload: this.clips.getStatus(),
    };
  }

  private publish(): void {
    this.snap = this.buildSnapshot();
    if ("mediaSession" in navigator) {
      try {
        navigator.mediaSession.playbackState = this.playing ? "playing" : this.step !== null ? "paused" : "none";
      } catch {
        /* 무시 */
      }
    }
    this.listeners.forEach((l) => l());
  }
}

function clamp(n: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, n));
}

let cachedVoices: SpeechSynthesisVoice[] | null = null;

function utterance(item: PlayItem, rate: number): SpeechSynthesisUtterance {
  const u = new SpeechSynthesisUtterance(item.en_text);
  u.lang = "en-US";
  u.rate = Math.max(0.5, rate * 0.95);
  if (!cachedVoices || cachedVoices.length === 0) cachedVoices = speechSynthesis.getVoices();
  const english = cachedVoices.filter((v) => v.lang.toLowerCase().startsWith("en-us"));
  if (english.length > 0) u.voice = english[item.speaker === "B" && english.length > 1 ? 1 : 0];
  return u;
}
