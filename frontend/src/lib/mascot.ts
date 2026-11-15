import type { DayView } from "../state/overlay";

/**
 * 성장형 캐릭터 "포롱"의 단계와 기분. 순수 함수이고 서버가 준 값만 읽는다(날짜 계산 없음).
 *
 * 단계 = current_day 가 지난 이정표 수. 하루를 놓쳐도 되돌아가지 않는다.
 * 이정표는 이탈이 많은 첫 주에 두 번(Day 3, 7), 그 뒤로는 3주·6주 경계에 둔다.
 */
export type MascotStage = 1 | 2 | 3 | 4 | 5 | 6;
export type MascotMood = "wait" | "done" | "sleepy" | "party";

export interface MascotState {
  stage: MascotStage;
  mood: MascotMood;
}

export const MASCOT_NAME = "포롱";
export const STAGE_DAYS: readonly number[] = [1, 3, 7, 21, 42, 66];
export const STAGE_NAMES: readonly string[] = ["알", "금 간 알", "아기 새", "솜털 새", "날개 편 새", "완주한 파랑새"];

const MOOD_NAMES: Record<MascotMood, string> = {
  wait: "오늘 문장을 기다리는 중",
  done: "오늘 달성",
  sleepy: "졸고 있어요",
  party: "66일 완주",
};

export function mascotStage(currentDay: number, programComplete: boolean): MascotStage {
  if (programComplete) return 6;
  let stage = 1;
  STAGE_DAYS.forEach((day, i) => {
    if (currentDay >= day) stage = i + 1;
  });
  return stage as MascotStage;
}

export function mascotMood(input: { programComplete: boolean; achievedToday: boolean; streak: number; currentDay: number }): MascotMood {
  if (input.programComplete) return "party";
  if (input.achievedToday) return "done";
  // 스트릭이 끊긴 뒤 오늘 아직일 때. 혼내는 표정이 아니라 깨워 달라는 표정이다.
  if (input.streak === 0 && input.currentDay > 1) return "sleepy";
  return "wait";
}

export function mascotOf(view: DayView): MascotState {
  const d = view.dashboard;
  return {
    stage: mascotStage(d.current_day, d.program_complete),
    mood: mascotMood({
      programComplete: d.program_complete,
      achievedToday: view.streak.achievedToday,
      streak: view.streak.current,
      currentDay: d.current_day,
    }),
  };
}

/** 다음에 자라는 날. 마지막 단계면 null. */
export function nextMilestone(currentDay: number, stage: MascotStage): { day: number; daysLeft: number; name: string } | null {
  if (stage >= 6) return null;
  const day = STAGE_DAYS[stage];
  return { day, daysLeft: Math.max(0, day - currentDay), name: STAGE_NAMES[stage] };
}

export function stageName(stage: MascotStage): string {
  return STAGE_NAMES[stage - 1];
}

/** "알이에요" / "아기 새예요": 받침에 맞춘 서술격 조사. */
export function stageIs(stage: MascotStage): string {
  const name = stageName(stage);
  const last = name.charCodeAt(name.length - 1) - 0xac00;
  const hasFinal = last >= 0 && last <= 11171 && last % 28 !== 0;
  return `${name}${hasFinal ? "이에요" : "예요"}`;
}

export function mascotLabel({ stage, mood }: MascotState): string {
  return `${MASCOT_NAME}, ${stageName(stage)} 단계, ${MOOD_NAMES[mood]}`;
}

/* ── 이미 본 단계 기억(성장 축하를 한 번만) ─────────────────── */

const SEEN_KEY = "habit66.mascot.stage.v1";

/** 마지막으로 본 단계. 없거나 읽을 수 없으면 null. */
export function seenStage(): MascotStage | null {
  try {
    const n = Number(window.localStorage.getItem(SEEN_KEY));
    return n >= 1 && n <= 6 ? (n as MascotStage) : null;
  } catch {
    return null;
  }
}

/**
 * 지금 단계를 기억하고, 전에 본 단계보다 자랐으면 true.
 * 처음 실행(기록 없음)과 저장소를 쓸 수 없는 경우는 false: 축하를 건너뛸지언정 되풀이하지는 않는다.
 */
export function noteStage(stage: MascotStage): boolean {
  try {
    const before = seenStage();
    if (before !== null && before >= stage) return false;
    window.localStorage.setItem(SEEN_KEY, String(stage));
    return before !== null;
  } catch {
    return false;
  }
}
