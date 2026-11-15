import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { Check, Mic, Square, Volume2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import type { Sentence } from "../api/types";
import { audioEngine } from "../audio/useEngine";
import { useSpeechMatch } from "../hooks/useSpeechMatch";
import type { MascotState } from "../lib/mascot";
import { matchWords, PASS_RATIO } from "../lib/wordMatch";
import { studyStore } from "../state/store";
import { showToast } from "../lib/toast";
import { Mascot } from "./Mascot";

interface Props {
  open: boolean;
  sentences: Sentence[];
  onDone: () => void;
  mascot: MascotState | null;
}

/** 모닝 3문장 보이스 락(SPEC F-04-2). 히어로 그라데이션 전면 오버레이. */
export function MorningLockOverlay({ open, sentences, onDone, mascot }: Props) {
  useEffect(() => {
    if (!open) return;
    const meta = document.querySelector('meta[name="theme-color"]');
    const prev = meta?.getAttribute("content") ?? null;
    const royal = getComputedStyle(document.documentElement).getPropertyValue("--royal").trim();
    if (royal) meta?.setAttribute("content", `rgb(${royal})`);
    return () => {
      if (prev) meta?.setAttribute("content", prev);
    };
  }, [open]);

  return (
    <AnimatePresence>
      {open && sentences.length > 0 && (
        <motion.div
          role="dialog"
          aria-modal="true"
          aria-labelledby="morning-title"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0, transition: { duration: 0.25 } }}
          className="fixed inset-0 z-50 overflow-y-auto bg-hero text-surface"
        >
          <LockBody sentences={sentences.slice(0, 3)} onDone={onDone} mascot={mascot} />
        </motion.div>
      )}
    </AnimatePresence>
  );
}

function LockBody({ sentences, onDone, mascot }: { sentences: Sentence[]; onDone: () => void; mascot: MascotState | null }) {
  const reduce = useReducedMotion();
  const speech = useSpeechMatch();
  const [index, setIndex] = useState(0);
  const [cleared, setCleared] = useState<boolean[]>(() => sentences.map(() => false));
  const sentence = sentences[index];

  // 대안마다 따로 채점해 가장 높은 것을 쓴다.
  const match = useMemo(() => {
    const candidates = speech.alternatives.length > 0 ? speech.alternatives : [speech.transcript];
    return candidates
      .map((text) => matchWords(sentence.en_text, text))
      .reduce((best, m) => (m.ratio > best.ratio ? m : best), matchWords(sentence.en_text, ""));
  }, [sentence.en_text, speech.alternatives, speech.transcript]);
  const hasAttempt = speech.transcript.length > 0;
  const passed = cleared[index] || match.ratio >= PASS_RATIO;
  const { stop, reset } = speech;

  // 1) 통과 확정: 기준을 넘으면 듣기를 멈추고 이 문장을 통과로 표시한다.
  useEffect(() => {
    if (!hasAttempt || cleared[index] || match.ratio < PASS_RATIO) return;
    stop();
    setCleared((c) => c.map((v, i) => (i === index ? true : v)));
  }, [match.ratio, hasAttempt, index, cleared, stop]);

  // 2) 이동: 통과로 확정된 뒤 잠시 보여 주고 다음 문장으로. 인식 결과가 늦게 바뀌어도 취소되지 않는다.
  useEffect(() => {
    if (!cleared[index]) return;
    const t = window.setTimeout(() => {
      if (index + 1 < sentences.length) {
        setIndex(index + 1);
        reset();
      } else {
        studyStore.record({ type: "MORNING_LOCK", result: "CLEARED" });
        showToast("모닝 스타터 완료! 오늘도 시작했어요", "success");
        onDone();
      }
    }, 900);
    return () => window.clearTimeout(t);
  }, [cleared, index, sentences.length, reset, onDone]);

  const skip = () => {
    speech.reset();
    studyStore.record({ type: "MORNING_LOCK", result: "SKIPPED" });
    onDone();
  };

  const percent = Math.round(match.ratio * 100);
  return (
    <div className="mx-auto flex min-h-dvh max-w-md flex-col px-6 pb-10 pt-safe unfolded:max-w-lg">
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[13px] font-medium text-surface/80">모닝 스타터</p>
          <h1 id="morning-title" className="mt-1 text-balance text-[22px] font-bold leading-snug">
            오늘의 핵심 3문장 소리 내어 읽기
          </h1>
        </div>
        {/* 아침 인사. 세 문장을 모두 통과하면 웃는다. 파란 바탕이라 흰 원 위에 세운다. */}
        {mascot && (
          <span className="flex h-[104px] w-[104px] shrink-0 items-center justify-center rounded-full bg-surface shadow-lift">
            <Mascot stage={mascot.stage} mood={cleared.every(Boolean) ? "done" : mascot.mood === "sleepy" ? "sleepy" : "wait"} size={96} />
          </span>
        )}
      </div>
      <ol className="mt-4 flex gap-1.5" aria-label={`${sentences.length}문장 중 ${cleared.filter(Boolean).length}문장 통과`}>
        {sentences.map((s, i) => (
          <li key={s.id} className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface/25">
            <span
              className="block h-full origin-left rounded-full bg-surface transition-transform duration-300 ease-[var(--ease-out)]"
              style={{ transform: `scaleX(${cleared[i] ? 1 : 0})` }}
            />
          </li>
        ))}
      </ol>

      <div className="mt-6 rounded-3xl bg-surface/15 p-6 backdrop-blur">
        <p className="text-[12.5px] font-semibold tabular-nums text-surface/75">
          {index + 1} / {sentences.length}
        </p>
        <p lang="en" translate="no" className="mt-2 break-words text-pretty text-[24px] font-bold leading-[1.35] tracking-tight">
          {hasAttempt
            ? match.hits.map((h, i) => (
                <span key={i} className={h.hit ? "text-surface" : "text-surface/45"}>
                  {i > 0 ? " " : ""}
                  {h.word}
                </span>
              ))
            : sentence.en_text}
        </p>
        <p className="mt-2 text-pretty text-[14px] leading-relaxed text-surface/80">{sentence.ko_natural}</p>
      </div>

      <div className="flex flex-1 flex-col items-center justify-center gap-4 py-8">
        {speech.supported ? (
          <>
            <div className="relative flex h-[120px] w-[120px] items-center justify-center">
              {speech.listening && !reduce && (
                <>
                  <motion.span
                    aria-hidden
                    className="absolute inset-4 rounded-full bg-surface/20"
                    animate={{ scale: [1, 1.45], opacity: [0.55, 0] }}
                    transition={{ duration: 1.4, repeat: Infinity, ease: "easeOut" }}
                  />
                  <motion.span
                    aria-hidden
                    className="absolute inset-4 rounded-full bg-surface/20"
                    animate={{ scale: [1, 1.45], opacity: [0.55, 0] }}
                    transition={{ duration: 1.4, repeat: Infinity, ease: "easeOut", delay: 0.7 }}
                  />
                </>
              )}
              <button
                type="button"
                onClick={() => (speech.listening ? speech.stop() : speech.start())}
                aria-label={speech.listening ? "듣기 멈추기" : "눌러서 문장 읽기"}
                className="press relative flex h-[88px] w-[88px] items-center justify-center rounded-full bg-surface text-royal shadow-lift"
              >
                {passed ? (
                  <Check size={34} strokeWidth={2.25} aria-hidden />
                ) : speech.listening ? (
                  <Square size={26} strokeWidth={2} fill="currentColor" aria-hidden />
                ) : (
                  <Mic size={34} strokeWidth={2} aria-hidden />
                )}
              </button>
            </div>
            <p className="min-h-[22px] text-[15px] font-semibold tabular-nums" aria-live="polite">
              {passed ? "통과했어요" : speech.listening ? "듣고 있어요" : hasAttempt ? `일치 ${percent}% (60% 이상이면 통과)` : "마이크를 누르고 읽어 보세요"}
            </p>
            {speech.error && <p className="text-pretty text-center text-[13px] text-surface/85">{speech.error}</p>}
          </>
        ) : (
          <p className="text-pretty text-center text-[14px] leading-relaxed text-surface/85">
            이 브라우저에서는 음성 인식을 쓸 수 없어요. Chrome에서 마이크 권한을 허용하거나 아래에서 건너뛰세요.
          </p>
        )}
        <button
          type="button"
          onClick={() => void audioEngine.audition(sentence)}
          className="press flex h-10 items-center gap-1.5 rounded-full bg-surface/15 px-4 text-[13.5px] font-semibold"
        >
          <Volume2 size={16} strokeWidth={2} aria-hidden />
          문장 듣기
        </button>
      </div>

      <button type="button" onClick={skip} className="mx-auto h-11 px-4 text-[13px] text-surface/70 underline underline-offset-4">
        긴급 건너뛰기
      </button>
    </div>
  );
}
