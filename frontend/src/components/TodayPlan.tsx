import { BookmarkPlus, Flame, Mic, Pause, Play, Timer } from "lucide-react";
import type { Session } from "../api/types";
import { useEngine } from "../audio/useEngine";
import { nextTodo, summarize, type Todo } from "../lib/todo";
import type { DayView } from "../state/overlay";

export interface TodoActions {
  /** 복습·오늘 10문장: 그 탭으로 가서 학습 방식을 맞추고 재생(재생 중이면 멈춤). */
  onStart: (session: Session) => void;
  onStartFocus: () => void;
  onAddWord: () => void;
  onOpenMorning: () => void;
}

interface Props extends TodoActions {
  view: DayView;
  todos: readonly Todo[];
  focusRunning: boolean;
  onOpenList: () => void;
}

/**
 * 헤더 아래 한 줄(SPEC 5장 "오늘 할 일"). 왼쪽은 진행(n/m)이고 누르면 체크리스트가 열린다.
 * 오른쪽은 다음에 할 일을 바로 시작하는 버튼. 다 끝내면 연속 일수를 보인다.
 */
export function TodayPlan({ view, todos, focusRunning, onOpenList, onStart, onStartFocus, onAddWord }: Props) {
  const eng = useEngine();
  const sum = summarize(todos);
  const next = nextTodo(todos);

  return (
    <div className="flex h-11 min-w-0 items-center justify-between gap-2">
      <button
        type="button"
        onClick={onOpenList}
        aria-label={`오늘 할 일 목록 열기, ${sum.total}개 중 ${sum.done}개 끝`}
        className="press flex h-11 shrink-0 items-center gap-2 rounded-full bg-surface pl-1.5 pr-3.5 shadow-card"
      >
        <ProgressRing done={sum.done} total={sum.total} size={30} />
        <span className="whitespace-nowrap text-[13px] font-semibold text-ink">
          할 일 <span className="tabular-nums text-royal">{sum.done}</span>
          <span className="tabular-nums text-muted">/{sum.total}</span>
        </span>
      </button>

      {next ? (
        <NextButton
          todo={next}
          playing={eng.playing && (next.id === "review" ? eng.session === "REVIEW" : next.id === "today" ? eng.session === "TODAY" : false)}
          focusRunning={focusRunning}
          onClick={() => {
            if (next.id === "review") onStart("REVIEW");
            else if (next.id === "today") onStart("TODAY");
            else if (next.id === "focus") onStartFocus();
            else if (next.id === "word") onAddWord();
          }}
        />
      ) : (
        <span className="flex min-w-0 items-center gap-1 whitespace-nowrap text-[13px] font-semibold text-royal">
          <Flame size={15} strokeWidth={2} fill="currentColor" aria-hidden />
          <span className="truncate">
            {sum.allDone ? "오늘 할 일 끝!" : "필수 끝!"} <span className="tabular-nums">{view.streak.current}일째</span>
          </span>
        </span>
      )}
    </div>
  );
}

function NextButton({ todo, playing, focusRunning, onClick }: { todo: Todo; playing: boolean; focusRunning: boolean; onClick: () => void }) {
  const icon =
    todo.id === "focus" ? (
      <Timer size={15} strokeWidth={2.25} aria-hidden />
    ) : todo.id === "word" ? (
      <BookmarkPlus size={15} strokeWidth={2.25} aria-hidden />
    ) : playing ? (
      <Pause size={15} strokeWidth={2.25} fill="currentColor" aria-hidden />
    ) : todo.id === "today" ? (
      // 오늘 10문장은 연속 재생이 아니라 듣고 따라 말하기다.
      <Mic size={15} strokeWidth={2.25} aria-hidden />
    ) : (
      <Play size={15} strokeWidth={2.25} fill="currentColor" aria-hidden />
    );
  const label = todo.id === "focus" && focusRunning ? "집중하는 중" : todo.label;
  const busy = todo.id === "focus" && focusRunning;
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={busy}
      aria-label={`${playing ? "멈추기: " : busy ? "" : "시작하기: "}${label}${todo.progress ? ` ${todo.progress.value}/${todo.progress.total}` : ""}`}
      className="press flex h-11 min-w-0 items-center gap-1.5 whitespace-nowrap rounded-full bg-royal pl-3.5 pr-4 text-[13px] font-semibold text-surface shadow-lift disabled:opacity-70"
    >
      {icon}
      <span className="truncate">{label}</span>
      {todo.progress && todo.id !== "focus" && (
        <span className="tabular-nums text-surface/80">
          {todo.progress.value}/{todo.progress.total}
        </span>
      )}
    </button>
  );
}

/** 진행 원. done/total 만큼 로열 블루로 채운다(다 끝나면 가득). */
export function ProgressRing({ done, total, size }: { done: number; total: number; size: number }) {
  const stroke = size >= 48 ? 5 : 3.5;
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const ratio = total > 0 ? done / total : 0;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden className="-rotate-90">
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" className="stroke-line" strokeWidth={stroke} />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={r}
        fill="none"
        className="stroke-royal transition-[stroke-dashoffset] duration-500 ease-[var(--ease-out)]"
        strokeWidth={stroke}
        strokeLinecap="round"
        style={{ strokeDasharray: c, strokeDashoffset: c * (1 - ratio) }}
      />
    </svg>
  );
}
