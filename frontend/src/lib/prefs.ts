import { isPlayMode, PLAYBACK_RATES, type PlaybackRate, type PlayMode } from "../audio/turn";

export type RevealFilter = "all" | "hint" | "korean";
export const REVEAL_FILTERS: readonly RevealFilter[] = ["all", "hint", "korean"];

interface Prefs {
  filter: RevealFilter;
  mode: PlayMode;
  rate: PlaybackRate;
}

const KEY = "habit66.prefs.v1";
const DEFAULTS: Prefs = { filter: "all", mode: "full", rate: 1 };

function read(): Prefs {
  try {
    const raw = window.localStorage.getItem(KEY);
    const parsed = raw ? (JSON.parse(raw) as Partial<Prefs>) : {};
    return {
      filter: REVEAL_FILTERS.includes(parsed.filter as RevealFilter) ? (parsed.filter as RevealFilter) : DEFAULTS.filter,
      mode: isPlayMode(parsed.mode ?? null) ? (parsed.mode as PlayMode) : DEFAULTS.mode,
      rate: (PLAYBACK_RATES as readonly number[]).includes(parsed.rate as number) ? (parsed.rate as PlaybackRate) : DEFAULTS.rate,
    };
  } catch {
    return { ...DEFAULTS };
  }
}

let current = read();

/** 화면 설정(가리기 필터, 재생 모드, 배속). 기기별 편의 설정이라 localStorage 에만 둔다. */
export const prefs = {
  get<K extends keyof Prefs>(key: K): Prefs[K] {
    return current[key];
  },
  set<K extends keyof Prefs>(key: K, value: Prefs[K]): void {
    current = { ...current, [key]: value };
    try {
      window.localStorage.setItem(KEY, JSON.stringify(current));
    } catch {
      /* 저장 불가여도 이번 실행에는 유지 */
    }
  },
};
