import { Check, ChevronRight, CloudOff, Lock } from "lucide-react";
import type { SpeakOverview } from "../api/types";

interface Props {
  overview: SpeakOverview | null;
  /** 아직 보내지 않은 연습 완료(문장 id). 서버 값과 합쳐 바로 보인다. */
  pendingIds: readonly number[];
  selected: number | null;
  failed: boolean;
  onSelect: (day: number) => void;
  onRetry: () => void;
}

/** 말하기 연습: 오늘까지의 Day 목록(최근 먼저). 학습에서 10문장을 다 본 Day 만 열리고, 행마다 연습을 끝낸 문장 수. 습관과 무관하다. */
export function SpeakDayList({
  overview,
  pendingIds,
  selected,
  failed,
  onSelect,
  onRetry,
}: Props) {
  if (!overview) {
    return failed ? (
      <div className="flex flex-col items-center gap-3 py-10 text-center">
        <CloudOff
          size={22}
          strokeWidth={2}
          className="text-muted"
          aria-hidden
        />
        <p className="break-keep text-[14px] text-muted">
          Day 목록을 받지 못했어요. 연결되면 다시 받아 주세요.
        </p>
        <button
          type="button"
          onClick={onRetry}
          className="press h-11 rounded-full bg-tint px-5 text-[13.5px] font-semibold text-royal"
        >
          다시 받기
        </button>
      </div>
    ) : (
      <div aria-hidden className="flex flex-col gap-2 py-2">
        {[0, 1, 2].map((i) => (
          <span key={i} className="skeleton h-14 rounded-2xl bg-tint" />
        ))}
      </div>
    );
  }
  const anyLocked = overview.days.some((d) => !d.open);
  return (
    <>
      {anyLocked && (
        <p className="mb-3 break-keep text-[13px] text-muted">
          학습(오늘의 10문장·누적 복습)에서 10문장을 다 본 Day 가 열려요.
        </p>
      )}
      <ul className="flex flex-col gap-2" aria-label="말하기 연습 Day 목록">
        {overview.days.map((d) => {
          if (!d.open) {
            return (
              <li key={d.day}>
                <div
                  className="flex min-h-14 w-full items-center gap-3 rounded-2xl bg-canvas px-4 py-2.5"
                  aria-label={`Day ${d.day} ${d.title}, 학습 ${d.seen}/${d.sentence_ids.length}, 잠김`}
                >
                  <span className="w-[52px] shrink-0 text-[13px] font-bold tabular-nums text-faint">
                    Day {d.day}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-[14.5px] font-semibold text-muted">
                    {d.title}
                  </span>
                  <span className="flex shrink-0 items-center gap-1 text-[12.5px] font-semibold tabular-nums text-muted">
                    <Lock size={13} strokeWidth={2.25} aria-hidden />
                    학습 {d.seen}/{d.sentence_ids.length}
                  </span>
                </div>
              </li>
            );
          }
          const doneSet = new Set([
            ...d.done_ids,
            ...pendingIds.filter((id) => d.sentence_ids.includes(id)),
          ]);
          const done = doneSet.size;
          const total = d.sentence_ids.length;
          const complete = total > 0 && done >= total;
          const active = selected === d.day;
          return (
            <li key={d.day}>
              <button
                type="button"
                onClick={() => onSelect(d.day)}
                aria-current={active || undefined}
                className={`press flex min-h-14 w-full items-center gap-3 rounded-2xl px-4 py-2.5 text-left ${active ? "bg-tint" : "bg-canvas"}`}
              >
                <span className="w-[52px] shrink-0 text-[13px] font-bold tabular-nums text-royal">
                  Day {d.day}
                </span>
                <span className="min-w-0 flex-1 truncate text-[14.5px] font-semibold text-ink">
                  {d.title}
                </span>
                {complete ? (
                  <span className="flex shrink-0 items-center gap-1 text-[12.5px] font-semibold text-royal">
                    <Check size={14} strokeWidth={2.5} aria-hidden />
                    연습 완료
                  </span>
                ) : (
                  <span className="shrink-0 text-[12.5px] font-semibold tabular-nums text-muted-strong">
                    {done}/{total}
                  </span>
                )}
                <ChevronRight
                  size={18}
                  strokeWidth={2}
                  className="shrink-0 text-faint"
                  aria-hidden
                />
              </button>
            </li>
          );
        })}
      </ul>
    </>
  );
}

/** 말하기 연습 첫 화면(Day 를 고르기 전). 커버 화면은 카드 안에 목록, 펼친 화면은 왼쪽 열에 목록이 있어 안내만 둔다. */
export function SpeakHome(props: Omit<Props, "selected">) {
  return (
    <section
      aria-label="말하기 연습"
      className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-stage bg-surface p-6 shadow-card"
    >
      <h2 className="text-[17px] font-bold text-ink">말하기 연습</h2>
      <p className="mt-1 break-keep text-[13.5px] text-muted">
        배운 Day 의 문장을 하나씩 5번 소리 내어 말해요. 습관 기록과는 별개인
        부가 연습이에요.
      </p>
      <div className="thin-scroll -mx-3 mt-4 min-h-0 flex-1 overflow-y-auto px-3 unfolded:hidden">
        <SpeakDayList {...props} selected={null} />
      </div>
      <p className="mt-6 hidden break-keep text-[14px] font-semibold text-royal unfolded:block">
        왼쪽에서 연습할 Day 를 골라 주세요.
      </p>
    </section>
  );
}
