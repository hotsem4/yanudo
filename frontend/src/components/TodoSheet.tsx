import { motion, useReducedMotion } from "framer-motion";
import { Check, ChevronRight, Moon, RotateCcw } from "lucide-react";
import { useState } from "react";
import type { DayView } from "../state/overlay";
import { summarize, type Todo } from "../lib/todo";
import type { MascotState } from "../lib/mascot";
import { BottomSheet } from "./BottomSheet";
import { Mascot } from "./Mascot";
import { ProgressRing, type TodoActions } from "./TodayPlan";
import { shortDate } from "./SpeakDayList";

interface Props extends TodoActions {
  open: boolean;
  onClose: () => void;
  todos: readonly Todo[];
  streak: number;
  mascot: MascotState | null;
  achievedToday: boolean;
  /** 오늘 레슨이 있고 프로그램 중이며 스냅샷이 오늘 것일 때만 "오늘은 어려워요"를 보인다. */
  restAllowed: boolean;
  /** 이번 30일 구간의 Day 범위(예: [1, 30]). 남은 횟수 안내에 쓴다. */
  restPeriod: readonly [number, number];
  restDay: DayView["restDay"];
  makeupsDue: DayView["makeupsDue"];
  /** "오늘은 어려워요"를 알린다(REST_DAY 이벤트). */
  onDeclareRest: () => void;
  /** 말하기 탭에서 그 Day 보충을 연다. */
  onOpenMakeup: (day: number) => void;
}

/** 오늘 할 일 체크리스트(SPEC 5장). 항목은 모두 자동으로 체크된다. 할 수 있는 항목을 누르면 바로 시작한다. */
export function TodoSheet({
  open,
  onClose,
  todos,
  streak,
  mascot,
  achievedToday,
  restAllowed,
  restPeriod,
  restDay,
  makeupsDue,
  onDeclareRest,
  onOpenMakeup,
  onStart,
  onStartFocus,
  onAddWord,
  onOpenMorning,
}: Props) {
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

      {restDay && restAllowed && !achievedToday && (
        <RestDayRow restDay={restDay} period={restDay.period ?? restPeriod} streak={streak} onDeclare={onDeclareRest} />
      )}

      {makeupsDue.length > 0 && (
        <section aria-label="보충할 날" className="mt-5">
          <h3 className="text-[13px] font-bold text-muted-strong">보충하면 연속 기록을 지켜요</h3>
          <ul className="mt-2 flex flex-col gap-2">
            {makeupsDue.map((m) => (
              <li key={m.day}>
                <button
                  type="button"
                  onClick={() => {
                    onClose();
                    onOpenMakeup(m.day);
                  }}
                  className="press flex min-h-14 w-full items-center gap-3 rounded-2xl bg-tint px-3.5 py-2.5 text-left"
                >
                  <RotateCcw size={18} strokeWidth={2.25} className="shrink-0 text-royal" aria-hidden />
                  <span className="min-w-0 flex-1">
                    <span className="block text-[15px] font-semibold text-ink">Day {m.day} 보충</span>
                    <span className="mt-0.5 block break-keep text-[12.5px] text-muted">
                      문장마다 {m.reps}번, {shortDate(m.deadline)}까지
                    </span>
                  </span>
                  <span className="shrink-0 text-[13px] font-semibold tabular-nums text-muted-strong">
                    {m.done}/{m.total}
                  </span>
                  <ChevronRight size={18} strokeWidth={2} className="shrink-0 text-faint" aria-hidden />
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

    </BottomSheet>
  );
}

/**
 * "오늘은 어려워요". 미리 알리면 오늘을 빼먹어도 내일부터 10문장을 1번씩만 말해 보충하면 된다(그냥 빼먹으면 5번씩).
 * 남용을 막는 30일 구간 횟수 제한이 있어 한 번 더 확인받는다.
 */
function RestDayRow({
  restDay,
  period,
  streak,
  onDeclare,
}: {
  restDay: NonNullable<DayView["restDay"]>;
  period: readonly [number, number];
  streak: number;
  onDeclare: () => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const range = `Day ${period[0]}~${period[1]}`;
  if (restDay.declaredToday) {
    return (
      <p className="mt-4 flex items-start gap-2 break-keep rounded-2xl bg-canvas px-3.5 py-3 text-[13px] leading-snug text-muted-strong">
        <Moon size={16} strokeWidth={2.25} className="mt-0.5 shrink-0 text-royal" aria-hidden />
        {restDay.sending
          ? "쉬는 날로 알렸어요. 연결되면 서버에 전해져요(그 전에는 밤 알림이 올 수 있어요)."
          : "오늘은 쉬는 날로 알렸어요. 못 하면 3일 안에 1번씩만 보충하면 되고, 오늘 밤 알림은 쉬어요."}
      </p>
    );
  }
  if (restDay.left <= 0) {
    return <p className="mt-4 break-keep text-[12.5px] text-muted">{range} 동안 쓸 수 있는 "오늘은 어려워요"를 다 썼어요.</p>;
  }
  if (!confirming) {
    return (
      <button
        type="button"
        onClick={() => setConfirming(true)}
        className="press mt-4 flex h-11 items-center gap-2 rounded-full bg-canvas px-4 text-[13.5px] font-semibold text-muted-strong"
      >
        <Moon size={16} strokeWidth={2.25} aria-hidden />
        오늘은 어려워요
      </button>
    );
  }
  return (
    <div className="mt-4 rounded-2xl bg-canvas p-3.5">
      <p className="break-keep text-[13.5px] leading-snug text-ink">
        오늘을 쉬는 날로 알릴까요? 오늘 못 하면 그날 보충이 10문장 <b className="font-semibold text-royal">1번씩</b>으로 가벼워져요(알리지
        않고 빼먹으면 5번씩).{streak > 0 ? " 내일부터 3일 안에 보충하면 연속 기록도 이어져요." : ""}
      </p>
      <p className="mt-1 text-[12.5px] text-muted">
        {range} 동안 {restDay.left}번 남았어요. 알린 뒤에는 취소할 수 없어요.
      </p>
      <div className="mt-3 flex gap-2">
        <button
          type="button"
          onClick={() => {
            onDeclare();
            setConfirming(false);
          }}
          className="press h-11 rounded-full bg-royal px-5 text-[13.5px] font-semibold text-surface"
        >
          쉬는 날로 알리기
        </button>
        <button
          type="button"
          onClick={() => setConfirming(false)}
          className="press h-11 rounded-full bg-surface px-5 text-[13.5px] font-semibold text-muted-strong"
        >
          취소
        </button>
      </div>
    </div>
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
