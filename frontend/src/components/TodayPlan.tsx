import { Check, ChevronRight, Flame, Pause, Play } from "lucide-react";
import type { Session } from "../api/types";
import { useEngine } from "../audio/useEngine";
import type { DayView } from "../state/overlay";

/** 오늘 할 일의 한 단계. done 이면 체크, current 면 누르는 버튼, 아니면 흐린 글자. */
interface Step {
  session: Session;
  label: string;
  seen: number;
  total: number;
  done: boolean;
}

/**
 * 오늘 해야 할 단계(SPEC 5장 "오늘 할 일"). 어제 복습이 있는 날은 복습 → 오늘 10문장 → 달성, 없는 날(Day 1)은 오늘 10문장 → 달성.
 * 화면만 보고도 순서를 알 수 있게 하려는 안내다. 판단은 모두 서버가 준 값(게이트, 진행, 달성)으로 한다.
 * 레슨이 없거나 66일이 끝났으면 null.
 */
export function planSteps(view: DayView | null): Step[] | null {
  if (!view || view.dashboard.lesson_day === 0 || view.dashboard.program_complete) return null;
  const steps: Step[] = [];
  if (view.gate.required) {
    steps.push({ session: "REVIEW", label: "어제 복습", seen: view.gate.seen, total: view.gate.total, done: view.gate.open });
  }
  const t = view.todayProgress;
  steps.push({ session: "TODAY", label: "오늘 10문장", seen: Math.max(t.audioSeen, t.cardSeen), total: t.total, done: view.streak.achievedToday });
  return steps;
}

interface Props {
  view: DayView | null;
  /** 이 단계를 시작(또는 이미 재생 중이면 멈춤)한다. 탭 이동·학습 방식 설정·재생은 App 이 한다. */
  onStart: (session: Session) => void;
}

/** 헤더 아래 한 줄짜리 단계 표시. 지금 할 단계가 곧 시작 버튼이다. */
export function TodayPlan({ view, onStart }: Props) {
  const eng = useEngine();
  const steps = planSteps(view);
  if (!view || !steps) return null;
  const currentIndex = steps.findIndex((s) => !s.done);
  const achieved = currentIndex === -1;

  return (
    <nav aria-label="오늘 할 일" className="flex h-11 min-w-0 items-center gap-1.5 unfolded:gap-1">
      {steps.map((step, i) => {
        const current = i === currentIndex;
        const playingHere = current && eng.playing && eng.session === step.session;
        return (
          <span key={step.session} className="flex min-w-0 items-center gap-1.5 unfolded:gap-1">
            {i > 0 && <ChevronRight size={14} strokeWidth={2} className="shrink-0 text-faint" aria-hidden />}
            {current ? (
              <button
                type="button"
                onClick={() => onStart(step.session)}
                aria-label={`${playingHere ? "멈추기" : "시작하기"}: ${step.label} ${step.seen}/${step.total}`}
                className="press flex h-11 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full bg-royal pl-3.5 pr-4 text-[13px] font-semibold text-surface shadow-lift"
              >
                {playingHere ? (
                  <Pause size={15} strokeWidth={2.25} fill="currentColor" aria-hidden />
                ) : (
                  <Play size={15} strokeWidth={2.25} fill="currentColor" aria-hidden />
                )}
                {step.label}
                <span className="tabular-nums text-surface/80">
                  {step.seen}/{step.total}
                </span>
              </button>
            ) : step.done ? (
              <span className="flex items-center gap-1 whitespace-nowrap text-[12.5px] font-semibold text-royal">
                <Check size={14} strokeWidth={2.5} aria-hidden />
                {/* 펼친 화면은 왼쪽 열이 280px 까지 좁아져 끝난 단계는 체크만 보인다(읽기 프로그램에는 이름을 남긴다). */}
                <span className="unfolded:sr-only">{step.label}</span>
              </span>
            ) : (
              <span className="whitespace-nowrap text-[12.5px] font-medium text-muted-strong">{step.label}</span>
            )}
          </span>
        );
      })}
      <ChevronRight size={14} strokeWidth={2} className="shrink-0 text-faint" aria-hidden />
      {achieved ? (
        <span className="flex items-center gap-1 whitespace-nowrap text-[12.5px] font-semibold text-royal">
          <Flame size={14} strokeWidth={2} fill="currentColor" aria-hidden />
          <span className="tabular-nums">{view.streak.current}일째</span> 달성
        </span>
      ) : (
        <span className="flex items-center gap-1 whitespace-nowrap text-[12.5px] font-medium text-muted-strong">
          <Flame size={14} strokeWidth={2} aria-hidden />
          달성
        </span>
      )}
    </nav>
  );
}
