import type { Dashboard } from "../api/types";

/** 66일은 끝이 아니라 한 라운드. 서버가 준 라운드를 쓰고, 예전 버전이 저장한 스냅샷처럼 없으면 current_day 로 맞춘다. */
export interface RoundInfo {
  round: number;
  roundDay: number;
  /** 한 라운드의 길이(66). */
  length: number;
}

export function roundOf(d: Dashboard): RoundInfo {
  const length = d.total_days > 0 ? d.total_days : 66;
  const day = Math.max(1, d.current_day);
  return {
    round: d.round ?? Math.floor((day - 1) / length) + 1,
    roundDay: d.round_day ?? ((day - 1) % length) + 1,
    length,
  };
}

/** 헤더 위 라벨. 1라운드는 "66일 습관", 그 뒤는 "2라운드 5/66"(리셋이 아니라 이어지는 것으로 읽히게). */
export function roundLabel(info: RoundInfo): string {
  return info.round <= 1 ? `${info.length}일 습관` : `${info.round}라운드 ${info.roundDay}/${info.length}`;
}
