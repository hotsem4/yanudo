import { Check, ChevronLeft, ChevronRight, CloudOff, Mic, RotateCcw, Square, Volume2 } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api } from "../api/client";
import type { SpeakLesson } from "../api/types";
import { audioEngine } from "../audio/useEngine";
import { useSpeechMatch } from "../hooks/useSpeechMatch";
import type { MascotState } from "../lib/mascot";
import { displayHits, matchWords, PASS_RATIO } from "../lib/wordMatch";
import { studyStore } from "../state/store";
import { Mascot } from "./Mascot";
import { shortDate } from "./SpeakDayList";

/** 한 문장을 이만큼 맞게 말하면 그 문장 연습이 끝난다(서버가 Day 마다 정한다. 미리 알린 날 보충은 1). */
const DEFAULT_REPS = 5;
const MIC_BLOCKED = new Set(["not-allowed", "service-not-allowed", "audio-capture", "network"]);

interface Props {
  day: number;
  pendingIds: readonly number[];
  mascot: MascotState | null;
  /** 커버 화면에서 Day 목록으로 돌아가기(펼친 화면은 왼쪽 열에 목록이 있다). */
  onBack: () => void;
  /** 한 문장을 끝냈을 때. 목록이 전송 전에도 완료로 보이게 App 이 모아 둔다. */
  onDone: (sentenceId: number) => void;
}

/**
 * 말하기 연습(SPEC F-09, 부가 기능). 문장을 듣고 마이크로 말해, 순서대로 맞힌 단어가 60% 이상이면 1회.
 * 5회를 채우면 SPEAK 이벤트를 남기고 다음 문장으로 간다. 회차 사이에는 마이크를 자동으로 다시 켠다.
 */
export function SpeakPractice({ day, pendingIds, mascot, onBack, onDone }: Props) {
  const [lesson, setLesson] = useState<SpeakLesson | null>(null);
  const [failed, setFailed] = useState(false);
  const [index, setIndex] = useState(0);
  const [reps, setReps] = useState(0);
  const [doneLocal, setDoneLocal] = useState<ReadonlySet<number>>(new Set());
  // 들어올 때 이미 다 끝낸 Day 거나 "처음부터 다시"를 눌렀으면, 남은 문장·끝은 이번 연습에서 끝낸 문장으로만 판단한다.
  const [sessionOnly, setSessionOnly] = useState(false);
  const [finished, setFinished] = useState(false);
  const speech = useSpeechMatch();
  const { stop, reset, start, listening, transcript, errorCode } = speech;
  // 이번 말하기를 이미 셌는가(인식 결과가 늦게 몇 번 더 바뀌어도 한 번만 센다).
  const counted = useRef(false);
  const timer = useRef<number | null>(null);
  const clearTimer = () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
  };
  useEffect(() => clearTimer, []);

  const load = useCallback(() => {
    setFailed(false);
    setLesson(null);
    api
      .speakDay(day)
      .then((l) => {
        setLesson(l);
        const done = new Set([...l.done_ids, ...pendingIds]);
        const first = l.sentences.findIndex((s) => !done.has(s.id));
        setIndex(first < 0 ? 0 : first);
        setSessionOnly(first < 0);
        setDoneLocal(new Set());
        setReps(0);
        setFinished(false);
      })
      .catch(() => setFailed(true));
    // pendingIds 가 바뀔 때마다 다시 받지 않는다(처음 위치를 정할 때만 쓴다).
  }, [day]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    clearTimer();
    stop();
    load();
  }, [load, stop]);

  const sentence = lesson?.sentences[index] ?? null;
  const target = lesson?.reps ?? DEFAULT_REPS;
  const makeup = lesson?.state === "MAKEUP";
  const done = useMemo(() => new Set([...(lesson?.done_ids ?? []), ...pendingIds, ...doneLocal]), [lesson, pendingIds, doneLocal]);

  const match = useMemo(() => {
    if (!sentence) return matchWords("", "");
    const candidates = speech.alternatives.length > 0 ? speech.alternatives : [transcript];
    return candidates.map((t) => matchWords(sentence.en_text, t)).reduce((best, m) => (m.ratio > best.ratio ? m : best), matchWords(sentence.en_text, ""));
  }, [sentence, speech.alternatives, transcript]);
  const hasAttempt = transcript.length > 0;
  const micBlocked = !speech.supported || (errorCode !== null && MIC_BLOCKED.has(errorCode));

  const goTo = useCallback(
    (i: number) => {
      clearTimer();
      stop();
      reset();
      counted.current = false;
      setReps(0);
      setIndex(i);
    },
    [stop, reset],
  );

  // 기준을 넘으면 1회로 센다. 5회면 기록하고 다음 문장, 아니면 잠깐 뒤 마이크를 다시 켠다.
  useEffect(() => {
    if (!sentence || !hasAttempt || counted.current || match.ratio < PASS_RATIO) return;
    counted.current = true;
    stop();
    const next = reps + 1;
    setReps(next);
    clearTimer();
    if (next >= target) {
      studyStore.record({ type: "SPEAK", sentence_id: sentence.id, reps: target });
      setDoneLocal((s) => new Set(s).add(sentence.id));
      onDone(sentence.id);
      // 건너뛴 문장이 남았으면 그쪽으로 간다(이 문장 다음부터 찾고, 없으면 처음부터). 모두 끝냈을 때만 끝 화면.
      const finishedIds = new Set([...(sessionOnly ? doneLocal : done), sentence.id]);
      const list = lesson?.sentences ?? [];
      const order = [...list.slice(index + 1), ...list.slice(0, index + 1)];
      const nextUndone = order.find((s) => !finishedIds.has(s.id));
      timer.current = window.setTimeout(() => {
        if (nextUndone) goTo(list.indexOf(nextUndone));
        else {
          reset();
          setFinished(true);
        }
      }, 1000);
    } else {
      timer.current = window.setTimeout(() => {
        counted.current = false;
        reset();
        start();
      }, 700);
    }
  }, [match.ratio, hasAttempt, sentence, reps, target, done, doneLocal, sessionOnly, stop, reset, start, goTo, index, lesson, onDone]);

  const toggleMic = () => {
    if (listening) {
      stop();
      return;
    }
    clearTimer();
    counted.current = false;
    reset();
    start();
  };
  const listen = () => {
    if (!sentence) return;
    stop();
    void audioEngine.audition(sentence);
  };

  if (failed) {
    return (
      <Frame>
        <div className="my-auto flex flex-col items-center gap-3 text-center">
          <CloudOff size={22} strokeWidth={2} className="text-muted" aria-hidden />
          <p className="break-keep text-[14px] text-muted">문장을 받지 못했어요. 연결되면 다시 받아 주세요.</p>
          <button type="button" onClick={load} className="press h-11 rounded-full bg-tint px-5 text-[13.5px] font-semibold text-royal">
            다시 받기
          </button>
        </div>
      </Frame>
    );
  }
  if (!lesson || !sentence) {
    return (
      <Frame>
        <div aria-hidden className="my-auto flex flex-col gap-3">
          <span className="skeleton h-7 w-3/4 rounded-lg bg-tint" />
          <span className="skeleton h-5 w-1/2 rounded-lg bg-line" />
        </div>
      </Frame>
    );
  }

  if (finished) {
    return (
      <Frame>
        <div className="my-auto flex flex-col items-center gap-4 text-center">
          {mascot && <Mascot stage={mascot.stage} mood="party" size={96} />}
          <h2 className="text-[19px] font-bold text-ink">Day {lesson.day} {makeup ? "보충" : "말하기 연습"} 끝!</h2>
          <p className="break-keep text-[14px] text-muted">
            {lesson.sentences.length}문장을 {target}번씩 소리 내어 말했어요.
            {makeup && (lesson.repairable ? " 전송되면 그날 연속 기록에 반영돼요." : " 이제 이 Day 도 자유롭게 연습할 수 있어요.")}
          </p>
          <div className="flex flex-wrap justify-center gap-2">
            <button type="button" onClick={() => {
                // 보충을 끝냈으면 이제 일반 연습(문장마다 5번)이다. 보충 횟수로 계속 기록하지 않는다.
                if (makeup) {
                  setLesson({ ...lesson, state: "OPEN", reps: DEFAULT_REPS, rest: false, deadline: null, repairable: false, done_ids: [] });
                }
                setDoneLocal(new Set());
                setSessionOnly(true);
                setFinished(false);
                goTo(0);
              }} className="press h-11 rounded-full bg-tint px-5 text-[13.5px] font-semibold text-royal">
              처음부터 다시
            </button>
            <button type="button" onClick={onBack} className="press h-11 rounded-full bg-royal px-5 text-[13.5px] font-semibold text-surface unfolded:hidden">
              다른 Day 고르기
            </button>
          </div>
        </div>
      </Frame>
    );
  }

  const status = micBlocked
    ? speech.error ?? "이 브라우저에서는 음성 인식을 쓸 수 없어요."
    : counted.current && reps >= target
      ? "이 문장 끝! 다음 문장으로 넘어가요"
      : counted.current
        ? `좋아요! 한 번 더 (${reps}/${target})`
        : listening
          ? "듣고 있어요"
          : hasAttempt
            ? `순서대로 맞힌 단어 ${Math.round(match.ratio * 100)}% (60% 이상이면 1회) — 다시 눌러 말해 보세요`
            : "마이크를 누르고 소리 내어 말해 보세요";

  return (
    <Frame>
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={onBack}
          aria-label="Day 목록으로"
          className="press hit -ml-2 flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-muted unfolded:hidden"
        >
          <ChevronLeft size={20} strokeWidth={2} aria-hidden />
        </button>
        <p className="min-w-0 flex-1 truncate text-[13px] font-semibold text-muted-strong">
          <span className="tabular-nums text-royal">Day {lesson.day}</span> · {lesson.title}
        </p>
        <span className="shrink-0 text-[12.5px] font-semibold tabular-nums text-muted">
          {index + 1} / {lesson.sentences.length}
        </span>
        <button
          type="button"
          onClick={listen}
          aria-label="문장 듣기"
          className="press hit -mr-1 flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-tint text-royal"
        >
          <Volume2 size={18} strokeWidth={2} aria-hidden />
        </button>
      </div>
      {makeup && (
        <p className="mt-2 flex items-center gap-1.5 text-[12.5px] font-semibold text-royal">
          <RotateCcw size={13} strokeWidth={2.25} className="shrink-0" aria-hidden />
          <span className="truncate">
            보충 {lesson.reps}번씩 ·{" "}
            {lesson.repairable && lesson.deadline ? `${shortDate(lesson.deadline)}까지 하면 연속 유지` : "끝내면 이 Day 가 열려요"}
          </span>
        </p>
      )}
      <ol className="mt-3 flex gap-1" aria-label={`${lesson.sentences.length}문장 중 ${done.size}문장 연습 끝`}>
        {lesson.sentences.map((s, i) => (
          <li key={s.id} className={`h-1.5 flex-1 rounded-full ${done.has(s.id) ? "bg-royal" : i === index ? "bg-royal/40" : "bg-line"}`} />
        ))}
      </ol>

      <div className="thin-scroll -mx-3 my-3 min-h-0 flex-1 overflow-y-auto overscroll-contain px-3">
        <div className="flex min-h-full flex-col justify-center py-2">
          <p lang="en" translate="no" className="break-words text-pretty text-[25px] font-bold leading-[1.35] tracking-tight text-ink">
            {hasAttempt
              ? displayHits(sentence.en_text, match).map((h, i) => (
                  <span key={i} className={h.hit ? "text-ink" : "text-faint"}>
                    {i > 0 ? " " : ""}
                    {h.text}
                  </span>
                ))
              : sentence.en_text}
          </p>
          {/* 빼먹은 날은 처음 보는 문장일 수 있어 어순 힌트도 함께 보여 준다. */}
          {makeup && <p className="mt-2.5 break-keep text-pretty text-[16px] font-semibold leading-snug text-royal">{sentence.ko_word_order}</p>}
          <p className="mt-2.5 break-keep text-pretty text-[14px] leading-relaxed text-muted">{sentence.ko_natural}</p>
          {done.has(sentence.id) && (
            <p className="mt-2 flex items-center gap-1 text-[12.5px] font-semibold text-royal">
              <Check size={14} strokeWidth={2.5} aria-hidden />
              {makeup ? "보충을 끝낸 문장이에요." : "전에 연습을 끝낸 문장이에요. 다시 해도 좋아요."}
            </p>
          )}
        </div>
      </div>

      <div className="flex flex-col items-center gap-3">
        {target > 1 ? (
          <div className="flex items-center gap-2" role="img" aria-label={`${target}번 중 ${reps}번 말함`}>
            {Array.from({ length: target }, (_, i) => (
              <span key={i} className={`h-3.5 w-3.5 rounded-full transition-colors duration-200 ${i < reps ? "bg-royal" : "bg-line"}`} />
            ))}
            <span className="ml-1 text-[13px] font-semibold tabular-nums text-muted-strong">
              {reps}/{target}
            </span>
          </div>
        ) : (
          <p className="text-[13px] font-semibold text-muted-strong">한 번만 맞게 말하면 돼요</p>
        )}
        <div className="flex w-full max-w-[280px] items-center justify-between gap-2">
          <button
            type="button"
            onClick={() => goTo(Math.max(0, index - 1))}
            disabled={index === 0}
            aria-label="이전 문장"
            className="press flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-tint text-royal disabled:opacity-35"
          >
            <ChevronLeft size={22} strokeWidth={2} aria-hidden />
          </button>
          <button
            type="button"
            onClick={toggleMic}
            disabled={micBlocked}
            aria-label={listening ? "듣기 멈추기" : "눌러서 말하기"}
            className="press flex h-16 w-16 shrink-0 items-center justify-center rounded-full bg-royal text-surface shadow-glow disabled:opacity-40"
          >
            {listening ? <Square size={22} strokeWidth={2} fill="currentColor" aria-hidden /> : <Mic size={28} strokeWidth={2} aria-hidden />}
          </button>
          <button
            type="button"
            onClick={() => goTo(Math.min(lesson.sentences.length - 1, index + 1))}
            disabled={index + 1 >= lesson.sentences.length}
            aria-label="다음 문장"
            className="press flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-tint text-royal disabled:opacity-35"
          >
            <ChevronRight size={22} strokeWidth={2} aria-hidden />
          </button>
        </div>
        <p className="min-h-[20px] text-pretty text-center text-[13px] font-semibold text-muted-strong" aria-live="polite">
          {status}
        </p>
      </div>
    </Frame>
  );
}

function Frame({ children }: { children: ReactNode }) {
  return (
    <section aria-label="말하기 연습" className="relative flex min-h-0 flex-1 flex-col overflow-hidden rounded-stage bg-surface p-6 shadow-card [@media(max-height:599px)]:min-h-[340px]">
      {children}
    </section>
  );
}
