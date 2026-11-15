import { AnimatePresence, motion, useReducedMotion } from "framer-motion";
import { Check, Keyboard, Mic, Square, Volume2 } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import type { Sentence } from "../api/types";
import { audioEngine } from "../audio/useEngine";
import { useSpeechMatch } from "../hooks/useSpeechMatch";
import type { MascotState } from "../lib/mascot";
import { matchWords, PASS_RATIO, type MatchResult } from "../lib/wordMatch";
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
  const panel = useRef<HTMLDivElement>(null);
  const shown = open && sentences.length > 0;

  // aria-modal 만으로는 포커스가 갇히지 않는다. 락 뒤 화면을 inert 로 막고 포커스를 락으로 옮긴다. 닫히면 되돌린다.
  useEffect(() => {
    const el = panel.current;
    if (!shown || !el?.parentElement) return;
    const before = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const others = [...el.parentElement.children].filter((c): c is HTMLElement => c !== el && c instanceof HTMLElement && !c.inert);
    others.forEach((c) => (c.inert = true));
    el.focus({ preventScroll: true });
    return () => {
      others.forEach((c) => (c.inert = false));
      before?.focus({ preventScroll: true });
    };
  }, [shown]);

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
      {shown && (
        <motion.div
          ref={panel}
          tabIndex={-1}
          role="dialog"
          aria-modal="true"
          aria-labelledby="morning-title"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0, transition: { duration: 0.25 } }}
          className="fixed inset-0 z-50 overflow-y-auto bg-hero text-surface focus:outline-none"
        >
          <LockBody sentences={sentences.slice(0, 3)} onDone={onDone} mascot={mascot} />
        </motion.div>
      )}
    </AnimatePresence>
  );
}

/** 마이크를 아예 쓸 수 없는 오류. 이때는 받아 적기로 바꾸고 건너뛰기를 바로 보여 준다. */
const MIC_BLOCKED = new Set(["not-allowed", "service-not-allowed", "audio-capture", "network"]);
/** 한 문장에서 이만큼 못 넘기면 건너뛰기를 보여 준다. */
const MISSES_BEFORE_SKIP = 3;

type Mode = "voice" | "type";

function LockBody({ sentences, onDone, mascot }: { sentences: Sentence[]; onDone: () => void; mascot: MascotState | null }) {
  const reduce = useReducedMotion();
  const speech = useSpeechMatch();
  const [index, setIndex] = useState(0);
  const [cleared, setCleared] = useState<boolean[]>(() => sentences.map(() => false));
  const [mode, setMode] = useState<Mode>(speech.supported ? "voice" : "type");
  const [typed, setTyped] = useState("");
  const [typedResult, setTypedResult] = useState<MatchResult | null>(null);
  const [misses, setMisses] = useState(0);
  const [confirmSkip, setConfirmSkip] = useState(false);
  // 마이크를 못 쓴다는 사실은 이 락 동안 유지한다. 다음 문장으로 넘어갈 때 reset() 이 오류 코드를 지워도 건너뛰기가 사라지지 않게.
  const [micDenied, setMicDenied] = useState(false);
  const sentence = sentences[index];

  // 대안마다 따로 채점해 가장 높은 것을 쓴다.
  const match = useMemo(() => {
    const candidates = speech.alternatives.length > 0 ? speech.alternatives : [speech.transcript];
    return candidates
      .map((text) => matchWords(sentence.en_text, text))
      .reduce((best, m) => (m.ratio > best.ratio ? m : best), matchWords(sentence.en_text, ""));
  }, [sentence.en_text, speech.alternatives, speech.transcript]);
  const hasAttempt = mode === "voice" && speech.transcript.length > 0;
  const passed = cleared[index] || (mode === "voice" && match.ratio >= PASS_RATIO);
  const micBlocked = !speech.supported || micDenied || (speech.errorCode !== null && MIC_BLOCKED.has(speech.errorCode));
  const canSkip = micBlocked || misses >= MISSES_BEFORE_SKIP;
  const { stop, reset, listening, transcript, errorCode } = speech;

  const clear = useCallback(() => setCleared((c) => c.map((v, i) => (i === index ? true : v))), [index]);

  // 1) 통과 확정: 기준을 넘으면 듣기를 멈추고 이 문장을 통과로 표시한다.
  useEffect(() => {
    if (!hasAttempt || cleared[index] || match.ratio < PASS_RATIO) return;
    stop();
    clear();
  }, [match.ratio, hasAttempt, index, cleared, stop, clear]);

  // 말하기가 끝났는데 못 넘겼으면(목소리가 안 들린 경우 포함) 한 번 놓친 것으로 센다.
  const wasListening = useRef(false);
  useEffect(() => {
    if (wasListening.current && !listening && !cleared[index] && match.ratio < PASS_RATIO) {
      if (transcript.length > 0 || errorCode === "no-speech") setMisses((n) => n + 1);
    }
    wasListening.current = listening;
  }, [listening, cleared, index, match.ratio, transcript, errorCode]);

  useEffect(() => {
    if (errorCode !== null && MIC_BLOCKED.has(errorCode)) setMicDenied(true);
  }, [errorCode]);

  // 마이크를 쓸 수 없으면 받아 적기로 바꾼다.
  useEffect(() => {
    if (micBlocked && mode === "voice") setMode("type");
  }, [micBlocked, mode]);

  // 2) 이동: 통과로 확정된 뒤 잠시 보여 주고 다음 문장으로. 인식 결과가 늦게 바뀌어도 취소되지 않는다.
  useEffect(() => {
    if (!cleared[index]) return;
    const t = window.setTimeout(() => {
      if (index + 1 < sentences.length) {
        setIndex(index + 1);
        setTyped("");
        setTypedResult(null);
        setMisses(0);
        setConfirmSkip(false);
        reset();
      } else {
        studyStore.record({ type: "MORNING_LOCK", result: "CLEARED" });
        showToast("모닝 스타터 완료! 오늘도 시작했어요", "success");
        onDone();
      }
    }, 900);
    return () => window.clearTimeout(t);
  }, [cleared, index, sentences.length, reset, onDone]);

  const submitTyped = (event: FormEvent) => {
    event.preventDefault();
    if (!typed.trim() || cleared[index]) return;
    const result = matchWords(sentence.en_text, typed);
    setTypedResult(result);
    if (result.ratio >= PASS_RATIO) clear();
    else setMisses((n) => n + 1);
  };

  const switchMode = (next: Mode) => {
    reset();
    setTypedResult(null);
    setMode(next);
  };

  const skip = () => {
    speech.reset();
    studyStore.record({ type: "MORNING_LOCK", result: "SKIPPED" });
    onDone();
  };

  const voicePercent = Math.round(match.ratio * 100);
  const typedPercent = typedResult ? Math.round(typedResult.ratio * 100) : 0;

  // 받아 적기에서는 통과 전까지 영어 문장을 가린다(보고 베끼면 연습이 되지 않는다). 맞힌 단어만 드러낸다.
  let sentenceText: ReactNode;
  if (mode === "voice") {
    sentenceText = hasAttempt
      ? match.hits.map((h, i) => (
          <span key={i} className={h.hit ? "text-surface" : "text-surface/45"}>
            {i > 0 ? " " : ""}
            {h.word}
          </span>
        ))
      : sentence.en_text;
  } else if (cleared[index]) {
    sentenceText = sentence.en_text;
  } else if (typedResult) {
    sentenceText = typedResult.hits.map((h, i) => (
      <span key={i} className={h.hit ? "text-surface" : "text-surface/40"}>
        {i > 0 ? " " : ""}
        {h.hit ? h.word : "·".repeat(Math.min(h.word.length, 6))}
      </span>
    ));
  } else {
    sentenceText = <span className="text-[19px] text-surface/60">문장을 듣고 영어로 받아 적어 보세요</span>;
  }

  return (
    <div className="mx-auto flex min-h-dvh max-w-md flex-col px-6 pb-[max(1.5rem,env(safe-area-inset-bottom))] pt-safe unfolded:max-w-3xl unfolded:px-10">
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[13px] font-medium text-surface/80">모닝 스타터</p>
          <h1 id="morning-title" className="mt-1 text-balance break-keep text-[22px] font-bold leading-snug">
            {mode === "voice" ? "오늘의 핵심 3문장 소리 내어 읽기" : "오늘의 핵심 3문장 듣고 받아 적기"}
          </h1>
        </div>
        {/* 아침 인사. 세 문장을 모두 통과하면 웃는다. 파란 바탕이라 흰 원 위에 세운다. 낮은 화면에서는 작게. */}
        {mascot && (
          <span className="flex h-[104px] w-[104px] shrink-0 items-center justify-center rounded-full bg-surface shadow-lift [@media(max-height:680px)]:h-[76px] [@media(max-height:680px)]:w-[76px]">
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

      {/* 펼친 화면은 문장 카드와 조작부를 두 열로 놓아 가로로 낮은 화면에서도 아래가 잘리지 않게 한다. */}
      <div className="flex flex-1 flex-col unfolded:grid unfolded:grid-cols-2 unfolded:items-center unfolded:gap-10">
        <div className="mt-6 rounded-3xl bg-surface/15 p-6 backdrop-blur unfolded:mt-0">
          <p className="text-[12.5px] font-semibold tabular-nums text-surface/75">
            {index + 1} / {sentences.length}
          </p>
          <p lang="en" translate="no" className="mt-2 break-words text-pretty text-[24px] font-bold leading-[1.35] tracking-tight">
            {sentenceText}
          </p>
          <p className="mt-2 text-pretty text-[14px] leading-relaxed text-surface/80">{sentence.ko_natural}</p>
        </div>

        <div className="flex flex-1 flex-col items-center justify-center gap-4 py-8 unfolded:py-6">
          {mode === "voice" ? (
            <>
              <div className="relative flex h-[120px] w-[120px] items-center justify-center [@media(max-height:680px)]:h-[100px] [@media(max-height:680px)]:w-[100px]">
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
              <p className="min-h-[22px] text-center text-[15px] font-semibold tabular-nums" aria-live="polite">
                {passed
                  ? "통과했어요"
                  : speech.listening
                    ? "듣고 있어요"
                    : hasAttempt
                      ? `순서대로 맞힌 단어 ${voicePercent}% (60% 이상이면 통과)`
                      : "마이크를 누르고 읽어 보세요"}
              </p>
              {speech.error && <p className="text-pretty text-center text-[13px] text-surface/85">{speech.error}</p>}
            </>
          ) : (
            <form onSubmit={submitTyped} className="flex w-full max-w-sm flex-col gap-3">
              {micBlocked && (
                <p className="text-pretty text-center text-[13px] leading-relaxed text-surface/85">
                  {speech.supported && speech.error ? speech.error : "이 브라우저에서는 음성 인식을 쓸 수 없어요."} 문장을 듣고 받아 적어
                  통과해요.
                </p>
              )}
              <label htmlFor="morning-typed" className="sr-only">
                들은 문장을 영어로 입력
              </label>
              <input
                id="morning-typed"
                lang="en"
                value={typed}
                onChange={(e) => setTyped(e.target.value)}
                disabled={cleared[index]}
                autoComplete="off"
                autoCorrect="off"
                autoCapitalize="off"
                spellCheck={false}
                enterKeyHint="done"
                placeholder="들은 문장을 영어로"
                className="h-12 w-full rounded-2xl bg-surface/15 px-4 text-[16px] font-semibold text-surface placeholder:font-medium placeholder:text-surface/55 focus:bg-surface/20 focus:outline-none focus-visible:ring-2 focus-visible:ring-surface/70"
              />
              <button
                type="submit"
                disabled={!typed.trim() || cleared[index]}
                className="press flex h-12 items-center justify-center gap-1.5 rounded-full bg-surface text-[15px] font-bold text-royal shadow-lift disabled:opacity-60"
              >
                {cleared[index] && <Check size={18} strokeWidth={2.25} aria-hidden />}
                {cleared[index] ? "통과했어요" : "확인"}
              </button>
              <p className="min-h-[20px] text-center text-[14px] font-semibold tabular-nums" aria-live="polite">
                {typedResult && !cleared[index] ? `순서대로 맞힌 단어 ${typedPercent}% (60% 이상이면 통과)` : ""}
              </p>
            </form>
          )}

          <div className="flex flex-wrap items-center justify-center gap-2">
            <button
              type="button"
              onClick={() => void audioEngine.audition(sentence)}
              className="press flex h-11 items-center gap-1.5 rounded-full bg-surface/15 px-4 text-[13.5px] font-semibold"
            >
              <Volume2 size={16} strokeWidth={2} aria-hidden />
              문장 듣기
            </button>
            {mode === "voice" ? (
              <button
                type="button"
                onClick={() => switchMode("type")}
                className="press flex h-11 items-center gap-1.5 rounded-full px-4 text-[13.5px] font-semibold text-surface/85"
              >
                <Keyboard size={16} strokeWidth={2} aria-hidden />
                받아 적기로
              </button>
            ) : (
              !micBlocked && (
                <button
                  type="button"
                  onClick={() => switchMode("voice")}
                  className="press flex h-11 items-center gap-1.5 rounded-full px-4 text-[13.5px] font-semibold text-surface/85"
                >
                  <Mic size={16} strokeWidth={2} aria-hidden />
                  말하기로
                </button>
              )
            )}
          </div>

          {canSkip && (
            <div className="flex min-h-11 flex-col items-center">
              {confirmSkip ? (
                <div className="flex flex-col items-center gap-2 text-center" role="group" aria-label="건너뛰기 확인">
                  <p className="text-pretty text-[13px] text-surface/85">오늘 모닝 스타터를 건너뛸까요? 건너뛴 날로 기록돼요.</p>
                  <div className="flex gap-2">
                    <button
                      type="button"
                      onClick={() => setConfirmSkip(false)}
                      className="press h-11 rounded-full bg-surface px-5 text-[13.5px] font-bold text-royal"
                    >
                      계속할게요
                    </button>
                    <button type="button" onClick={skip} className="press h-11 rounded-full px-4 text-[13.5px] font-semibold text-surface/80">
                      건너뛰기
                    </button>
                  </div>
                </div>
              ) : (
                <button
                  type="button"
                  onClick={() => setConfirmSkip(true)}
                  className="h-11 px-4 text-[13px] text-surface/70 underline underline-offset-4"
                >
                  긴급 건너뛰기
                </button>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
