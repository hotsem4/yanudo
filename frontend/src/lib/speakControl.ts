import { useSyncExternalStore } from "react";
import type { PlayItem } from "../audio/turn";
import { audioEngine } from "../audio/useEngine";

/**
 * 오늘의 10문장 따라 말하기(SpeakCheck)와 하단 독을 잇는다. 카드가 보이는 동안 독 가운데 버튼이 마이크가 되어
 * 엄지 자리에서 말하기를 시작·멈춘다. 카드(SpeakCheck)가 마이크를 소유하고, 독은 상태를 읽고 toggle 만 부른다.
 * 따라 말하기에서 문장을 들려줄 때도 여기(hear)를 거친다. 미리듣기는 엔진의 재생 상태를 바꾸지 않아서,
 * 들려주는 동안 안내 띠가 "듣는 중"을 보이려면 따로 알아야 한다.
 *
 * 먼저 듣기: 아직 끝까지 들은 적 없는 문장은 독 가운데 버튼이 스피커다(듣기 전에 실수로 녹음하지 않게).
 * 끝까지 들으면(들려준 시간이 지나면) 그 문장을 "들음"으로 기억하고 가운데 버튼이 마이크로 바뀐다.
 */
export interface SpeakControlState {
  /** 지금 카드가 따라 말하기를 받는 중인가(오늘 탭, 잠기지 않음). */
  active: boolean;
  listening: boolean;
  /** 이 문장을 막 통과했다(다음 문장으로 넘어가는 중). */
  justPassed: boolean;
  /** 카드가 가려졌다(시트·모닝 락). */
  disabled: boolean;
  /** 마이크를 쓸 수 없다(받아 적기). 듣기는 된다. */
  micBlocked: boolean;
  /** 이 문장을 아직 끝까지 들은 적이 없다: 가운데 버튼이 스피커다. */
  needsHearing: boolean;
  /** 따라 말할 문장을 들려주는 중(길이로 어림한다). */
  hearing: boolean;
  /** 지금 들려주는 소리의 어림 길이(ms). 가운데 버튼의 링이 이만큼 걸려 찬다. */
  hearMs: number;
  /** 들려주기를 새로 시작할 때마다 바뀐다(링 애니메이션을 처음부터 다시 그리게). */
  hearKey: number;
}

type CardState = Pick<SpeakControlState, "listening" | "justPassed" | "disabled" | "micBlocked" | "needsHearing">;

const IDLE: SpeakControlState = {
  active: false,
  listening: false,
  justPassed: false,
  disabled: false,
  micBlocked: false,
  needsHearing: false,
  hearing: false,
  hearMs: 0,
  hearKey: 0,
};
/** 소리 길이 어림에 더하는 여유(불러오기·끝 무음). 음성 파일이 없으면 기기 음성 길이를 모르므로 최소값을 쓴다. */
const HEAR_SLACK_MS = 500;
const HEAR_MIN_MS = 1500;

let state: SpeakControlState = IDLE;
let card: CardState & { active: boolean } = IDLE;
let hearing = false;
let hearMs = 0;
let hearKey = 0;
let hearTimer: number | null = null;
let owner: symbol | null = null;
let onToggle: (() => void) | null = null;
let onCancel: (() => void) | null = null;
/** 이번 실행에서 끝까지 들은 문장(새로고침하면 처음부터). */
const heardIds = new Set<number>();
const listeners = new Set<() => void>();

function publish(): void {
  const next: SpeakControlState = { ...card, hearing, hearMs, hearKey };
  const keys = Object.keys(next) as (keyof SpeakControlState)[];
  if (keys.every((k) => next[k] === state[k])) return;
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
        card = IDLE;
        // "들려주는 중"은 여기서 끄지 않는다: 문장을 옮기면 새 문장을 들려주기 시작한 뒤에 옛 카드가 놓이므로,
        // 여기서 끄면 방금 시작한 표시가 꺼진다. 탭 전환처럼 소리가 멈출 때는 stopHearing 을 따로 부른다.
        publish();
      },
    };
  },
  toggle(): void {
    onToggle?.();
  },
  /** 이 문장을 끝까지 들었는가(먼저 듣기). */
  isHeard(id: number): boolean {
    return heardIds.has(id);
  },
  /** 따라 말할 문장을 한 번 들려준다(현재 배속). 듣고 있던 마이크는 채점 없이 끈다(정답 소리가 인식되지 않게). */
  hear(item: PlayItem): void {
    if (card.listening) onCancel?.();
    void audioEngine.audition(item);
    stopHearing();
    const ms = item.duration_ms > 0 ? item.duration_ms / audioEngine.getSnapshot().rate + HEAR_SLACK_MS : 0;
    hearing = true;
    hearMs = Math.max(HEAR_MIN_MS, ms);
    hearKey += 1;
    hearTimer = window.setTimeout(() => {
      hearTimer = null;
      hearing = false;
      heardIds.add(item.id);
      publish();
    }, hearMs);
    publish();
  },
  /** 들려주던 소리를 모두 멈춘다(마이크를 켜기 전, 앱 소리가 인식되지 않게). */
  hush(): void {
    audioEngine.hush();
    stopHearing();
    publish();
  },
  /** 탭을 바꾸는 등 엔진이 소리를 멈췄을 때 "들려주는 중"만 끈다(끝까지 들은 것으로 치지 않는다). */
  stopHearing(): void {
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
