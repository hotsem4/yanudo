import { Keyboard } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import type { Sentence } from "../api/types";
import { audioEngine } from "../audio/useEngine";
import { useSpeechMatch } from "../hooks/useSpeechMatch";
import { speakControl } from "../lib/speakControl";
import { matchWords, PASS_RATIO, type MatchResult } from "../lib/wordMatch";
import { studyStore } from "../state/store";

const MIC_BLOCKED = new Set(["not-allowed", "service-not-allowed", "audio-capture", "network"]);
/** 통과하고 다음 문장으로 넘어가기까지(결과를 볼 시간). */
const ADVANCE_MS = 1200;
/** 받아 적기는 인식 오차가 없으므로 더 높게 본다. */
const TYPE_PASS_RATIO = 0.8;

/** 마이크를 쓸 수 없는 상태는 문장이 바뀌어도 기억한다(문장마다 실패해야 받아 적기로 바뀌지 않게). network 는 다시 연결되면 푼다. */
let micUnavailable: string | null = null;
if (typeof window !== "undefined") {
  window.addEventListener("online", () => {
    if (micUnavailable === "network") micUnavailable = null;
  });
}

/**
 * 카드가 그릴 영어 문장 모양.
 * - none: 평소대로(필터에 따라 보이거나 가림)
 * - hide: 가린다(외워서 말하기 단계)
 * - hits: 맞힌 단어만 드러내고 나머지는 가린다(가리고 말하는 중·받아 적기)
 * - full: 전부 보이되 맞힌 단어는 진하게, 놓친 단어는 흐리게(말해 본 뒤)
 */
export type EnDisplay = "none" | "hide" | "hits" | "full";

export interface SpeakFeedback {
  result: MatchResult | null;
  display: EnDisplay;
  status: string;
  tone: "info" | "listening" | "pass" | "error";
  /** 지금 단계(카드 머리에 작게 보인다). */
  step: string | null;
}

interface Props {
  sentence: Sentence;
  /** 오늘 이미 따라 말하기로 통과한 문장. 다시 해도 되지만 표시가 달라진다. */
  passed: boolean;
  /** 가리기 필터가 이미 영어를 가리고 있다(한글만·어순힌트). 그러면 보고 말하기 단계를 건너뛴다. */
  filterHides: boolean;
  /** 이 문장을 통과하면 남는 미통과 문장 수(0 이면 오늘 달성). */
  remainingAfter: number;
  disabled: boolean;
  /** 통과했다(기록은 여기서 남긴다). typed: 받아 적기로 통과(소리 없이 넘어간다). 넘어갈 문장이 없으면 false. */
  onPassed: (typed: boolean) => boolean;
  /** 결과·안내. 카드 아래쪽은 커버 화면에서 독에 가리므로 부모가 문장 자리와 진행 막대 아래에 보인다. */
  onFeedback: (fb: SpeakFeedback) => void;
}

/**
 * 오늘의 10문장 따라 말하기(SPEC F-02·F-05). 학습 앱이라 한 문장을 두 번 말한다.
 * 1) 보고 따라 말하기 → 2) 영어를 가리고 해석·어순만 보고 말하기. 2단계를 통과해야 VIEW(via SPEAK)를 남긴다.
 * 판정은 말을 끝낸 뒤 최종 결과로 한다(순서대로 맞힌 단어 60% 이상). 말하는 동안에는 맞힌 단어만 드러난다.
 * 받아 적기는 영어를 가린 채 80% 이상이어야 통과다.
 */
export function SpeakCheck({ sentence, passed, filterHides, remainingAfter, disabled, onPassed, onFeedback }: Props) {
  const speech = useSpeechMatch();
  const { start, stop, reset, listening, transcript, errorCode } = speech;
  if (errorCode !== null && MIC_BLOCKED.has(errorCode)) micUnavailable = errorCode;
  const micBlocked = !speech.supported || micUnavailable !== null;

  const [stage, setStage] = useState<"see" | "recall">(filterHides ? "recall" : "see");
  const [typing, setTyping] = useState(false);
  const [typed, setTyped] = useState("");
  const [typedResult, setTypedResult] = useState<MatchResult | null>(null);
  const [judged, setJudged] = useState<MatchResult | null>(null);
  const [justPassed, setJustPassed] = useState(false);
  const [doneAll, setDoneAll] = useState(false);
  const pendingAdvance = useRef<boolean | null>(null);
  const timer = useRef<number | null>(null);
  const disabledRef = useRef(disabled);
  disabledRef.current = disabled;

  const useTyping = typing || micBlocked;

  useEffect(
    () => () => {
      if (timer.current !== null) window.clearTimeout(timer.current);
      stop();
    },
    [stop],
  );

  const live = useMemo(() => {
    const candidates = speech.alternatives.length > 0 ? speech.alternatives : [transcript];
    return candidates
      .map((t) => matchWords(sentence.en_text, t))
      .reduce((best, m) => (m.ratio > best.ratio ? m : best), matchWords(sentence.en_text, ""));
  }, [sentence.en_text, speech.alternatives, transcript]);

  const advance = (typedPass: boolean) => {
    timer.current = null;
    if (disabledRef.current) {
      pendingAdvance.current = typedPass; // 시트에 가려진 동안은 넘기지 않고 기다린다
      return;
    }
    const moved = onPassed(typedPass);
    if (!moved) {
      // 넘어갈 문장이 없다(오늘 다 통과했거나 남은 문장이 이 앞에 있다). 다시 연습할 수 있게 푼다.
      setJustPassed(false);
      setDoneAll(remainingAfter === 0);
      setStage(filterHides ? "recall" : "see");
      setJudged(null);
      reset();
    }
  };
  // 시트 등으로 카드가 가려지면 듣던 마이크를 멈춘다(보이지 않는 곳에서 판정·기록하지 않게).
  useEffect(() => {
    if (disabled && listening) stop();
  }, [disabled]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!disabled && pendingAdvance.current !== null) {
      const typedPass = pendingAdvance.current;
      pendingAdvance.current = null;
      advance(typedPass);
    }
  }, [disabled]); // eslint-disable-line react-hooks/exhaustive-deps

  const recordPass = (typedPass: boolean) => {
    studyStore.record({ type: "VIEW", sentence_id: sentence.id, session: "TODAY", via: "SPEAK" });
    setJustPassed(true);
    timer.current = window.setTimeout(() => advance(typedPass), ADVANCE_MS);
  };

  // 말을 끝냈을 때(인식이 끝났을 때) 한 번 판정한다. 중간 결과로는 통과시키지 않는다(문장 끝까지 말하게).
  const wasListening = useRef(false);
  useEffect(() => {
    if (wasListening.current && !listening && transcript.length > 0 && !justPassed && !disabledRef.current) {
      setJudged(live);
      if (live.ratio >= PASS_RATIO) {
        if (stage === "see") {
          setStage("recall");
        } else {
          recordPass(false);
        }
      }
    }
    wasListening.current = listening;
  }, [listening]); // eslint-disable-line react-hooks/exhaustive-deps

  const toggleMic = () => {
    if (listening) {
      stop();
      return;
    }
    if (micBlocked) return;
    // 받아 적기 중에 독 마이크를 누르면 말하기로 돌아간다.
    setTyping(false);
    setTypedResult(null);
    // 들려주던 소리(연속 재생·미리듣기·기기 음성)가 마이크에 들어가지 않게 모두 멈춘다.
    audioEngine.hush();
    setDoneAll(false);
    setJudged(null);
    reset();
    start();
  };

  // 하단 독 가운데 버튼이 이 카드의 마이크가 된다(엄지 자리).
  const toggleRef = useRef(toggleMic);
  toggleRef.current = toggleMic;
  const control = useRef<ReturnType<typeof speakControl.claim> | null>(null);
  useEffect(() => {
    control.current = speakControl.claim(() => toggleRef.current());
    return () => control.current?.release();
  }, []);
  useEffect(() => {
    control.current?.update({ active: true, listening, justPassed, disabled: disabled || micBlocked });
  }, [listening, justPassed, disabled, micBlocked]);

  const submitTyped = (e: FormEvent) => {
    e.preventDefault();
    if (justPassed) return;
    const r = matchWords(sentence.en_text, typed);
    setTypedResult(r);
    if (r.ratio >= TYPE_PASS_RATIO) recordPass(true);
  };

  // ── 화면에 보일 것 ──
  const result = useTyping ? typedResult : listening ? (transcript ? live : null) : judged;
  const percent = result ? Math.round(result.ratio * 100) : 0;
  const hitCount = result ? result.hits.filter((h) => h.hit).length : 0;
  const score = result ? `단어 ${result.hits.length}개 중 ${hitCount}개 (${percent}%)` : "";
  const recall = useTyping || stage === "recall";

  let display: EnDisplay = "none";
  if (justPassed) display = result ? "full" : "none";
  else if (useTyping) display = typedResult ? "hits" : "hide";
  else if (listening) display = recall ? "hits" : "none";
  else if (judged) display = "full";
  else if (stage === "recall") display = "hide";

  let tone: SpeakFeedback["tone"] = "info";
  let status: string;
  if (justPassed) {
    tone = "pass";
    status = remainingAfter === 0 ? "통과! 오늘 10문장을 모두 말했어요" : `통과! ${score}`;
  } else if (doneAll) {
    tone = "pass";
    status = "오늘 10문장 모두 통과했어요. 더 연습해도 좋아요";
  } else if (listening) {
    tone = "listening";
    status = recall ? "가린 문장을 영어로 말해 보세요. 다 말하면 마이크를 한 번 더 눌러요" : "보면서 영어로 따라 말해 보세요. 다 말하면 마이크를 한 번 더 눌러요";
  } else if (useTyping) {
    status = typedResult
      ? `${score}. 80% 이상이면 통과예요. 다시 들어 보고 고쳐 적어요`
      : micBlocked && !typing
        ? `${speech.error ?? "이 브라우저에서는 음성 인식을 쓸 수 없어요."} 들은 문장을 영어로 적어 보세요`
        : "문장을 듣고 영어로 적어 보세요(왼쪽 아래 스피커 버튼으로 다시 듣기)";
  } else if (!listening && speech.error) {
    tone = "error";
    status = speech.error;
  } else if (judged && judged.ratio >= PASS_RATIO && stage === "recall" && !filterHides) {
    status = `좋아요! ${score}. 이번엔 영어를 가리고 말해 보세요`;
  } else if (judged) {
    status = `${score}. 60% 이상이면 통과예요. 흐린 단어를 다시 들어 보고 말해 보세요`;
  } else if (stage === "recall") {
    status = filterHides ? "해석·어순을 보고 영어로 말해 보세요(가운데 마이크 버튼)" : "이번엔 영어를 가리고 말해 보세요(가운데 마이크 버튼)";
  } else {
    status = passed ? "통과한 문장이에요. 다시 해도 좋아요(가운데 마이크 버튼)" : "듣고 나서 가운데 마이크 버튼을 누르고 따라 말해 보세요";
  }
  const step = useTyping ? "받아 적기" : stage === "see" ? "1/2 보고 말하기" : filterHides ? "외워서 말하기" : "2/2 가리고 말하기";

  useEffect(() => {
    onFeedback({ result, display, status, tone, step });
  }, [result, display, status, tone, step]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="mt-1 flex flex-col gap-2" aria-hidden={disabled || undefined}>
      {useTyping && (
        <form onSubmit={submitTyped} className="flex items-center gap-2">
          <input
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            onFocus={(e) => e.currentTarget.scrollIntoView({ block: "center", behavior: "smooth" })}
            disabled={disabled || justPassed}
            lang="en"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
            placeholder="들은 문장을 영어로 적기"
            aria-label="들은 문장을 영어로 적기"
            className="h-12 min-w-0 flex-1 rounded-2xl bg-canvas px-4 text-[16px] text-ink outline-none placeholder:text-faint focus:ring-2 focus:ring-royal/40"
          />
          <button
            type="submit"
            disabled={disabled || justPassed || typed.trim().length === 0}
            className="press h-12 shrink-0 rounded-full bg-royal px-5 text-[14px] font-semibold text-surface disabled:opacity-40"
          >
            확인
          </button>
        </form>
      )}

      {!micBlocked && (
        <button
          type="button"
          onClick={() => {
            stop();
            setTyping((t) => !t);
            setTypedResult(null);
            setJudged(null);
          }}
          disabled={disabled}
          className="press hit flex items-center gap-1.5 self-start text-[12.5px] font-semibold text-muted"
        >
          <Keyboard size={14} strokeWidth={2} aria-hidden />
          {typing ? "말하기로 돌아가기" : "소리 내기 어려우면 받아 적기"}
        </button>
      )}
    </div>
  );
}
