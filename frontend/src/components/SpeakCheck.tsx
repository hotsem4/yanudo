import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import type { Sentence } from "../api/types";
import { SPEECH_ERRORS, useSpeechMatch } from "../hooks/useSpeechMatch";
import { speakControl, useSpeakControl } from "../lib/speakControl";
import { excuseWords, matchWords, PASS_RATIO, type MatchResult } from "../lib/wordMatch";
import { studyStore } from "../state/store";

/** 마이크를 아예 쓸 수 없는 오류. network 는 기기가 온라인이어도 잠깐 나므로 문장마다의 일시 오류로 본다. */
const MIC_BLOCKED = new Set(["not-allowed", "service-not-allowed", "audio-capture"]);
/** 통과하고 다음 문장으로 넘어가기까지(결과를 볼 시간). */
const ADVANCE_MS = 1200;
/** 1단계를 통과한 뒤 맞힌·놓친 단어를 보여 주고 영어를 가리기까지. */
const COVER_MS = 1500;
/** 말이 이만큼 끊기면 다 말한 것으로 보고 채점한다(문장 중간에 숨 고르는 정도는 넘긴다). */
const SETTLE_MS = 1800;
/** 받아 적기는 인식 오차가 없으므로 더 높게 본다. */
const TYPE_PASS_RATIO = 0.8;
/** 1단계(보고 말하기)는 문장을 보며 따라 하므로 높게 본다. 2단계(가리고 말하기)는 PASS_RATIO(60%). */
const SEE_PASS_RATIO = 0.8;
/** 문장의 나머지를 이만큼 이상 맞혔는데 같은 단어를 두 번 연달아 못 알아들으면, 인식이 어려운 단어로 보고 넘긴다. */
const EXCUSE_AFTER_MISSES = 2;
const EXCUSE_MIN_RATIO = 0.5;

/** 마이크를 쓸 수 없는 상태는 문장이 바뀌어도 기억한다(문장마다 실패해야 받아 적기로 바뀌지 않게). */
let micUnavailable: string | null = null;

/**
 * 문장·탭을 오가도 이어지는 학습 상태. SpeakCheck 는 문장마다 새로 붙으므로 컴포넌트 밖에 둔다(새로고침하면 처음부터).
 * - seeStagePassed: 1단계(보고 말하기)를 통과한 문장. 돌아오면 2단계부터 한다.
 * - preferTyping: 스스로 고른 받아 적기. 다음 문장에서도 받아 적기로 이어 간다.
 */
const seeStagePassed = new Set<number>();
let preferTyping = false;
/**
 * 인식이 어려운 단어(문장 id → 정답 단어 위치). "Seoul" 처럼 또박또박 말해도 인식기가 받아 적지 못하는 단어가 있다.
 * 문장의 나머지는 잘 말했는데 같은 단어만 두 번 연달아 놓치면 맞은 것으로 친다. 문장당 1~2개까지만(문장을 통째로 넘기지 않게).
 */
const excused = new Map<number, Set<number>>();
const missCounts = new Map<number, Map<number, number>>();

/**
 * 문장이 바뀐 직후, SpeakCheck 가 첫 피드백을 보내기 전에 카드가 쓸 영어 표시. 피드백을 기다리는 한 프레임 동안
 * "보임"으로 그리면 가려야 할 문장의 답이 잠깐 보인다.
 */
export function initialDisplay(sentenceId: number, passed: boolean): EnDisplay {
  if (passed) return "show";
  const supported = typeof window !== "undefined" && !!(window.SpeechRecognition ?? window.webkitSpeechRecognition);
  if (preferTyping || micUnavailable !== null || !supported) return "hide";
  return seeStagePassed.has(sentenceId) ? "hide" : "show";
}

/**
 * 카드가 그릴 영어 문장 모양. 오늘 탭에서는 이 값만 영어를 보일지 정한다(가리기 필터·역할극 가림은 쓰지 않는다).
 * - show: 그대로 보인다(보고 말하기)
 * - hide: 가린다(가리고 말하기, 받아 적기)
 * - live: 다 보이되 맞힌 단어가 진해진다(보고 말하는 중)
 * - hits: 맞힌 단어만 드러나고 나머지는 빈칸(가리고 말하는 중, 받아 적기 결과)
 * - full: 다 보이고 맞힌 단어는 진하게, 놓친 단어는 흐리게(말해 본 뒤)
 */
export type EnDisplay = "show" | "hide" | "live" | "hits" | "full";

export interface SpeakFeedback {
  result: MatchResult | null;
  display: EnDisplay;
  status: string;
  tone: "info" | "listening" | "pass" | "error";
  /** 지금 단계(안내 띠 위에 작게 보인다). 오늘 10문장을 다 통과하고 쉬는 중이면 null. */
  step: string | null;
  typing: boolean;
  /** 받아 적기와 말하기를 오갈 수 있다(마이크를 쓸 수 없으면 받아 적기만). */
  canSwitch: boolean;
  toggleTyping: () => void;
}

interface Props {
  sentence: Sentence;
  /** 오늘 이미 따라 말하기로 통과한 문장. 다시 해도 되지만 표시가 달라진다. */
  passed: boolean;
  /** 이 문장을 빼고 남은 미통과 문장 수(0 이면 이 문장으로 오늘 달성). */
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
 * 판정은 말을 끝낸 뒤 최종 결과로 한다(순서대로 맞힌 단어 60% 이상). 받아 적기는 영어를 가린 채 80% 이상이어야 통과다.
 */
export function SpeakCheck({ sentence, passed, remainingAfter, disabled, onPassed, onFeedback }: Props) {
  const speech = useSpeechMatch({ continuous: true });
  const { start, stop, reset, listening, transcript, errorCode } = speech;
  if (errorCode !== null && MIC_BLOCKED.has(errorCode)) micUnavailable = errorCode;
  const micBlocked = !speech.supported || micUnavailable !== null;
  const { hearing } = useSpeakControl();
  // 먼저 듣기: 아직 끝까지 들은 적 없는 미통과 문장은 마이크 대신 스피커가 먼저다(듣기 전에 실수로 녹음하지 않게).
  const needsHearing = !passed && !speakControl.isHeard(sentence.id);
  const [newlyExcused, setNewlyExcused] = useState<string | null>(null);

  const [stage, setStage] = useState<"see" | "recall">(() => (seeStagePassed.has(sentence.id) ? "recall" : "see"));
  const [typing, setTypingState] = useState(preferTyping);
  const setTyping = (on: boolean) => {
    preferTyping = on;
    setTypingState(on);
  };
  const [typed, setTyped] = useState("");
  const [typedResult, setTypedResult] = useState<MatchResult | null>(null);
  const [judged, setJudged] = useState<MatchResult | null>(null);
  /** 방금 1단계를 통과해 결과를 보여 주는 중(곧 영어를 가린다). */
  const [stageUp, setStageUp] = useState(false);
  const [justPassed, setJustPassed] = useState(false);
  /** 이번 통과로 오늘 10문장을 처음 다 채웠다(다 끝낸 뒤 다시 연습한 통과와 구분한다). */
  const [finishedNow, setFinishedNow] = useState(false);
  const pendingAdvance = useRef<boolean | null>(null);
  const timer = useRef<number | null>(null);
  const coverTimer = useRef<number | null>(null);
  const disabledRef = useRef(disabled);
  disabledRef.current = disabled;
  // 1.2초 타이머가 부를 때 그 사이 바뀐 화면(날짜 변경·잠금)을 보도록 늘 최신 콜백을 쓴다.
  const onPassedRef = useRef(onPassed);
  onPassedRef.current = onPassed;
  // 한 프레임 안에 마이크를 두 번 누르면 렌더 전 값으로 둘 다 "시작"이 된다. 누른 순간의 상태를 따로 든다.
  const listeningRef = useRef(listening);
  listeningRef.current = listening;

  const useTyping = typing || micBlocked;
  const allDone = passed && remainingAfter === 0;

  const clearCover = () => {
    if (coverTimer.current !== null) window.clearTimeout(coverTimer.current);
    coverTimer.current = null;
    setStageUp(false);
  };

  useEffect(
    () => () => {
      if (timer.current !== null) window.clearTimeout(timer.current);
      if (coverTimer.current !== null) window.clearTimeout(coverTimer.current);
      stop();
    },
    [stop],
  );

  const rawLive = useMemo(() => {
    const candidates = speech.alternatives.length > 0 ? speech.alternatives : [transcript];
    return candidates
      .map((t) => matchWords(sentence.en_text, t))
      .reduce((best, m) => (m.ratio > best.ratio ? m : best), matchWords(sentence.en_text, ""));
  }, [sentence.en_text, speech.alternatives, transcript]);
  const live = useMemo(() => excuseWords(rawLive, excused.get(sentence.id)), [rawLive, sentence.id, newlyExcused]); // eslint-disable-line react-hooks/exhaustive-deps
  const passRatio = stage === "see" ? SEE_PASS_RATIO : PASS_RATIO;

  /** 실패한 판정 뒤: 문장의 나머지는 말했는데 같은 단어를 연달아 놓쳤으면 인식이 어려운 단어로 넘긴다. */
  const noteMisses = (raw: MatchResult) => {
    if (raw.ratio < EXCUSE_MIN_RATIO) return;
    const counts = missCounts.get(sentence.id) ?? new Map<number, number>();
    const set = excused.get(sentence.id) ?? new Set<number>();
    const cap = Math.max(1, Math.floor(raw.hits.length * 0.15));
    const added: string[] = [];
    raw.hits.forEach((h, i) => {
      if (h.hit) {
        counts.delete(i); // 연달아 놓친 것만 센다
        return;
      }
      const n = (counts.get(i) ?? 0) + 1;
      counts.set(i, n);
      if (n >= EXCUSE_AFTER_MISSES && !set.has(i) && set.size < cap) {
        set.add(i);
        added.push(h.word);
      }
    });
    missCounts.set(sentence.id, counts);
    excused.set(sentence.id, set);
    if (added.length > 0) setNewlyExcused(added.join(", "));
  };

  // 연속 인식이라 인식기가 스스로 끝나지 않는다. 문장을 다 맞혔거나 말이 잠깐 끊기면 멈추고 채점한다.
  useEffect(() => {
    if (!listening || transcript.length === 0) return;
    if (live.ratio >= 1) {
      stop();
      return;
    }
    const t = window.setTimeout(stop, SETTLE_MS);
    return () => window.clearTimeout(t);
  }, [listening, transcript, live.ratio, stop]);

  const advance = (typedPass: boolean) => {
    timer.current = null;
    if (disabledRef.current) {
      pendingAdvance.current = typedPass; // 시트에 가려진 동안은 넘기지 않고 기다린다
      return;
    }
    const moved = onPassedRef.current(typedPass);
    if (!moved) {
      // 넘어갈 문장이 없다(오늘 다 통과했거나 남은 문장이 이 앞에 있다). 다시 연습할 수 있게 푼다.
      setJustPassed(false);
      setJudged(null);
      setTypedResult(null);
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
    seeStagePassed.add(sentence.id);
    setFinishedNow(!passed && remainingAfter === 0);
    setJustPassed(true);
    timer.current = window.setTimeout(() => advance(typedPass), ADVANCE_MS);
  };

  // 말을 끝냈을 때(인식이 끝났을 때) 한 번 판정한다. 중간 결과로는 통과시키지 않는다(문장 끝까지 말하게).
  const wasListening = useRef(false);
  useEffect(() => {
    if (wasListening.current && !listening && transcript.length > 0 && !justPassed && !disabledRef.current) {
      setJudged(live);
      if (live.ratio < passRatio) noteMisses(rawLive);
      if (live.ratio >= passRatio) {
        if (stage === "see") {
          // 맞힌·놓친 단어를 잠깐 보여 준 뒤 영어를 가린다(가리고 말하는 단계에 답이 보이지 않게).
          seeStagePassed.add(sentence.id);
          setStage("recall");
          setStageUp(true);
          coverTimer.current = window.setTimeout(() => {
            coverTimer.current = null;
            setStageUp(false);
            setJudged(null);
          }, COVER_MS);
        } else {
          recordPass(false);
        }
      }
    }
    wasListening.current = listening;
  }, [listening]); // eslint-disable-line react-hooks/exhaustive-deps

  /** 받아 적기에서 말하기로 돌아갈 때. 받아 적기를 해 본 문장은 답을 다시 보여 주지 않는다(가린 단계로). */
  const leaveTyping = () => {
    if (typing && typedResult) setStage("recall");
    setTyping(false);
    setTypedResult(null);
    setTyped("");
  };

  const toggleMic = () => {
    if (listeningRef.current) {
      listeningRef.current = false;
      stop();
      return;
    }
    if (micBlocked || needsHearing) return;
    // 받아 적기 중에 독 마이크를 누르면 말하기로 돌아간다.
    leaveTyping();
    // 들려주던 소리(미리듣기·연속 재생·기기 음성)가 마이크에 들어가지 않게 모두 멈춘다.
    speakControl.hush();
    clearCover();
    setJudged(null);
    setNewlyExcused(null);
    reset();
    start();
    listeningRef.current = true;
  };

  const toggleTyping = () => {
    // 듣던 말은 채점하지 않고 버린다(stop 이면 판정이 돌아 받아 적기 화면에서 통과로 기록된다).
    reset();
    clearCover();
    setJudged(null);
    if (typing) leaveTyping();
    else {
      setTyping(true);
      setTypedResult(null);
    }
  };
  // 띠에 둔 버튼이 늘 지금 상태로 부르도록 같은 함수를 넘긴다(피드백은 값이 바뀔 때만 다시 보낸다).
  const toggleTypingRef = useRef(toggleTyping);
  toggleTypingRef.current = toggleTyping;
  const stableToggleTyping = useRef(() => toggleTypingRef.current()).current;

  // 하단 독 가운데 버튼이 이 카드의 마이크가 된다(엄지 자리).
  const resetRef = useRef(reset);
  resetRef.current = reset;
  const toggleRef = useRef(toggleMic);
  toggleRef.current = toggleMic;
  const control = useRef<ReturnType<typeof speakControl.claim> | null>(null);
  useEffect(() => {
    // 취소: reset 은 인식기를 버리고 받은 말도 비우므로 채점·기록하지 않는다.
    control.current = speakControl.claim(
      () => toggleRef.current(),
      () => resetRef.current(),
    );
    return () => control.current?.release();
  }, []);
  useEffect(() => {
    control.current?.update({ listening, justPassed, disabled, micBlocked, needsHearing });
  }, [listening, justPassed, disabled, micBlocked, needsHearing]);

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

  // 오늘 통과한 문장을 쉬며 보는 중이면 영어를 보여 준다(다 끝낸 뒤 다시 볼 수 있게). 다시 말해 보면 그때 가린다.
  const restingPassed = passed && !listening && !judged && !typedResult && !justPassed;
  let display: EnDisplay;
  if (justPassed) display = result ? "full" : "show";
  else if (useTyping) display = typedResult ? "hits" : restingPassed ? "show" : "hide";
  // 인식 결과가 오기 전에도 가린 단계는 가린 채로 둔다.
  else if (listening) display = recall ? (result ? "hits" : "hide") : result ? "live" : "show";
  else if (judged) display = "full";
  else display = recall && !restingPassed ? "hide" : "show";

  let tone: SpeakFeedback["tone"] = "info";
  let status: string;
  if (justPassed) {
    tone = "pass";
    status = finishedNow ? "통과! 오늘 10문장을 모두 말했어요" : `통과! ${score}`;
  } else if (listening) {
    tone = "listening";
    status = recall ? "가린 문장을 영어로 말해 보세요. 다 말하고 잠깐 멈추면 채점해요" : "보면서 영어로 따라 말해 보세요. 다 말하고 잠깐 멈추면 채점해요";
  } else if (hearing) {
    tone = "listening";
    status = useTyping
      ? "문장을 들려주는 중이에요. 다 들으면 영어로 적어 보세요"
      : needsHearing
        ? "문장을 들려주는 중이에요. 다 들으면 가운데 버튼이 마이크로 바뀌어요"
        : "문장을 들려주는 중이에요. 다 들으면 가운데 마이크를 눌러요";
  } else if (needsHearing && !justPassed) {
    status = useTyping ? "먼저 가운데 스피커를 눌러 문장을 들어 보세요. 들은 뒤에 적을 수 있어요" : "먼저 가운데 스피커를 눌러 문장을 들어 보세요";
  } else if (useTyping && typedResult) {
    status = `${score}. 80% 이상이면 통과예요. 다시 듣고 고쳐 적어요`;
  } else if (useTyping && allDone) {
    tone = "pass";
    status = "오늘 10문장 모두 통과했어요. 더 연습해도 좋아요";
  } else if (useTyping && restingPassed) {
    status = "통과한 문장이에요. 다시 적어 봐도 좋아요";
  } else if (useTyping) {
    // 마이크를 못 쓰는 이유는 문장이 바뀌어도 기억한 오류 코드로 다시 말한다(권한 거부를 "미지원"으로 바꿔 말하지 않게).
    const why = speech.error ?? (micUnavailable ? SPEECH_ERRORS[micUnavailable] : null) ?? "이 브라우저에서는 음성 인식을 쓸 수 없어요.";
    status = micBlocked && !typing ? `${why} 문장을 듣고 영어로 적어 보세요` : "영어를 가렸어요. 문장을 듣고 영어로 적어 보세요(왼쪽 아래 스피커로 다시 듣기)";
  } else if (speech.error && !judged) {
    // 말한 것이 있으면 판정이 먼저다(no-speech 가 뒤따라 와도 말한 단어를 버리지 않는다).
    tone = "error";
    status = errorCode === "network" ? `${speech.error} 위의 "받아 적기"로 바꿀 수도 있어요` : speech.error;
  } else if (stageUp && judged) {
    tone = "pass";
    status = `좋아요! ${score}. 이제 영어를 가리고 말해 봐요`;
  } else if (judged && newlyExcused) {
    status = `'${newlyExcused}'은(는) 인식이 잘 안 되는 단어라 다음부터 맞은 것으로 칠게요. 한 번 더 말해 보세요`;
  } else if (judged) {
    status = `${score}. ${Math.round(passRatio * 100)}% 이상이면 통과예요. 흐린 단어를 다시 들어 보고 말해 보세요`;
  } else if (allDone) {
    tone = "pass";
    status = "오늘 10문장 모두 통과했어요. 더 연습해도 좋아요";
  } else if (restingPassed) {
    status = "통과한 문장이에요. 다시 해도 좋아요(가운데 마이크)";
  } else if (recall) {
    status = "영어를 가렸어요. 어순·해석을 보고 말해 보세요(카드를 길게 누르면 잠깐 보여요)";
  } else {
    status = passed ? "통과한 문장이에요. 다시 해도 좋아요(가운데 마이크)" : "듣고 나서 가운데 마이크를 누르고, 보면서 따라 말해 보세요";
  }
  const resting = allDone && !listening && !judged && !justPassed && !typedResult;
  const step = resting ? null : useTyping ? "받아 적기" : restingPassed ? "통과한 문장" : stage === "see" ? "1/2 보고 말하기" : "2/2 가리고 말하기";

  useEffect(() => {
    onFeedback({ result, display, status, tone, step, typing: useTyping, canSwitch: !micBlocked, toggleTyping: stableToggleTyping });
  }, [result, display, status, tone, step, useTyping, micBlocked]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!useTyping) return null;
  return (
    <form onSubmit={submitTyped} className="mt-1 flex items-center gap-2" aria-hidden={disabled || undefined}>
      <input
        value={typed}
        onChange={(e) => setTyped(e.target.value)}
        onFocus={(e) => e.currentTarget.scrollIntoView({ block: "center", behavior: "smooth" })}
        disabled={disabled || justPassed || needsHearing}
        lang="en"
        autoCapitalize="off"
        autoCorrect="off"
        spellCheck={false}
        placeholder={needsHearing ? "먼저 문장을 들어 보세요" : "들은 문장을 영어로 적기"}
        aria-label="들은 문장을 영어로 적기"
        className="h-12 min-w-0 flex-1 rounded-2xl bg-canvas px-4 text-[16px] text-ink outline-none placeholder:text-faint focus:ring-2 focus:ring-royal/40"
      />
      <button
        type="submit"
        disabled={disabled || justPassed || needsHearing || typed.trim().length === 0}
        className="press h-12 shrink-0 rounded-full bg-royal px-5 text-[14px] font-semibold text-surface disabled:opacity-40"
      >
        확인
      </button>
    </form>
  );
}
