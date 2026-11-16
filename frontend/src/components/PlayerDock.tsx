import { AnimatePresence, motion } from "framer-motion";
import { Check, Lock, Mic, Pause, Play, SkipBack, SkipForward, Square, Volume2 } from "lucide-react";
import { useEffect, useRef } from "react";
import { audioEngine, useEngine } from "../audio/useEngine";
import { speakControl, useSpeakControl } from "../lib/speakControl";

const R = 31;
const C = 2 * Math.PI * R;

/**
 * 하단 플로팅 필 독: 이전 · 56px 재생 버튼(무음 대기 링) · 다음. 이 셋 외에는 넣지 않는다(clean-blue-ui).
 * 링은 무음 트랙의 currentTime(미디어 시계)을 rAF 로 읽어 DOM 속성만 바꾼다.
 * 오늘의 10문장에서 따라 말하기를 받는 동안에는 이 문장 듣기 · 마이크 · 다음 문장이 된다(학습이 먼저).
 */
export function PlayerDock() {
  const speak = useSpeakControl();
  const playing = useEngine().playing;
  // 연속 재생(복습에서 이어짐·알림 자동 재생 등) 중에는 재생 독을 보여 멈출 수 있게 한다.
  if (speak.active && !playing) return <SpeakDock listening={speak.listening} justPassed={speak.justPassed} disabled={speak.disabled} hearing={speak.hearing} />;
  return <PlayDock />;
}

function SpeakDock({ listening, justPassed, disabled, hearing }: { listening: boolean; justPassed: boolean; disabled: boolean; hearing: boolean }) {
  const eng = useEngine();
  const item = eng.items[eng.cursor];
  const atEnd = eng.cursor >= eng.items.length - 1;
  const nextItem = eng.items[eng.cursor + 1];
  const side = "press flex h-12 w-12 items-center justify-center rounded-full text-surface/80 disabled:opacity-35 [@media(hover:hover)]:hover:bg-surface/10";
  return (
    <div className="dock-rail bottom-dock z-30">
      <nav aria-label="따라 말하기 컨트롤" className="pointer-events-auto flex h-[72px] w-[264px] items-center justify-between rounded-full bg-ink px-2 shadow-dock">
        <button
          type="button"
          aria-label="이 문장 듣기"
          aria-pressed={hearing}
          disabled={!item || listening}
          onClick={() => item && speakControl.hear(item)}
          className={`${side} ${hearing ? "bg-surface/15 text-surface" : ""}`}
        >
          <Volume2 size={22} strokeWidth={2} aria-hidden />
        </button>
        <div className="relative flex h-[66px] w-[66px] items-center justify-center">
          {listening && <span aria-hidden className="absolute inset-0 rounded-full bg-royal/30 motion-safe:animate-ping" />}
          <button
            type="button"
            aria-label={listening ? "말하기 멈추기" : "따라 말하기"}
            aria-pressed={listening}
            disabled={disabled || justPassed}
            onClick={() => speakControl.toggle()}
            className="press relative flex h-14 w-14 items-center justify-center rounded-full bg-royal text-surface shadow-glow disabled:bg-royal/45 disabled:shadow-none"
          >
            {justPassed ? (
              <Check size={26} strokeWidth={2.5} aria-hidden />
            ) : listening ? (
              <Square size={20} strokeWidth={2} fill="currentColor" aria-hidden />
            ) : (
              <Mic size={26} strokeWidth={2} aria-hidden />
            )}
          </button>
        </div>
        <button
          type="button"
          aria-label="다음 문장 듣기"
          disabled={!item || atEnd || listening}
          onClick={() => {
            // 따라 말하기는 듣고 말하는 흐름이라 다음 문장으로 가면 그 문장을 들려준다.
            audioEngine.go("next");
            if (nextItem) speakControl.hear(nextItem);
          }}
          className={side}
        >
          <SkipForward size={22} strokeWidth={2} aria-hidden />
        </button>
      </nav>
    </div>
  );
}

function PlayDock() {
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
        <button type="button" aria-label="이전 문장" disabled={empty || locked || atStart} onClick={() => audioEngine.go("prev")} className={side}>
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

        <button type="button" aria-label="다음 문장" disabled={empty || locked || atEnd} onClick={() => audioEngine.go("next")} className={side}>
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
