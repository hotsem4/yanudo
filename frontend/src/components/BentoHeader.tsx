import { CalendarDays, Flame, RotateCcw, Snowflake } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { FocusTimer } from "../hooks/useFocusTimer";
import { FOCUS_SECONDS } from "../hooks/useFocusTimer";
import { koreanDuration, mmss } from "../lib/format";
import { mascotLabel, mascotOf } from "../lib/mascot";
import { roundLabel, roundOf } from "../lib/round";
import { showToast } from "../lib/toast";
import type { DayView } from "../state/overlay";
import { Hourglass } from "./Hourglass";
import { Mascot } from "./Mascot";

interface Props {
  view: DayView | null;
  timer: FocusTimer;
  onOpenHeatmap: () => void;
}

export function BentoHeader({ view, timer, onOpenHeatmap }: Props) {
  return (
    <header className="grid grid-cols-2 gap-3">
      <HeroCard view={view} onOpen={onOpenHeatmap} />
      <TimerCard view={view} timer={timer} />
    </header>
  );
}

function HeroCard({ view, onOpen }: { view: DayView | null; onOpen: () => void }) {
  const d = view?.dashboard;
  const info = d ? roundOf(d) : null;
  // 1라운드는 "Day 5 / 66". 2라운드부터는 누적 Day 를 크게(분모 없이) 쓰고 라운드 진행은 라벨과 막대로 보인다.
  const firstRound = !info || info.round <= 1;
  const day = d?.current_day ?? 0;
  const roundDay = info?.roundDay ?? 0;
  const total = info?.length ?? 66;
  const streak = view?.streak;
  const label = info ? roundLabel(info) : "66일 습관";
  const mascot = view ? mascotOf(view) : null;
  return (
    <button
      type="button"
      onClick={onOpen}
      aria-label={`${label} 기록 열기. Day ${day}${firstRound ? ` / ${total}` : ""}, 연속 ${streak?.current ?? 0}일, 프리즈 ${streak?.freezesLeft ?? 0}개${mascot ? `. ${mascotLabel(mascot)}` : ""}`}
      className="press relative flex min-h-[132px] flex-col overflow-hidden rounded-3xl bg-hero p-4 text-left text-surface shadow-card"
    >
      <span className="flex h-6 items-center justify-between">
        <span className="text-[12px] font-medium text-surface/80">{label}</span>
        {!mascot && <CalendarDays size={16} strokeWidth={1.75} aria-hidden className="text-surface/75" />}
      </span>
      {/* 포롱이 사는 곳. 메인 화면에서는 이 배지 한 곳뿐이고, 자주 보는 자리라 움직이지 않는다. */}
      {mascot && (
        <span className="absolute right-2.5 top-1.5 flex h-[38px] w-[38px] items-center justify-center rounded-full bg-surface">
          <Mascot stage={mascot.stage} mood={mascot.mood} size={32} still />
        </span>
      )}
      <span className="mt-1 text-[26px] font-bold leading-tight tracking-tight tabular-nums">
        {view ? (
          <>
            Day {day}
            {firstRound && <span className="text-[15px] font-semibold text-surface/70"> / {total}</span>}
          </>
        ) : (
          <span className="skeleton inline-block h-7 w-24 rounded-lg bg-surface/25 align-middle" />
        )}
      </span>
      <span className="mt-2.5 h-1.5 overflow-hidden rounded-full bg-surface/25">
        <span
          className="block h-full origin-left rounded-full bg-surface transition-transform duration-300 ease-[var(--ease-out)]"
          style={{ transform: `scaleX(${total ? roundDay / total : 0})` }}
        />
      </span>
      <span className="mt-auto flex items-center justify-between pt-3 text-[12.5px] font-semibold">
        <span className="flex items-center gap-1">
          <Flame size={15} strokeWidth={2} fill={streak?.achievedToday ? "currentColor" : "none"} aria-hidden />
          <span className="tabular-nums">연속 {streak?.current ?? 0}일</span>
        </span>
        <span className="flex items-center gap-1 text-surface/90" title="남은 스트릭 프리즈">
          <Snowflake size={14} strokeWidth={2} aria-hidden />
          <span className="tabular-nums">{streak?.freezesLeft ?? 0}</span>
        </span>
      </span>
    </button>
  );
}

function TimerCard({ view, timer }: { view: DayView | null; timer: FocusTimer }) {
  const [armed, setArmed] = useState(false);
  const armTimer = useRef<number | null>(null);

  useEffect(() => () => {
    if (armTimer.current !== null) window.clearTimeout(armTimer.current);
  }, []);

  const onTap = () => {
    if (!timer.running) {
      timer.start();
      showToast("25분 집중을 시작했어요");
      return;
    }
    if (!armed) {
      setArmed(true);
      armTimer.current = window.setTimeout(() => setArmed(false), 3000);
      return;
    }
    if (armTimer.current !== null) window.clearTimeout(armTimer.current);
    setArmed(false);
    const seconds = timer.finish();
    showToast(seconds >= 60 ? `집중 ${koreanDuration(seconds)}을 기록했어요` : "1분 미만이라 기록하지 않았어요", "success");
  };

  const over = timer.running && timer.remaining <= 0;
  const big = !timer.running ? mmss(FOCUS_SECONDS) : over ? `+${mmss(timer.overtime)}` : mmss(timer.remaining);
  const caption = !timer.running ? "탭해서 집중 시작" : armed ? "한 번 더 누르면 끝나요" : over ? "초과 집중 중" : "집중 중";
  // 펼친 화면 폭이 600~699px이면 왼쪽 열이 최소폭(280px)이라 라벨 자리가 6글자 남짓이다. 그때만 짧게 쓴다.
  const shortCaption = !timer.running ? "집중 시작" : armed ? "한 번 더 탭" : over ? "초과 집중" : "집중 중";

  const left = timer.running ? Math.max(0, timer.remaining) / FOCUS_SECONDS : 1;

  const gate = view?.gate;
  const review = view?.reviewProgress;
  const reviewLine =
    gate && gate.required && !gate.open
      ? `어제 복습 ${gate.seen}/${gate.total}`
      : review && review.total > 0
        ? `복습 ${review.seen}/${review.total}`
        : "복습 대기 없음";

  return (
    <button
      type="button"
      onClick={onTap}
      aria-label={`25분 모래시계 타이머. ${caption}. ${big}`}
      className={`press flex min-h-[132px] flex-col rounded-3xl bg-surface p-4 text-left shadow-card ${armed ? "ring-2 ring-royal/40" : ""}`}
    >
      <span className="flex h-6 items-center justify-between gap-2">
        <span className="whitespace-nowrap text-[12px] font-medium leading-tight text-muted">
          <span className="unfolded:max-[699px]:hidden">{caption}</span>
          <span aria-hidden className="hidden unfolded:max-[699px]:inline">
            {shortCaption}
          </span>
        </span>
        <Hourglass size={22} remaining={left} />
      </span>
      <span className={`mt-1 text-[26px] font-bold leading-tight tracking-tight tabular-nums ${over ? "text-royal" : "text-ink"}`}>{big}</span>
      <span className="mt-2.5 h-1.5 overflow-hidden rounded-full bg-line">
        <span
          className="block h-full origin-left rounded-full bg-royal transition-transform duration-1000 ease-linear"
          style={{ transform: `scaleX(${1 - left})` }}
        />
      </span>
      <span className="mt-auto flex items-center gap-1 pt-3 text-[12.5px] font-semibold text-muted">
        <RotateCcw size={13} strokeWidth={2} aria-hidden className="text-royal" />
        <span className="tabular-nums">{view ? reviewLine : "불러오는 중…"}</span>
      </span>
    </button>
  );
}
