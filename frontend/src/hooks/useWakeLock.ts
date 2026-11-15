import { useEffect } from "react";

/** 학습 중(재생·대기·타이머) 화면 꺼짐 방지. 화면 복귀 때 다시 요청한다. */
export function useWakeLock(active: boolean): void {
  useEffect(() => {
    if (!active || !("wakeLock" in navigator)) return;
    let sentinel: WakeLockSentinel | null = null;
    let cancelled = false;
    const request = async () => {
      try {
        const s = await navigator.wakeLock.request("screen");
        if (cancelled) void s.release();
        else sentinel = s;
      } catch {
        /* 배터리 절약 모드 등으로 거절될 수 있다 */
      }
    };
    const onVisible = () => {
      if (document.visibilityState === "visible" && (!sentinel || sentinel.released)) void request();
    };
    void request();
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      cancelled = true;
      document.removeEventListener("visibilitychange", onVisible);
      if (sentinel && !sentinel.released) void sentinel.release().catch(() => undefined);
    };
  }, [active]);
}
