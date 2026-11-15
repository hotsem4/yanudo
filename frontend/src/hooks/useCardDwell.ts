import { useEffect } from "react";
import type { Session } from "../api/types";
import { studyStore } from "../state/store";

const DWELL_MS = 1200;

/**
 * 카드를 실제로 1.2초 이상 보면 CARD 열람을 기록한다(SPEC 0장). 스토어가 중복을 거른다.
 * 화면이 숨겨지면 타이머를 멈추고, 돌아오면 처음부터 다시 잰다. 시트·오버레이에 가려진 동안은 enabled=false.
 */
export function useCardDwell(sentenceId: number | null, session: Session | null, enabled: boolean): void {
  useEffect(() => {
    if (!enabled || sentenceId === null || session === null) return;
    let timer: number | null = null;
    const arm = () => {
      if (timer !== null) window.clearTimeout(timer);
      timer = null;
      if (document.visibilityState !== "visible") return;
      timer = window.setTimeout(() => {
        timer = null;
        studyStore.record({ type: "VIEW", sentence_id: sentenceId, session, via: "CARD" });
      }, DWELL_MS);
    };
    arm();
    document.addEventListener("visibilitychange", arm);
    return () => {
      document.removeEventListener("visibilitychange", arm);
      if (timer !== null) window.clearTimeout(timer);
    };
  }, [sentenceId, session, enabled]);
}
