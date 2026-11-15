import type { Speaker } from "../api/types";

export type PlayMode = "full" | "shadowing" | "roleplay";
export const PLAY_MODES: readonly PlayMode[] = ["full", "shadowing", "roleplay"];

export function isPlayMode(value: string | null): value is PlayMode {
  return value !== null && (PLAY_MODES as readonly string[]).includes(value);
}

export const PLAYBACK_RATES = [0.85, 1, 1.15] as const;
export type PlaybackRate = (typeof PLAYBACK_RATES)[number];

/** 엔진이 필요로 하는 최소 모양. 와이어 Sentence 가 구조적으로 만족한다. */
export interface PlayItem {
  readonly id: number;
  readonly day: number;
  readonly speaker: Speaker;
  readonly en_text: string;
  readonly audio_url: string | null;
  readonly duration_ms: number;
}

/** 한 문장 차례의 계획. 3모드 규칙 전부가 여기 있다(SPEC F-03, docs/architecture.md 표). */
export interface Turn {
  speak: boolean;
  hideEn: boolean;
  /** 사용자 차례 무음 대기. 배속과 무관하게 duration_ms × 1.3. */
  holdMs: number;
  /** 문장 사이 간격. */
  gapMs: number;
  /** AUDIO 열람을 보고하는 시점. */
  heardAfter: "speak" | "hold";
}

const GAP_MS = 600;
const YOUR_TURN_FACTOR = 1.3;

export function planTurn(item: PlayItem, mode: PlayMode): Turn {
  const yourTurn = Math.round(Math.max(item.duration_ms, 800) * YOUR_TURN_FACTOR);
  switch (mode) {
    case "shadowing":
      return { speak: true, hideEn: false, holdMs: yourTurn, gapMs: 0, heardAfter: "speak" };
    case "roleplay":
      return item.speaker === "B"
        ? { speak: false, hideEn: true, holdMs: yourTurn, gapMs: 0, heardAfter: "hold" }
        : { speak: true, hideEn: false, holdMs: 0, gapMs: GAP_MS, heardAfter: "speak" };
    case "full":
    default:
      return { speak: true, hideEn: false, holdMs: 0, gapMs: GAP_MS, heardAfter: "speak" };
  }
}
