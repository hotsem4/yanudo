import type { MakeupEffect } from "../api/types";

/** "2026-11-18" → "11/18". */
function short(iso: string): string {
  const [, m, d] = iso.split("-");
  return `${Number(m)}/${Number(d)}`;
}

/**
 * 보충을 끝냈을 때 실제로 생기는 일을 한 줄로(SPEC F-05). 같은 "보충"이라도 상황마다 효과가 달라서
 * (연속이 이어짐, 프리즈만 돌아옴, 앞날부터 해야 함) 서버가 계산한 effect 로 말한다.
 * effect 가 없으면(이전 서버) 기한만 알린다.
 */
export function makeupEffectText(effect: MakeupEffect | undefined, streakIfDone: number | undefined, deadline: string | null): string {
  const by = deadline ? `${short(deadline)}까지 하면 ` : "";
  switch (effect) {
    case "STREAK":
      return `${by}연속 ${streakIfDone ?? 1}일로 이어져요`;
    case "WITH_OTHERS":
      return `남은 보충까지 끝내면 연속 ${streakIfDone ?? 1}일로 이어져요`;
    case "FREEZE":
      return `${by}이날 쓴 프리즈를 돌려받아요`;
    case "AFTER_EARLIER":
      return "앞날 보충을 먼저 끝내야 연속에 반영돼요";
    case "NONE":
      return "끝내면 이 Day 가 열려요";
    default:
      return deadline ? `${short(deadline)}까지 하면 연속 유지` : "끝내면 이 Day 가 열려요";
  }
}

/** 보충을 다 끝낸 뒤 한마디(전송 후 반영). */
export function makeupDoneText(effect: MakeupEffect | undefined, streakIfDone: number | undefined): string {
  switch (effect) {
    case "STREAK":
      return ` 전송되면 연속 ${streakIfDone ?? 1}일로 이어져요.`;
    case "FREEZE":
      return " 전송되면 이날 쓴 프리즈를 돌려받아요.";
    case "WITH_OTHERS":
      return " 남은 보충까지 끝내면 연속이 이어져요.";
    case "AFTER_EARLIER":
      return " 앞날 보충까지 끝내면 연속에 반영돼요.";
    default:
      return " 이제 이 Day 도 자유롭게 연습할 수 있어요.";
  }
}
