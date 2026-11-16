import { useSyncExternalStore } from "react";
import type { PlayItem } from "../audio/turn";
import { audioEngine } from "../audio/useEngine";

/**
 * 오늘의 10문장 따라 말하기(SpeakCheck)와 하단 독을 잇는다. 카드가 보이는 동안 독 가운데 버튼이 마이크가 되어
 * 엄지 자리에서 말하기를 시작·멈춘다. 카드(SpeakCheck)가 마이크를 소유하고, 독은 상태를 읽고 toggle 만 부른다.
 * 따라 말하기에서 문장을 들려줄 때도 여기(hear)를 거친다. 미리듣기는 엔진의 재생 상태를 바꾸지 않아서,
 * 들려주는 동안 안내 띠가 "듣는 중"을 보이려면 따로 알아야 한다.
 */
export interface SpeakControlState {
  /** 지금 카드가 따라 말하기를 받는 중인가(오늘 탭, 잠기지 않음). */
  active: boolean;
  listening: boolean;
  /** 이 문장을 막 통과했다(다음 문장으로 넘어가는 중). */
  justPassed: boolean;
  disabled: boolean;
  /** 따라 말할 문장을 들려주는 중(길이로 어림한다). */
  hearing: boolean;
}

type CardState = Pick<SpeakControlState, "listening" | "justPassed" | "disabled">;

const IDLE_CARD: SpeakControlState = { active: false, listening: false, justPassed: false, disabled: false, hearing: false };
/** 소리 길이 어림에 더하는 여유(불러오기·끝 무음). 음성 파일이 없으면 기기 음성 길이를 모르므로 최소값을 쓴다. */
const HEAR_SLACK_MS = 500;
const HEAR_MIN_MS = 1500;

let state: SpeakControlState = IDLE_CARD;
let card: Omit<SpeakControlState, "hearing"> = IDLE_CARD;
let hearing = false;
let hearTimer: number | null = null;
let owner: symbol | null = null;
let onToggle: (() => void) | null = null;
let onCancel: (() => void) | null = null;
const listeners = new Set<() => void>();

function publish(): void {
  const next: SpeakControlState = { ...card, hearing };
  if (
    next.active === state.active &&
    next.listening === state.listening &&
    next.justPassed === state.justPassed &&
    next.disabled === state.disabled &&
    next.hearing === state.hearing
  ) {
    return;
  }
  state = next;
  listeners.forEach((l) => l());
}

function stopHearing(): void {
  if (hearTimer !== null) window.clearTimeout(hearTimer);
  hearTimer = null;
  hearing = false;
}

export const speakControl = {
  /** 카드가 마이크를 맡는다. 돌려준 함수로 놓는다(다른 카드가 이미 맡았으면 그 뒤에 온 것이 이긴다). */
  claim(toggle: () => void, cancel: () => void): { update: (s: CardState) => void; release: () => void } {
    const id = Symbol("speak");
    owner = id;
    onToggle = toggle;
    onCancel = cancel;
    return {
      update: (s) => {
        if (owner !== id) return;
        card = { active: true, ...s };
        publish();
      },
      release: () => {
        if (owner !== id) return;
        owner = null;
        onToggle = null;
        onCancel = null;
        card = IDLE_CARD;
        // 카드가 사라지면(탭 전환 등) 엔진이 소리를 멈추므로 "들려주는 중"도 끝낸다.
        stopHearing();
        publish();
      },
    };
  },
  toggle(): void {
    onToggle?.();
  },
  /** 따라 말할 문장을 한 번 들려준다(현재 배속). 듣고 있던 마이크는 채점 없이 끈다(정답 소리가 인식되지 않게). */
  hear(item: PlayItem): void {
    if (card.listening) onCancel?.();
    void audioEngine.audition(item);
    stopHearing();
    const ms = item.duration_ms > 0 ? item.duration_ms / audioEngine.getSnapshot().rate + HEAR_SLACK_MS : 0;
    hearing = true;
    hearTimer = window.setTimeout(() => {
      hearTimer = null;
      hearing = false;
      publish();
    }, Math.max(HEAR_MIN_MS, ms));
    publish();
  },
  /** 들려주던 소리를 모두 멈춘다(마이크를 켜기 전, 앱 소리가 인식되지 않게). */
  hush(): void {
    audioEngine.hush();
    stopHearing();
    publish();
  },
};

export function useSpeakControl(): SpeakControlState {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => state,
    () => state,
  );
}
