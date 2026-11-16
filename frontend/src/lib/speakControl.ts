import { useSyncExternalStore } from "react";

/**
 * 오늘의 10문장 따라 말하기(SpeakCheck)와 하단 독을 잇는다. 카드가 보이는 동안 독 가운데 버튼이 마이크가 되어
 * 엄지 자리에서 말하기를 시작·멈춘다. 카드(SpeakCheck)가 마이크를 소유하고, 독은 상태를 읽고 toggle 만 부른다.
 */
export interface SpeakControlState {
  /** 지금 카드가 따라 말하기를 받는 중인가(오늘 탭, 잠기지 않음, 받아 적기가 아님). */
  active: boolean;
  listening: boolean;
  /** 이 문장을 막 통과했다(다음 문장으로 넘어가는 중). */
  justPassed: boolean;
  disabled: boolean;
}

const IDLE: SpeakControlState = { active: false, listening: false, justPassed: false, disabled: false };

let state: SpeakControlState = IDLE;
let owner: symbol | null = null;
let onToggle: (() => void) | null = null;
const listeners = new Set<() => void>();

function publish(next: SpeakControlState): void {
  if (
    next.active === state.active &&
    next.listening === state.listening &&
    next.justPassed === state.justPassed &&
    next.disabled === state.disabled
  ) {
    return;
  }
  state = next;
  listeners.forEach((l) => l());
}

export const speakControl = {
  /** 카드가 마이크를 맡는다. 돌려준 함수로 놓는다(다른 카드가 이미 맡았으면 그 뒤에 온 것이 이긴다). */
  claim(toggle: () => void): { update: (s: Omit<SpeakControlState, "active"> & { active?: boolean }) => void; release: () => void } {
    const id = Symbol("speak");
    owner = id;
    onToggle = toggle;
    return {
      update: (s) => {
        if (owner !== id) return;
        publish({ active: s.active ?? true, listening: s.listening, justPassed: s.justPassed, disabled: s.disabled });
      },
      release: () => {
        if (owner !== id) return;
        owner = null;
        onToggle = null;
        publish(IDLE);
      },
    };
  },
  toggle(): void {
    onToggle?.();
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
