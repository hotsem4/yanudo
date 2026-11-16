import { AnimatePresence, motion, useReducedMotion, type PanInfo } from "framer-motion";
import { BookmarkCheck, BookmarkPlus, Check, CheckCheck, DownloadCloud, Keyboard, Lock, Mic, RotateCcw, WifiOff } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { MarkStatus, Sentence, Session, SessionData } from "../api/types";
import type { EngineSnapshot } from "../audio/engine";
import { audioEngine, useEngine } from "../audio/useEngine";
import { useCardDwell } from "../hooks/useCardDwell";
import { mascotOf, seenStage } from "../lib/mascot";
import type { RevealFilter } from "../lib/prefs";
import { sentenceWords } from "../lib/vocab";
import type { DayView } from "../state/overlay";
import type { StudyState } from "../state/store";
import { studyStore } from "../state/store";
import { Mascot } from "./Mascot";
import { initialDisplay, SpeakCheck, type SpeakFeedback } from "./SpeakCheck";
import { speakControl, useSpeakControl } from "../lib/speakControl";
import { displayHits } from "../lib/wordMatch";
import { Waveform } from "./Waveform";

interface Props {
  study: StudyState;
  tab: Session;
  filter: RevealFilter;
  /** 시트·모닝 락에 가려진 동안은 카드 열람을 세지 않는다. */
  covered: boolean;
  onGoReview: () => void;
  /** 단어 담기 시트를 연다. */
  onPickWords: (sentence: Sentence) => void;
}

/** 학습 카드 영역. 데이터 4상태(로딩·오류·비어 있음·정상)를 모두 그린다(hallmark 앱 화면 규칙). */
export function StudyStage({ study, tab, filter, covered, onGoReview, onPickWords }: Props) {
  const eng = useEngine();
  if (study.kind === "booting") return <SkeletonCard />;
  if (study.kind === "error") return <ErrorCard message={study.message} />;

  const view = study.view;
  const session = tab === "TODAY" ? view.today : view.review;
  if (!session || session.sentences.length === 0) return <EmptyCard view={view} tab={tab} />;

  const items = session.sentences;
  const synced = eng.session === tab && eng.items.length === items.length;
  const index = synced ? Math.min(eng.cursor, items.length - 1) : 0;
  return (
    <StudyCard
      key={tab}
      view={view}
      session={session}
      sentence={items[index]}
      index={index}
      eng={eng}
      filter={filter}
      covered={covered}
      online={study.sync.online}
      onGoReview={onGoReview}
      onPickWords={onPickWords}
    />
  );
}

interface CardProps {
  view: DayView;
  session: SessionData;
  sentence: Sentence;
  index: number;
  eng: EngineSnapshot;
  filter: RevealFilter;
  covered: boolean;
  online: boolean;
  onGoReview: () => void;
  onPickWords: (sentence: Sentence) => void;
}

const REVEAL_MS = 2500;
/** 화면 아래에서 독(72px)과 그 아래·위 여백이 차지하는 높이. */
const DOCK_CLEARANCE = 72 + 16 + 20;
const SWIPE_DISTANCE = 80;
const SWIPE_VELOCITY = 110; // px/s (= 0.11px/ms, emil-design-eng)
/** 손으로 넘긴 카드가 빠져나가는 거리. 카드 폭의 절반쯤이면 fade 와 함께 화면 밖으로 나간 것처럼 보인다. */
const FLING_PX = 200;

interface SlideDir {
  dir: number;
  fling: number;
}

function StudyCard({ view, session, sentence, index, eng, filter, covered, online, onGoReview, onPickWords }: CardProps) {
  const reduce = useReducedMotion();
  const locked = session.session === "TODAY" && !view.gate.open;
  const total = session.sentences.length;

  useCardDwell(sentence.id, session.session, !locked && !covered);

  const [revealedId, setRevealedId] = useState<number | null>(null);
  const revealTimer = useRef<number | null>(null);
  useEffect(() => () => {
    if (revealTimer.current !== null) window.clearTimeout(revealTimer.current);
  }, []);

  const prevIndex = useRef(index);
  const direction = index >= prevIndex.current ? 1 : -1;
  // 손으로 넘겼을 때 카드가 손을 뗀 자리에서 그 방향 그대로 빠져나가게 할 거리. 0이면 버튼·키보드로 넘긴 것.
  const flingX = useRef(0);
  const motionDir: SlideDir = { dir: direction, fling: flingX.current };
  useEffect(() => {
    prevIndex.current = index;
    flingX.current = 0;
  }, [index]);

  // 따라 말하기 결과(오늘 탭). 문장이 바뀌면 SpeakCheck 가 새로 붙어 다시 알려 준다.
  const [feedback, setFeedback] = useState<(SpeakFeedback & { id: number }) | null>(null);
  const fb = feedback?.id === sentence.id ? feedback : null;

  // 오늘 탭(따라 말하기)에서는 SpeakCheck 의 두 단계 흐름만 영어를 보일지 정한다. 가리기 필터·역할극 가림이
  // 끼어들면 "보고 말하기인데 가려짐", "가리고 말하기인데 보임"이 생긴다. 해석·어순은 늘 보인다(가리고 말할 때의 단서).
  const todayFlow = session.session === "TODAY" && !locked;
  const engineHides = eng.hideEn && eng.item?.id === sentence.id;
  // 새 문장의 첫 피드백이 오기 전 한 프레임도 가릴 문장은 가린다(답이 번쩍 보이지 않게).
  const rawDisplay = fb?.display ?? (todayFlow ? initialDisplay(sentence.id, view.todayProgress.spokenIds.has(sentence.id)) : "show");
  // 가리고 말하는 단계에서 카드를 길게 누르고 있는 동안만 영어를 보여 준다(손을 떼면 다시 가린다).
  const [holding, setHolding] = useState(false);
  useEffect(() => setHolding(false), [sentence.id]);
  const holdPeek = todayFlow && (rawDisplay === "hide" || rawDisplay === "hits");
  const peeking = holdPeek && holding;
  const enDisplay = peeking ? "show" : rawDisplay;
  const revealed = !todayFlow && revealedId === sentence.id;
  const hideEn = todayFlow ? enDisplay === "hide" : !revealed && (filter !== "all" || engineHides);
  const hideOrder = !todayFlow && !revealed && filter === "korean";
  const hideNatural = !todayFlow && !revealed && filter === "hint";
  const anyHidden = hideEn || hideOrder || hideNatural;
  // 탭으로 엿보기는 복습 탭만. 오늘 탭의 가림은 외워서 말하는 단계라 엿볼 수 없다.
  const peekable = !todayFlow && (anyHidden || revealed);

  // 커버 화면은 헤더·할 일·탭이 위를 차지해 문장과 해석·어순이 하단 독 뒤로 내려간다.
  // 듣기·말하기를 시작하면 문장 블록이 독 위에 오도록 스크롤한다(자동으로 처음 열 때는 움직이지 않는다).
  const speak = useSpeakControl();
  const sentenceRef = useRef<HTMLDivElement>(null);
  const engaged = todayFlow && (speak.listening || speak.hearing);
  useEffect(() => {
    if (!engaged) return;
    const el = sentenceRef.current;
    if (!el) return;
    const dockTop = window.innerHeight - DOCK_CLEARANCE;
    const r = el.getBoundingClientRect();
    if (r.bottom <= dockTop) return;
    // 위로 넘치지 않을 만큼만(카드 머리는 남긴다).
    const by = Math.min(r.bottom - dockTop, Math.max(0, r.top - 16));
    if (by > 0) window.scrollBy({ top: by, behavior: reduce ? "auto" : "smooth" });
  }, [engaged, sentence.id]); // eslint-disable-line react-hooks/exhaustive-deps

  /** 문장 이동. 오늘 탭이면 옮긴 문장을 한 번 들려준다(듣고 따라 말하는 흐름). */
  const moveTo = (i: number) => {
    if (locked || i < 0 || i >= total) return;
    audioEngine.go(i);
    if (todayFlow) speakControl.hear(session.sentences[i]);
  };

  const onTap = () => {
    if (!peekable) return;
    if (revealed) {
      setRevealedId(null);
      return;
    }
    if (!anyHidden) return;
    setRevealedId(sentence.id);
    if (revealTimer.current !== null) window.clearTimeout(revealTimer.current);
    revealTimer.current = window.setTimeout(() => setRevealedId(null), REVEAL_MS);
  };

  const onDragEnd = (_: unknown, info: PanInfo) => {
    const { offset, velocity } = info;
    if ((offset.x < -SWIPE_DISTANCE || (velocity.x < -SWIPE_VELOCITY && offset.x < -24)) && index < total - 1) {
      flingX.current = -FLING_PX;
      moveTo(index + 1);
    } else if ((offset.x > SWIPE_DISTANCE || (velocity.x > SWIPE_VELOCITY && offset.x > 24)) && index > 0) {
      flingX.current = FLING_PX;
      moveTo(index - 1);
    }
  };

  const banner = bannerText(view, session);

  return (
    <section
      aria-label={`${session.session === "TODAY" ? "오늘의 문장" : "복습 문장"} ${index + 1} / ${total}`}
      className="relative flex min-h-[340px] flex-1 flex-col overflow-hidden rounded-stage bg-surface p-6 shadow-card"
    >
      {/* 잠긴 동안 카드 내용은 잠금 패널 아래에 깔린 배경일 뿐이라 보조 기술에서도 뺀다. */}
      <div className="flex items-center justify-between gap-2" aria-hidden={locked || undefined}>
        <span className="flex min-w-0 items-center gap-2.5">
          <SpeakerChip speaker={sentence.speaker} />
          <span className="shrink-0 whitespace-nowrap text-[12.5px] font-semibold tabular-nums text-muted">
            {index + 1} / {total}
          </span>
        </span>
        <span className="flex shrink-0 items-center gap-3">
          <WordsButton sentence={sentence} view={view} disabled={locked} onOpen={() => onPickWords(sentence)} />
          <MarkToggle sentence={sentence} disabled={locked} />
        </span>
      </div>

      <ProgressRail
        sentences={session.sentences}
        index={index}
        spoken={session.session === "TODAY" ? view.todayProgress.spokenIds : null}
        disabled={locked}
        onGo={moveTo}
      />

      {banner && <p aria-hidden={locked || undefined} className="mt-1 self-start rounded-full bg-tint px-3 py-1.5 text-[12px] font-medium text-royal">{banner}</p>}
      {/* 따라 말하기 단계와 받아 적기 전환. 카드 아래쪽은 커버 화면에서 독에 가리므로 위에 둔다. */}
      {fb && todayFlow && (fb.step || fb.canSwitch) && (
        <div className="mt-1 flex min-h-11 items-center justify-between gap-2">
          {fb.step ? <span className="shrink-0 rounded-full bg-canvas px-2.5 py-1 text-[11.5px] font-bold text-muted-strong">{fb.step}</span> : <span />}
          {fb.canSwitch && (
            <button
              type="button"
              onClick={fb.toggleTyping}
              disabled={covered}
              className="press -mr-2 flex h-11 shrink-0 items-center gap-1.5 rounded-full px-2 text-[12.5px] font-semibold text-muted"
            >
              {fb.typing ? <Mic size={14} strokeWidth={2} aria-hidden /> : <Keyboard size={14} strokeWidth={2} aria-hidden />}
              {fb.typing ? "말하기로 돌아가기" : "받아 적기"}
            </button>
          )}
        </div>
      )}
      {/* 따라 말하기 안내 띠. 읽기 프로그램이 첫 문구도 읽도록 자리는 늘 두고 내용만 바꾼다. */}
      <p
        aria-live="polite"
        className={
          fb && todayFlow
            ? `mt-1 self-start break-keep rounded-2xl px-3 py-1.5 text-[12.5px] font-semibold leading-snug ${
                fb.tone === "pass" ? "bg-royal text-surface" : fb.tone === "error" ? "bg-canvas text-ink" : "bg-tint text-royal"
              }`
            : "sr-only"
        }
      >
        {/* 연속 재생(알림 자동 재생 등) 중에는 독이 재생 독이라 마이크가 없다. */}
        {fb && todayFlow
          ? eng.playing
            ? "연속 재생 중이에요. 멈추면 가운데 버튼이 마이크로 바뀌어요"
            : view.stale && fb.tone !== "listening"
              ? // 날이 바뀌었는데 서버에 못 닿아 아직 어제 문장이다. 통과해도 오늘 달성에 세지 않는다.
                "날짜가 바뀌어 아직 어제 문장이에요. 연결되면 오늘 문장으로 바뀌어요(지금 통과는 오늘 달성에 세지 않아요)"
              : fb.status
          : ""}
      </p>

      <div ref={sentenceRef} className={`relative flex flex-1 flex-col justify-center py-8 ${locked ? "opacity-30" : ""}`} aria-hidden={locked}>
        <AnimatePresence initial={false} mode="popLayout" custom={motionDir}>
          <motion.div
            key={sentence.id}
            custom={motionDir}
            variants={{
              // 손으로 넘겼으면 새 카드는 반대편에서 조금 더 멀리서 들어온다. 버튼·키보드는 짧게.
              enter: (m: SlideDir) => ({ x: reduce ? 0 : m.fling ? -Math.sign(m.fling) * 56 : m.dir * 24, opacity: 0, filter: "blur(2px)" }),
              center: { x: 0, opacity: 1, filter: "blur(0px)" },
              // 손으로 넘겼으면 끌던 방향 그대로 밀려 나간다(가운데로 되돌아오며 사라지면 손을 뗄 때 반대로 튀어 보인다).
              exit: (m: SlideDir) => ({ x: reduce ? 0 : m.fling ? m.fling : m.dir * -24, opacity: 0, filter: "blur(2px)" }),
            }}
            initial="enter"
            animate="center"
            exit="exit"
            transition={{ duration: 0.2, ease: [0.23, 1, 0.32, 1] }}
            drag={locked ? false : "x"}
            dragSnapToOrigin
            dragElastic={0.18}
            dragMomentum={false}
            onDragEnd={onDragEnd}
            onTap={onTap}
            onPointerDown={() => holdPeek && setHolding(true)}
            onPointerUp={() => setHolding(false)}
            onPointerCancel={() => setHolding(false)}
            onPointerLeave={() => setHolding(false)}
            // 길게 누를 때 뜨는 복사 메뉴·확대를 막는다.
            onContextMenu={(e) => holdPeek && e.preventDefault()}
            onKeyUp={(e) => {
              if (e.key === " " || e.key === "Enter") setHolding(false);
            }}
            role={peekable || holdPeek ? "button" : "group"}
            tabIndex={locked ? -1 : 0}
            aria-label={
              peekable && anyHidden
                ? "가린 문장 잠깐 보기"
                : holdPeek
                  ? "문장 카드. 누르고 있는 동안 영어가 보여요. 좌우 화살표로 문장 이동"
                  : "문장 카드. 좌우 화살표로 문장 이동"
            }
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                // 오늘 탭은 누르고 있는 동안만 보인다(키보드도 같게).
                if (holdPeek) setHolding(true);
                else onTap();
              } else if (e.key === "ArrowRight") moveTo(index + 1);
              else if (e.key === "ArrowLeft") moveTo(index - 1);
            }}
            className={`touch-pan-y select-none rounded-2xl [-webkit-touch-callout:none] ${peekable || holdPeek ? "cursor-pointer" : ""} ${locked ? "blur-[10px]" : ""}`}
          >
            <p
              className="hide-blur break-words text-pretty text-[25px] font-bold leading-[1.35] tracking-tight text-ink"
              data-hidden={hideEn}
              // 오늘 탭에서 가린 영어는 읽기 프로그램도 읽지 않는다(외워서 말하는 단계).
              aria-hidden={(todayFlow && hideEn) || undefined}
              lang="en"
              translate="no"
            >
              {todayFlow && fb?.result && (enDisplay === "full" || enDisplay === "hits" || enDisplay === "live")
                ? displayHits(sentence.en_text, fb.result).map((h, i) => (
                    <span key={i}>
                      {i > 0 ? " " : ""}
                      {h.hit ? (
                        <span className="text-ink">{h.text}</span>
                      ) : enDisplay === "live" ? (
                        // 보고 말하는 중: 아직 못 맞힌 단어는 옅게, 말한 단어가 진해진다(듣고 있다는 반응).
                        <span className="text-muted">{h.text}</span>
                      ) : enDisplay === "hits" ? (
                        // 가리고 말하는 중: 아직 맞히지 못한 단어는 자리만 보인다(답을 미리 보지 않게).
                        <span aria-hidden className="inline-block h-[0.9em] translate-y-[0.12em] rounded-md bg-line" style={{ width: `${Math.max(2, h.text.length) * 0.5}em` }} />
                      ) : (
                        <span className="text-faint underline decoration-faint decoration-dotted underline-offset-4">{h.text}</span>
                      )}
                    </span>
                  ))
                : sentence.en_text}
            </p>
            {todayFlow && fb?.result && enDisplay === "full" && (
              <span className="sr-only">
                맞힌 단어 {fb.result.hits.filter((h) => h.hit).length}개, 놓친 단어:{" "}
                {fb.result.hits.filter((h) => !h.hit).map((h) => h.word).join(", ") || "없음"}
              </span>
            )}
            <p className="hide-blur -mb-1.5 mt-4 break-keep text-[16px] font-semibold leading-normal text-royal" data-hidden={hideOrder}>
              <ChunkedOrder text={sentence.ko_word_order} />
            </p>
            <p className="hide-blur mt-2.5 break-keep text-pretty text-[14px] leading-relaxed text-muted" data-hidden={hideNatural}>
              {sentence.ko_natural}
            </p>
            {peekable && anyHidden && <span className="sr-only">가려진 문장이 있어요. 카드를 탭하면 잠깐 보여요.</span>}
            {todayFlow && hideEn && (
              <span className="sr-only">
                영어 문장을 가렸어요. 어순과 해석을 보고 영어로 {fb?.typing ? "적어" : "말해"} 보세요. 카드를 길게 누르는 동안 보여요.
              </span>
            )}
          </motion.div>
        </AnimatePresence>
      </div>

      {todayFlow && (
        <SpeakCheck
          key={sentence.id}
          sentence={sentence}
          passed={view.todayProgress.spokenIds.has(sentence.id)}
          remainingAfter={session.sentences.filter((s) => s.id !== sentence.id && !view.todayProgress.spokenIds.has(s.id)).length}
          disabled={covered}
          onFeedback={(f) => setFeedback({ ...f, id: sentence.id })}
          onPassed={(typed) => {
            // 아직 통과하지 못한 다음 문장으로 넘기고(뒤에 없으면 앞에서 찾는다) 그 문장을 한 번 들려준다.
            // 받아 적기로 통과했으면 소리를 내지 않는다(소리 내기 어려운 곳일 수 있다).
            const list = session.sentences;
            const order = [...list.slice(index + 1), ...list.slice(0, index)];
            const next = order.find((s) => s.id !== sentence.id && !view.todayProgress.spokenIds.has(s.id));
            if (!next) return false;
            audioEngine.go(list.indexOf(next));
            if (!typed) speakControl.hear(next);
            return true;
          }}
        />
      )}

      <div aria-hidden={locked || undefined}>
        {/* 오늘 탭에서는 필터 힌트("탭해서 확인")를 띄우지 않는다. 할 일은 안내 띠 하나가 말한다. */}
        <StatusLine eng={eng} sentence={sentence} filter={filter} anyHidden={!todayFlow && anyHidden} online={online} />
      </div>

      {locked && <LockPanel view={view} onGoReview={onGoReview} />}
    </section>
  );
}

function bannerText(view: DayView, session: SessionData): string | null {
  const d = view.dashboard;
  if (session.session === "REVIEW" && session.preview) return "앞선 복습 레슨이 없어 오늘 문장으로 미리 복습해요";
  if (session.session === "TODAY" && d.content_missing && d.lesson_day > 0) {
    return `복습 순환이에요. Day ${d.lesson_day} 문장을 다시 익혀요`;
  }
  if (view.stale) return "날짜가 바뀌었어요. 연결되면 오늘 문장으로 바뀌어요";
  if (session.session === "TODAY" && view.restDay?.declaredToday && !view.streak.achievedToday) {
    return "오늘은 어려운 날로 알렸어요. 내일 문장마다 1번씩 보충하면 이어져요";
  }
  return null;
}

/**
 * 어순 직역의 시그니처: " / " 로 나뉜 덩어리를 하나씩 연블루 조각으로 놓아 영어 어순이 블록으로 보이게 한다.
 * 조각은 inline-block 이라 줄이 모자라면 조각째 다음 줄로 넘어가고, 어절 가운데서 끊기지 않는다(break-keep).
 */
function ChunkedOrder({ text }: { text: string }) {
  const chunks = text.split("/").map((c) => c.trim()).filter(Boolean);
  return (
    <>
      {chunks.map((chunk, i) => (
        <span key={i}>
          {i > 0 && " "}
          <span className="mb-1.5 inline-block max-w-full rounded-[10px] bg-tint px-2 py-1 align-top">{chunk}</span>
        </span>
      ))}
    </>
  );
}

/**
 * 문장 진행 레일. 문장마다 한 칸이고, 누르면 그 문장으로 간다(칸마다 높이 44px의 누름 영역).
 * 오늘 탭이면 따라 말하기로 통과한 문장을 royal 로 채우고, 복습 탭이면 암기한 문장을 frost 로 칠한다. 지금 위치는 두껍다.
 * 문장 지도가 보이는 화면(펼친 화면, 세로 640px 이상)에서는 지도가 같은 일을 하므로 숨긴다(.rail-no-map).
 */
function ProgressRail({
  sentences,
  index,
  spoken,
  disabled,
  onGo,
}: {
  sentences: readonly Sentence[];
  index: number;
  spoken: ReadonlySet<number> | null;
  disabled: boolean;
  onGo: (i: number) => void;
}) {
  // 누적 복습처럼 문장이 많으면 칸 폭이 몇 px 이라 누를 수 없다. 표시만 하고 이동은 스와이프·독에 맡긴다.
  if (sentences.length > 12) {
    return (
      <div aria-hidden className="rail-no-map mt-4 mb-1 gap-[3px]">
        {sentences.map((s, i) => {
          const done = spoken ? spoken.has(s.id) : s.status === "MEMORIZED";
          return (
            <span
              key={s.id}
              className={`block min-w-0 flex-1 rounded-full ${i === index ? "h-1.5" : "h-1"} ${
                done ? (i === index ? "bg-royal" : spoken ? "bg-royal" : "bg-frost") : i === index ? "bg-royal/45" : "bg-line"
              }`}
            />
          );
        })}
      </div>
    );
  }
  return (
    <div role="group" aria-label="문장 진행" className="rail-no-map mt-0.5 -mb-1 gap-[3px]">
      {sentences.map((s, i) => {
        const current = i === index;
        const done = spoken ? spoken.has(s.id) : s.status === "MEMORIZED";
        return (
          <button
            key={s.id}
            type="button"
            disabled={disabled}
            onClick={() => onGo(i)}
            aria-current={current ? "true" : undefined}
            aria-label={`${i + 1}번 문장${done ? (spoken ? ", 통과" : ", 암기 완료") : ""}`}
            className="flex h-11 min-w-0 flex-1 items-center"
          >
            <span
              className={`block w-full rounded-full transition-[background-color,height] duration-200 ${current ? "h-1.5" : "h-1"} ${
                done ? (spoken ? "bg-royal" : current ? "bg-royal" : "bg-frost") : current ? "bg-royal/45" : "bg-line"
              }`}
            />
          </button>
        );
      })}
    </div>
  );
}

function SpeakerChip({ speaker }: { speaker: "A" | "B" }) {
  const isA = speaker === "A";
  return (
    <span
      className={`flex h-7 items-center gap-1.5 rounded-full pl-1 pr-3 text-[12px] font-semibold ${
        isA ? "bg-tint text-royal" : "bg-royal text-surface"
      }`}
    >
      <span
        className={`flex h-5 w-5 items-center justify-center rounded-full text-[11px] font-bold ${
          isA ? "bg-royal text-surface" : "bg-surface text-royal"
        }`}
      >
        {speaker}
      </span>
      <span className="[@media(min-width:600px)_and_(max-width:699px)]:sr-only">{isA ? "Andrew" : "Ava"}</span>
    </span>
  );
}

/** 단어 담기 버튼. 이 문장에서 담은 단어가 있으면 채워진 책갈피로 바뀐다. 카드 탭 영역 밖(윗줄)에 둔다. */
function WordsButton({ sentence, view, disabled, onOpen }: { sentence: Sentence; view: DayView; disabled: boolean; onOpen: () => void }) {
  const saved = new Set((view.vocab ?? []).map((v) => v.term_key));
  const keys = [...(sentence.terms ?? []).map((t) => t.term_key), ...sentenceWords(sentence.en_text, sentence.terms ?? []).map((w) => w.key)];
  const count = keys.filter((k) => saved.has(k)).length;
  const Icon = count > 0 ? BookmarkCheck : BookmarkPlus;
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onOpen}
      aria-label={count > 0 ? `단어 담기. 이 문장에서 ${count}개 담음` : "단어 담기"}
      className={`press hit flex h-9 w-9 items-center justify-center rounded-full disabled:opacity-40 ${
        count > 0 ? "bg-royal text-surface" : "bg-tint text-royal"
      }`}
    >
      <Icon size={17} strokeWidth={2} aria-hidden />
    </button>
  );
}

const NEXT_MARK: Record<MarkStatus, MarkStatus> = { NEW: "MEMORIZED", MEMORIZED: "PRACTICE", PRACTICE: "MEMORIZED" };

function MarkToggle({ sentence, disabled }: { sentence: Sentence; disabled: boolean }) {
  const status = sentence.status;
  const look =
    status === "MEMORIZED"
      ? { cls: "bg-royal text-surface", label: "암기 완료", Icon: CheckCheck }
      : status === "PRACTICE"
        ? { cls: "bg-surface text-muted shadow-card", label: "다시 연습", Icon: RotateCcw }
        : { cls: "bg-tint text-royal", label: "암기", Icon: Check };
  return (
    <button
      type="button"
      disabled={disabled}
      aria-pressed={status === "MEMORIZED"}
      aria-label={`암기 상태: ${look.label}. 눌러서 바꾸기`}
      onClick={() => studyStore.record({ type: "MARK", sentence_id: sentence.id, status: NEXT_MARK[status] })}
      className={`press hit flex h-9 shrink-0 items-center gap-1 whitespace-nowrap rounded-full px-3 text-[12.5px] font-semibold disabled:opacity-40 ${look.cls}`}
    >
      <AnimatePresence initial={false} mode="popLayout">
        <motion.span
          key={status}
          initial={{ scale: 0.25, opacity: 0, filter: "blur(4px)" }}
          animate={{ scale: 1, opacity: 1, filter: "blur(0px)" }}
          exit={{ scale: 0.25, opacity: 0, filter: "blur(4px)" }}
          transition={{ type: "spring", duration: 0.3, bounce: 0 }}
          className="flex"
        >
          <look.Icon size={15} strokeWidth={2} aria-hidden />
        </motion.span>
      </AnimatePresence>
      {look.label}
    </button>
  );
}

interface StatusProps {
  eng: EngineSnapshot;
  sentence: Sentence;
  filter: RevealFilter;
  anyHidden: boolean;
  online: boolean;
}

function StatusLine({ eng, sentence, filter, anyHidden, online }: StatusProps) {
  const current = eng.item?.id === sentence.id;
  let left: JSX.Element;
  if (current && eng.phase === "speaking") {
    left = (
      <>
        <Waveform />
        <span>{eng.source === "speech" ? "기기 음성으로 듣는 중" : "듣는 중"}</span>
      </>
    );
  } else if (current && eng.phase === "holding" && eng.hold) {
    left = (
      <>
        <Waveform calm />
        <span>{eng.hideEn ? "B 대사를 말해 보세요" : "따라 말해 보세요"}</span>
        <HoldCountdown totalMs={eng.hold.totalMs} />
      </>
    );
  } else if (current && eng.phase === "loading") {
    left = <span>불러오는 중…</span>;
  } else {
    // 가린 카드에서는 할 일을 알려 준다: 보이는 쪽을 보고 영어로 말해 본 뒤 탭해서 확인.
    const hint = filter === "korean" ? "해석을 보고 영어로 말한 뒤, 탭해서 확인" : "어순을 보고 영어로 말한 뒤, 탭해서 확인";
    left = <span className="truncate tabular-nums">{anyHidden && filter !== "all" ? hint : `Day ${sentence.day} 문장`}</span>;
  }

  const { ready, total } = eng.preload;
  return (
    <div className="flex h-7 items-center justify-between gap-3 text-[12.5px] font-medium text-muted" aria-live="polite">
      <span className="flex min-w-0 items-center gap-2">{left}</span>
      {!online ? (
        <span className="flex shrink-0 items-center gap-1">
          <WifiOff size={14} strokeWidth={1.75} aria-hidden />
          오프라인
        </span>
      ) : total > 0 && ready < total ? (
        <span className="flex shrink-0 items-center gap-1 tabular-nums" title="오프라인 재생 준비">
          <DownloadCloud size={14} strokeWidth={1.75} aria-hidden />
          준비 {ready}/{total}
        </span>
      ) : null}
    </div>
  );
}

/** 남은 대기 시간. 무음 트랙의 currentTime(엔진의 미디어 시계)을 rAF 로 읽어 DOM 만 갱신한다. */
function HoldCountdown({ totalMs }: { totalMs: number }) {
  const ref = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    let raf = 0;
    const tick = () => {
      const left = Math.max(0, (totalMs * (1 - audioEngine.holdProgress())) / 1000);
      if (ref.current) ref.current.textContent = `${left.toFixed(1)}초`;
      raf = requestAnimationFrame(tick);
    };
    tick();
    return () => cancelAnimationFrame(raf);
  }, [totalMs]);
  return <span ref={ref} className="tabular-nums text-royal" />;
}

const GATE_R = 33;
const GATE_C = 2 * Math.PI * GATE_R;

/** 잠금 패널. 어제 복습 진행을 자물쇠를 감싼 링으로 보여 준다(refs/9704 의 원형 게이지). */
function LockPanel({ view, onGoReview }: { view: DayView; onGoReview: () => void }) {
  const g = view.gate;
  const ratio = g.total > 0 ? g.seen / g.total : 0;
  return (
    <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-3 rounded-stage bg-surface/75 p-8 text-center backdrop-blur-sm">
      <span className="relative flex h-[76px] w-[76px] items-center justify-center text-royal">
        <svg aria-hidden className="absolute inset-0 -rotate-90" viewBox="0 0 76 76">
          <circle cx="38" cy="38" r={GATE_R} fill="none" strokeWidth="5" className="stroke-line" />
          <circle
            cx="38"
            cy="38"
            r={GATE_R}
            fill="none"
            strokeWidth="5"
            strokeLinecap="round"
            className="stroke-royal transition-[stroke-dashoffset] duration-300 ease-[var(--ease-out)]"
            style={{ strokeDasharray: GATE_C, strokeDashoffset: GATE_C * (1 - ratio) }}
          />
        </svg>
        <Lock size={24} strokeWidth={2} aria-hidden />
      </span>
      {/* 오래 쉬고 돌아오면 "어제" 레슨이 처음 보는 레슨일 수 있어 Day 로 부른다. */}
      <h2 className="mt-1 text-balance text-[17px] font-bold text-ink">Day {g.yesterdayDay} 문장부터 복습해요</h2>
      <p className="text-pretty text-[14px] leading-relaxed text-muted">
        Day {g.yesterdayDay} 복습 <span className="font-semibold tabular-nums text-royal">{g.seen}/{g.total}</span>을 마치면
        <br />
        오늘의 10문장이 열려요
      </p>
      <button type="button" onClick={onGoReview} className="press mt-1 h-12 rounded-full bg-royal px-6 text-[14px] font-semibold text-surface shadow-glow">
        누적 복습으로 가기
      </button>
    </div>
  );
}

function CardShell({ children }: { children: React.ReactNode }) {
  return <section className="flex min-h-[340px] flex-1 flex-col rounded-stage bg-surface p-6 shadow-card">{children}</section>;
}

function SkeletonCard() {
  return (
    <CardShell>
      <div aria-hidden className="flex items-center justify-between">
        <span className="skeleton h-7 w-24 rounded-full bg-line" />
        <span className="skeleton h-9 w-20 rounded-full bg-line" />
      </div>
      <div aria-hidden className="flex flex-1 flex-col justify-center gap-3 py-8">
        <span className="skeleton h-7 w-[92%] rounded-lg bg-line" />
        <span className="skeleton h-7 w-[64%] rounded-lg bg-line" />
        <span className="skeleton mt-3 h-5 w-[78%] rounded-lg bg-tint" />
        <span className="skeleton h-4 w-[52%] rounded-lg bg-line" />
      </div>
      <span className="sr-only">문장을 불러오는 중이에요</span>
    </CardShell>
  );
}

function ErrorCard({ message }: { message: string }) {
  return (
    <CardShell>
      <div className="flex flex-1 flex-col items-center justify-center gap-3 text-center">
        {/* 서버 값을 못 받았으니 마지막으로 본 단계의 포롱이 졸고 있다. */}
        <Mascot stage={seenStage() ?? 1} mood="sleepy" size={76} />
        <h2 className="text-[17px] font-bold text-ink">서버에 연결하지 못했어요</h2>
        <p className="text-pretty text-[14px] leading-relaxed text-muted">
          PC에서 서버가 켜져 있는지, 폰의 Tailscale이 연결돼 있는지 확인해 주세요.
          <span className="mt-1 block text-[12.5px]">{message}</span>
        </p>
        <button
          type="button"
          onClick={() => void studyStore.refresh()}
          className="press mt-1 h-11 rounded-full bg-royal px-5 text-[14px] font-semibold text-surface shadow-glow"
        >
          다시 시도
        </button>
      </div>
    </CardShell>
  );
}

function EmptyCard({ view, tab }: { view: DayView; tab: Session }) {
  const noContent = view.dashboard.lesson_day === 0;
  return (
    <CardShell>
      <div className="flex flex-1 flex-col items-center justify-center gap-3 text-center">
        <Mascot stage={mascotOf(view).stage} mood="wait" size={76} />
        <h2 className="text-[17px] font-bold text-ink">{noContent ? "아직 레슨이 없어요" : tab === "REVIEW" ? "복습할 문장이 없어요" : "오늘 문장이 없어요"}</h2>
        <p className="text-pretty text-[14px] leading-relaxed text-muted">
          {noContent
            ? "PC에서 레슨 JSON을 임포트하면 여기에 나타나요."
            : "연결되면 문장을 다시 받아 올게요."}
        </p>
        {!noContent && (
          <button
            type="button"
            onClick={() => void studyStore.refresh()}
            className="press mt-1 h-11 rounded-full bg-tint px-5 text-[14px] font-semibold text-royal"
          >
            다시 받기
          </button>
        )}
      </div>
    </CardShell>
  );
}
