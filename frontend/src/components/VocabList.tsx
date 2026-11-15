import { Check } from "lucide-react";
import { useEffect, useRef } from "react";
import type { VocabEntry } from "../api/types";
import type { VocabFilter } from "../lib/vocab";

interface Props {
  /** null 이면 아직 못 받았다. */
  all: VocabEntry[] | null;
  deck: VocabEntry[];
  index: number;
  filter: VocabFilter;
  onSelect: (key: string) => void;
}

const HEAD: Record<VocabFilter, string> = { learning: "외우는 중인 단어", mastered: "외운 단어", all: "담은 단어" };

/** 단어 목록(펼친 화면 왼쪽 열, 문장 지도 자리). 누르면 오른쪽 카드가 그 단어로 바뀐다. */
export function VocabList({ all, deck, index, filter, onSelect }: Props) {
  const currentRef = useRef<HTMLButtonElement>(null);
  const currentKey = deck[index]?.term_key;

  useEffect(() => {
    currentRef.current?.scrollIntoView({ block: "nearest" });
  }, [currentKey]);

  return (
    <section aria-label="단어 목록" className="flex max-h-full min-h-0 w-full flex-col self-start rounded-3xl bg-surface p-4 shadow-card">
      <div className="px-1">
        <p className="text-[12px] font-medium text-muted">{HEAD[filter]}</p>
        <p className="mt-0.5 text-[26px] font-bold leading-tight tracking-tight tabular-nums text-ink">
          {all ? (
            <>
              {deck.length}
              {filter !== "all" && <span className="text-[15px] font-semibold text-muted"> / {all.length}</span>}
            </>
          ) : (
            <span className="skeleton inline-block h-7 w-16 rounded-lg bg-line align-middle" />
          )}
        </p>
      </div>

      {!all ? (
        <div aria-hidden className="mt-3 flex flex-col gap-2">
          {Array.from({ length: 4 }, (_, i) => (
            <span key={i} className="skeleton h-14 rounded-2xl bg-line" />
          ))}
        </div>
      ) : deck.length === 0 ? (
        <p className="mt-3 break-keep px-1 text-pretty text-[13.5px] leading-relaxed text-muted">
          {all.length === 0 ? "담은 단어가 여기에 차례로 모여요." : "이 필터에 해당하는 단어가 없어요."}
        </p>
      ) : (
        <ol className="thin-scroll -mr-2 mt-3 flex min-h-0 flex-col gap-1 overflow-y-auto overscroll-contain p-0.5 pr-2.5">
          {deck.map((v, i) => {
            const current = i === index;
            return (
              <li key={v.term_key}>
                <button
                  ref={current ? currentRef : undefined}
                  type="button"
                  aria-current={current ? "true" : undefined}
                  onClick={() => onSelect(v.term_key)}
                  className={`press flex min-h-14 w-full items-center gap-2 rounded-2xl px-3 py-2 text-left ${
                    current ? "bg-tint" : "[@media(hover:hover)]:hover:bg-canvas"
                  }`}
                >
                  <span className="min-w-0 flex-1">
                    <span lang="en" translate="no" className={`block truncate text-[14.5px] font-semibold ${current ? "text-royal" : "text-ink"}`}>
                      {v.text}
                    </span>
                    <span className={`block truncate text-[12.5px] ${current ? "text-muted-strong" : "text-muted"}`}>
                      {v.meaning ?? (v.status === "PENDING" ? "뜻을 만드는 중…" : "뜻을 적어 주세요")}
                    </span>
                  </span>
                  {v.mastered && <Check size={16} strokeWidth={2} aria-label="외운 단어" className="shrink-0 text-royal" />}
                </button>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
