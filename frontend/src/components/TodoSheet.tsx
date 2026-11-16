import { motion, useReducedMotion } from "framer-motion";
import { Check, ChevronRight } from "lucide-react";
import { summarize, type Todo } from "../lib/todo";
import type { MascotState } from "../lib/mascot";
import { BottomSheet } from "./BottomSheet";
import { Mascot } from "./Mascot";
import { ProgressRing, type TodoActions } from "./TodayPlan";

interface Props extends TodoActions {
  open: boolean;
  onClose: () => void;
  todos: readonly Todo[];
  streak: number;
  mascot: MascotState | null;
}

/** 오늘 할 일 체크리스트(SPEC 5장). 항목은 모두 자동으로 체크된다. 할 수 있는 항목을 누르면 바로 시작한다. */
export function TodoSheet({ open, onClose, todos, streak, mascot, onStart, onStartFocus, onAddWord, onOpenMorning }: Props) {
  const sum = summarize(todos);
  const run = (todo: Todo) => {
    onClose();
    if (todo.id === "review") onStart("REVIEW");
    else if (todo.id === "today") onStart("TODAY");
    else if (todo.id === "focus") onStartFocus();
    else if (todo.id === "word") onAddWord();
    else if (todo.id === "morning") onOpenMorning();
  };
  const message = sum.allDone
    ? "오늘 할 일 끝! 정말 잘했어요"
    : sum.requiredDone
      ? `필수는 끝! 연속 ${streak}일째예요. 보너스도 해 볼까요?`
      : "필수 두 가지를 끝내면 연속 기록이 이어져요";

  return (
    <BottomSheet open={open} onClose={onClose} title="오늘 할 일">
      <div className="flex items-center gap-4">
        <span className="relative flex shrink-0 items-center justify-center">
          <ProgressRing done={sum.done} total={sum.total} size={64} />
          <span className="absolute text-[15px] font-bold tabular-nums text-ink">
            {sum.done}/{sum.total}
          </span>
        </span>
        <p className="min-w-0 flex-1 break-keep text-[15px] font-semibold leading-snug text-ink">{message}</p>
        {mascot && (sum.allDone || sum.requiredDone) && (
          <Mascot stage={mascot.stage} mood={sum.allDone ? "party" : "done"} size={56} />
        )}
      </div>

      <ul className="mt-5 flex flex-col gap-2" aria-label="할 일 목록">
        {todos.map((todo) => {
          const actionable = todo.startable;
          const body = (
            <>
              <CheckMark state={todo.state} />
              <span className="min-w-0 flex-1">
                <span className="flex items-center gap-2">
                  <span className={`text-[15px] font-semibold ${todo.state === "missed" ? "text-faint" : "text-ink"}`}>{todo.label}</span>
                  <span
                    className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${todo.required ? "bg-tint text-royal" : "bg-canvas text-muted-strong"}`}
                  >
                    {todo.required ? "필수" : "보너스"}
                  </span>
                </span>
                <span className={`mt-0.5 block break-keep text-[12.5px] leading-snug ${todo.state === "missed" ? "text-faint" : "text-muted"}`}>
                  {todo.detail}
                </span>
              </span>
              {todo.progress && todo.state !== "missed" && (
                <span className="shrink-0 text-[13px] font-semibold tabular-nums text-muted-strong">
                  {todo.progress.value}/{todo.progress.total}
                  {todo.id === "focus" ? "분" : ""}
                </span>
              )}
              {actionable && <ChevronRight size={18} strokeWidth={2} className="shrink-0 text-faint" aria-hidden />}
            </>
          );
          return (
            <li key={todo.id}>
              {actionable ? (
                <button
                  type="button"
                  onClick={() => run(todo)}
                  className="press flex min-h-14 w-full items-center gap-3 rounded-2xl bg-canvas px-3.5 py-2.5 text-left"
                >
                  {body}
                </button>
              ) : (
                <div className={`flex min-h-14 items-center gap-3 rounded-2xl px-3.5 py-2.5 ${todo.state === "done" ? "bg-tint" : "bg-canvas"}`}>
                  {body}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </BottomSheet>
  );
}

/** 체크 표시. 끝나는 순간 채워지며 커진다(줄인 움직임 설정이면 바로 바뀐다). */
function CheckMark({ state }: { state: Todo["state"] }) {
  const reduce = useReducedMotion();
  if (state === "done") {
    return (
      <motion.span
        key="done"
        initial={reduce ? false : { scale: 0.4, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        transition={{ type: "spring", stiffness: 520, damping: 26 }}
        className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-royal text-surface"
        aria-label="끝남"
      >
        <Check size={16} strokeWidth={3} aria-hidden />
      </motion.span>
    );
  }
  return (
    <span
      className={`h-7 w-7 shrink-0 rounded-full ${state === "missed" ? "bg-line" : "bg-surface ring-2 ring-inset ring-line"}`}
      aria-label={state === "missed" ? "지남" : "아직"}
    />
  );
}
