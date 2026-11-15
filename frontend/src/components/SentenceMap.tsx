import { useEffect, useRef } from "react";
import type { Session } from "../api/types";
import { audioEngine, useEngine } from "../audio/useEngine";
import type { DayView } from "../state/overlay";

interface Props {
  view: DayView | null;
  tab: Session;
}

/**
 * 문장 지도(펼친 화면 왼쪽 열). 세션의 문장을 번호 타일로 깔아 지금 위치와 암기한 문장을 한눈에 보여 주고,
 * 타일을 누르면 그 문장으로 간다(엔진의 유일한 이동 입구 go 를 그대로 쓴다).
 * 영어 원문은 넣지 않는다. 가리기 필터로 암송하는 중에 답이 보이면 안 된다.
 */
export function SentenceMap({ view, tab }: Props) {
  const eng = useEngine();
  const currentRef = useRef<HTMLButtonElement>(null);

  const session = view ? (tab === "TODAY" ? view.today : view.review) : null;
  const items = session?.sentences ?? [];
  const synced = eng.session === tab && eng.items.length === items.length;
  const index = synced ? Math.min(eng.cursor, items.length - 1) : 0;
  const locked = !!view && tab === "TODAY" && !view.gate.open;
  const memorized = items.filter((s) => s.status === "MEMORIZED").length;

  useEffect(() => {
    currentRef.current?.scrollIntoView({ block: "nearest" });
  }, [index, tab]);

  return (
    <section aria-label="문장 지도" className="flex max-h-full min-h-0 w-full flex-col self-start rounded-3xl bg-surface p-4 shadow-card">
      <div className="flex items-end justify-between px-1">
        <div>
          <p className="text-[12px] font-medium text-muted">암기한 문장</p>
          <p className="mt-0.5 text-[26px] font-bold leading-tight tracking-tight tabular-nums text-ink">
            {view ? (
              <>
                {memorized}
                <span className="text-[15px] font-semibold text-muted"> / {items.length}</span>
              </>
            ) : (
              <span className="skeleton inline-block h-7 w-16 rounded-lg bg-line align-middle" />
            )}
          </p>
        </div>
        <ul aria-hidden className="flex items-center gap-3 pb-1.5 text-[12px] font-medium text-muted">
          <li className="flex items-center gap-1.5">
            <span className="h-2.5 w-2.5 rounded-[4px] bg-royal" />
            지금
          </li>
          <li className="flex items-center gap-1.5">
            <span className="h-2.5 w-2.5 rounded-[4px] bg-frost" />
            암기
          </li>
        </ul>
      </div>

      {!view ? (
        <div aria-hidden className="mt-3 grid grid-cols-5 gap-2 max-[699px]:grid-cols-4">
          {Array.from({ length: 10 }, (_, i) => (
            <span key={i} className="skeleton h-12 rounded-2xl bg-line" />
          ))}
        </div>
      ) : items.length === 0 ? (
        <p className="mt-3 px-1 text-pretty text-[13.5px] leading-relaxed text-muted">문장을 받으면 여기에 순서대로 놓여요.</p>
      ) : (
        <ol className="thin-scroll -mr-2 mt-3 grid min-h-0 auto-rows-min grid-cols-5 content-start gap-2 overflow-y-auto overscroll-contain p-0.5 pr-2.5 max-[699px]:grid-cols-4">
          {/* 폭 700px 미만의 펼친 화면은 왼쪽 열이 280px이라 5칸이면 칸이 44px보다 좁아진다. 간격(8px)은 지키고 4칸으로 줄인다. */}
          {items.map((s, i) => {
            const current = i === index;
            const done = s.status === "MEMORIZED";
            return (
              <li key={s.id}>
                <button
                  ref={current ? currentRef : undefined}
                  type="button"
                  disabled={locked || !synced}
                  aria-current={current ? "true" : undefined}
                  aria-label={`${i + 1}번 문장으로 가기${done ? ", 암기 완료" : ""}`}
                  onClick={() => audioEngine.go(i)}
                  className={`press flex h-12 w-full items-center justify-center rounded-2xl text-[13.5px] tabular-nums disabled:opacity-40 ${
                    current
                      ? "bg-royal font-bold text-surface"
                      : done
                        ? "bg-frost font-semibold text-ink [@media(hover:hover)]:hover:bg-frost/80"
                        : "bg-canvas font-medium text-muted-strong [@media(hover:hover)]:hover:bg-tint"
                  }`}
                >
                  {i + 1}
                </button>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
