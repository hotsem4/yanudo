import { useCallback, useEffect, useState } from "react";
import { serverNow } from "../api/client";
import { studyStore } from "../state/store";

export const FOCUS_SECONDS = 25 * 60;
const KEY = "habit66.focus.v1";

function readStart(): number | null {
  try {
    const v = Number(window.localStorage.getItem(KEY));
    return Number.isFinite(v) && v > 0 ? v : null;
  } catch {
    return null;
  }
}

function writeStart(v: number | null): void {
  try {
    if (v === null) window.localStorage.removeItem(KEY);
    else window.localStorage.setItem(KEY, String(v));
  } catch {
    /* 무시 */
  }
}

export interface FocusTimer {
  running: boolean;
  elapsed: number;
  /** 25분까지 남은 초. 0 이하면 초과 집중. */
  remaining: number;
  overtime: number;
  start: () => void;
  finish: () => number;
}

/**
 * 25분 모래시계(SPEC F-06): 카운트다운 → 0 이 되면 알람 없이 +00:01 부터 조용히 카운트업.
 * 시작 시각을 localStorage 에 두어 새로고침해도 이어진다. 끝내면 FOCUS 이벤트(id = focus:<시작 시각>)로 기록한다.
 */
export function useFocusTimer(): FocusTimer {
  const [startedAt, setStartedAt] = useState<number | null>(readStart);
  const [now, setNow] = useState(serverNow);

  useEffect(() => {
    if (startedAt === null) return;
    setNow(serverNow());
    const t = window.setInterval(() => setNow(serverNow()), 1000);
    return () => window.clearInterval(t);
  }, [startedAt]);

  const start = useCallback(() => {
    const t = serverNow();
    writeStart(t);
    setStartedAt(t);
    setNow(t);
  }, []);

  const finish = useCallback((): number => {
    if (startedAt === null) return 0;
    const seconds = Math.min(21600, Math.floor((serverNow() - startedAt) / 1000));
    if (seconds >= 60) {
      studyStore.record({ type: "FOCUS", id: `focus:${startedAt}`, started_at: startedAt, seconds });
    }
    writeStart(null);
    setStartedAt(null);
    return seconds;
  }, [startedAt]);

  const elapsed = startedAt === null ? 0 : Math.max(0, Math.floor((now - startedAt) / 1000));
  return {
    running: startedAt !== null,
    elapsed,
    remaining: FOCUS_SECONDS - elapsed,
    overtime: Math.max(0, elapsed - FOCUS_SECONDS),
    start,
    finish,
  };
}
