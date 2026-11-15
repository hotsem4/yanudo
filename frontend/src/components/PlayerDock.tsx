import { AnimatePresence, motion } from "framer-motion";
import { Lock, Pause, Play, SkipBack, SkipForward } from "lucide-react";
import { useEffect, useRef } from "react";
import { audioEngine, useEngine } from "../audio/useEngine";

const R = 31;
const C = 2 * Math.PI * R;

/**
 * 하단 플로팅 필 독: 이전 · 56px 재생 버튼(무음 대기 링) · 다음. 이 셋 외에는 넣지 않는다(clean-blue-ui).
 * 링은 무음 트랙의 currentTime(미디어 시계)을 rAF 로 읽어 DOM 속성만 바꾼다.
 */
export function PlayerDock() {
  const eng = useEngine();
  const ringRef = useRef<SVGCircleElement>(null);
  const holding = eng.phase === "holding" || (eng.paused && eng.hold !== null);

  useEffect(() => {
    const ring = ringRef.current;
    if (!ring) return;
    if (!holding) {
      ring.style.strokeDashoffset = String(C);
      return;
    }
    let raf = 0;
    const tick = () => {
      // 남은 시간이 줄어드는 링: 꽉 찬 상태에서 시작해 0 으로
      ring.style.strokeDashoffset = String(C * audioEngine.holdProgress());
      raf = requestAnimationFrame(tick);
    };
    tick();
    return () => cancelAnimationFrame(raf);
  }, [holding]);

  const empty = eng.items.length === 0;
  const atStart = eng.cursor <= 0;
  const atEnd = eng.cursor >= eng.items.length - 1;
  const locked = !empty && !eng.playable;
  const label = locked ? "잠겨 있어요. 어제 복습을 먼저 마치세요" : eng.playing ? "일시정지" : eng.paused ? "이어서 재생" : "재생";

  const side = "press flex h-12 w-12 items-center justify-center rounded-full text-surface/80 disabled:opacity-35 [@media(hover:hover)]:hover:bg-surface/10";

  return (
    <div className="dock-rail bottom-dock z-30">
      <nav aria-label="재생 컨트롤" className="pointer-events-auto flex h-[72px] w-[264px] items-center justify-between rounded-full bg-ink px-2 shadow-dock">
        <button type="button" aria-label="이전 문장" disabled={empty || atStart} onClick={() => audioEngine.go("prev")} className={side}>
          <SkipBack size={22} strokeWidth={2} aria-hidden />
        </button>

        <div className="relative flex h-[66px] w-[66px] items-center justify-center">
          <svg aria-hidden className="pointer-events-none absolute inset-0 -rotate-90" viewBox="0 0 66 66">
            <circle cx="33" cy="33" r={R} fill="none" strokeWidth="3" className="stroke-surface/15" />
            <circle
              ref={ringRef}
              cx="33"
              cy="33"
              r={R}
              fill="none"
              strokeWidth="3"
              strokeLinecap="round"
              className="stroke-surface"
              style={{ strokeDasharray: C, strokeDashoffset: C }}
            />
          </svg>
          <button
            type="button"
            aria-label={label}
            disabled={empty || locked}
            onClick={() => audioEngine.toggle()}
            className="press flex h-14 w-14 items-center justify-center rounded-full bg-royal text-surface shadow-glow disabled:bg-royal/45 disabled:shadow-none"
          >
            <AnimatePresence initial={false} mode="popLayout">
              <motion.span
                key={locked ? "lock" : eng.playing ? "pause" : "play"}
                initial={{ scale: 0.25, opacity: 0, filter: "blur(4px)" }}
                animate={{ scale: 1, opacity: 1, filter: "blur(0px)" }}
                exit={{ scale: 0.25, opacity: 0, filter: "blur(4px)" }}
                transition={{ type: "spring", duration: 0.3, bounce: 0 }}
                className="flex"
              >
                {locked ? (
                  <Lock size={22} strokeWidth={2} aria-hidden />
                ) : eng.playing ? (
                  <Pause size={24} strokeWidth={2} fill="currentColor" aria-hidden />
                ) : (
                  <Play size={24} strokeWidth={2} fill="currentColor" aria-hidden className="translate-x-[1.5px]" />
                )}
              </motion.span>
            </AnimatePresence>
          </button>
        </div>

        <button type="button" aria-label="다음 문장" disabled={empty || atEnd} onClick={() => audioEngine.go("next")} className={side}>
          <SkipForward size={22} strokeWidth={2} aria-hidden />
        </button>
      </nav>
    </div>
  );
}

/** 브라우저가 소리 재생을 막았을 때(자동 실행) 한 번 탭으로 시작. */
export function BlockedPrompt() {
  const eng = useEngine();
  return (
    <AnimatePresence>
      {eng.phase === "blocked" && (
        <motion.div
          initial={{ opacity: 0, y: 12, scale: 0.95 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          exit={{ opacity: 0, y: 8 }}
          transition={{ duration: 0.22, ease: [0.23, 1, 0.32, 1] }}
          className="dock-rail bottom-[calc(max(16px,env(safe-area-inset-bottom))+88px)] z-30"
        >
          <button
            type="button"
            onClick={() => void audioEngine.play()}
            className="press pointer-events-auto flex h-12 items-center gap-2 rounded-full bg-surface px-5 text-[14px] font-semibold text-royal shadow-lift"
          >
            <Play size={18} strokeWidth={2} fill="currentColor" aria-hidden />
            탭해서 재생 시작
          </button>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
