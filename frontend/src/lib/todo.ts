import type { DayView } from "../state/overlay";

/** 오늘 할 일 하나(SPEC 5장 "오늘 할 일"). 모두 서버가 준 값으로 자동 판정한다(직접 체크하지 않는다). */
export type TodoId = "morning" | "review" | "today" | "focus" | "word";
/** done 끝남, open 할 수 있음, missed 오늘은 지나서 못 함(진행률에서 뺀다). */
export type TodoState = "done" | "open" | "missed";

export interface Todo {
  id: TodoId;
  label: string;
  detail: string;
  /** 필수(연속 기록이 걸린 것)인가. 아니면 보너스. */
  required: boolean;
  state: TodoState;
  /** 지금 눌러서 시작할 수 있는가(아침 시간 전의 모닝 스타터처럼 아직 못 하는 열린 항목은 false). */
  startable: boolean;
  progress?: { value: number; total: number };
}

export const FOCUS_GOAL_SECONDS = 25 * 60;

/** 오늘 할 일 목록. 레슨이 없으면 null. 66일 뒤에도 계속된다. now 는 서버 시계 추정값. */
export function todayTodos(view: DayView, now: number): Todo[] | null {
  const d = view.dashboard;
  if (d.lesson_day === 0) return null;
  const items: Todo[] = [];

  const m = view.morningLock;
  if (m.sentences.length > 0) {
    let state: TodoState;
    let detail: string;
    if (m.result === "CLEARED") {
      state = "done";
      detail = "아침 3문장을 소리 내어 읽었어요";
    } else if (m.result === "SKIPPED") {
      state = "missed";
      detail = "오늘은 건너뛰었어요";
    } else if (now < m.windowEnd && !m.studiedToday) {
      state = "open";
      detail = now < m.windowStart ? "아침에 앱을 열면 시작돼요" : "지금 할 수 있어요";
    } else {
      // 아침 시간이 지났거나, 락이 뜨기 전에 이미 학습을 시작했다(락은 오늘 첫 학습 전에만 뜬다).
      state = "missed";
      detail = "오늘은 지났어요";
    }
    items.push({ id: "morning", label: "모닝 스타터", detail, required: false, state, startable: state === "open" && now >= m.windowStart });
  }

  const g = view.gate;
  if (g.required) {
    items.push({
      id: "review",
      label: "어제 복습",
      detail: g.yesterdayDay ? `Day ${g.yesterdayDay} 문장을 해석 보며 따라 말하기` : "어제 문장을 해석 보며 따라 말하기",
      required: true,
      state: g.open ? "done" : "open",
      startable: !g.open,
      progress: { value: g.seen, total: g.total },
    });
  }

  const t = view.todayProgress;
  items.push({
    id: "today",
    label: "오늘의 10문장",
    detail: "문장마다 듣고 영어로 따라 말해 통과하기",
    required: true,
    state: view.streak.achievedToday ? "done" : "open",
    startable: !view.streak.achievedToday,
    progress: { value: t.spoken, total: t.total },
  });

  const minutes = Math.floor(view.focusSecondsToday / 60);
  items.push({
    id: "focus",
    label: "25분 집중",
    detail: "집중 타이머로 오늘 25분 채우기",
    required: false,
    state: view.focusSecondsToday >= FOCUS_GOAL_SECONDS ? "done" : "open",
    startable: view.focusSecondsToday < FOCUS_GOAL_SECONDS,
    progress: { value: Math.min(minutes, 25), total: 25 },
  });

  // 서버가 준 오늘 구간(valid_from ~ valid_until)과 담은 시각을 비교한다(날짜를 계산하지 않는다).
  const addedToday = (view.vocab ?? []).some((v) => v.added_at >= d.valid_from && v.added_at < d.valid_until);
  items.push({
    id: "word",
    label: "새 단어 1개 담기",
    detail: "학습 카드의 책갈피 버튼으로 모르는 단어 담기",
    required: false,
    state: addedToday ? "done" : "open",
    startable: !addedToday,
  });

  return items;
}

export interface TodoSummary {
  done: number;
  /** 진행률의 분모(지나서 못 하는 항목은 뺀다). */
  total: number;
  requiredDone: boolean;
  allDone: boolean;
}

export function summarize(items: readonly Todo[]): TodoSummary {
  const available = items.filter((i) => i.state !== "missed");
  const done = available.filter((i) => i.state === "done").length;
  return {
    done,
    total: available.length,
    requiredDone: items.filter((i) => i.required).every((i) => i.state === "done"),
    allDone: done === available.length,
  };
}

/** 다음에 할 일: 필수 먼저(복습 → 오늘), 그다음 보너스(집중 → 단어). 모닝 스타터는 락 화면이 스스로 뜨므로 뺀다. */
export function nextTodo(items: readonly Todo[]): Todo | null {
  const order: TodoId[] = ["review", "today", "focus", "word"];
  for (const id of order) {
    const item = items.find((i) => i.id === id);
    if (item && item.state === "open") return item;
  }
  return null;
}
