import type { ReactNode } from "react";

export interface SegmentOption<T extends string> {
  value: T;
  label: string;
  icon?: ReactNode;
}

interface Props<T extends string> {
  options: readonly SegmentOption<T>[];
  value: T;
  onChange: (value: T) => void;
  label: string;
  /** primary: 흰 바탕 + 로열 블루 알약(필터·모드). tab: 연블루 바탕 + 흰 알약(세션 탭). */
  variant?: "primary" | "tab";
  /** 모양은 tab 인데 뜻은 필터(여럿 중 하나 고르기)일 때 "radio" 를 준다. 기본은 모양을 따른다. */
  semantics?: "tab" | "radio";
  className?: string;
}

/**
 * 필 세그먼트. 항목 폭이 같으므로 활성 알약은 translateX(index × 100%) CSS 트랜지션으로 움직인다
 * (framer layoutId 는 메인 스레드라 쓰지 않는다 — clean-blue-ui §5).
 */
export function Segmented<T extends string>({ options, value, onChange, label, variant = "primary", semantics, className = "" }: Props<T>) {
  const index = Math.max(0, options.findIndex((o) => o.value === value));
  const isTab = variant === "tab";
  const asTab = (semantics ?? variant) === "tab";
  return (
    <div
      role={asTab ? "tablist" : "radiogroup"}
      aria-label={label}
      className={`relative flex rounded-full p-1 ${isTab ? "bg-tint" : "bg-surface shadow-card"} ${className}`}
    >
      <span
        aria-hidden
        className={`pointer-events-none absolute bottom-1 left-1 top-1 rounded-full transition-transform duration-[220ms] ease-[var(--ease-out)] ${
          isTab ? "bg-surface shadow-card" : "bg-royal"
        }`}
        style={{ width: `calc((100% - 8px) / ${options.length})`, transform: `translateX(${index * 100}%)` }}
      />
      {options.map((o) => {
        const active = o.value === value;
        return (
          <button
            key={o.value}
            type="button"
            role={asTab ? "tab" : "radio"}
            aria-selected={asTab ? active : undefined}
            aria-checked={asTab ? undefined : active}
            onClick={() => onChange(o.value)}
            className={`press relative z-10 flex h-11 min-w-0 flex-1 items-center justify-center gap-1 overflow-hidden whitespace-nowrap rounded-full text-[12.5px] ${options.length > 2 ? "px-1 tracking-tight [@media(min-width:600px)_and_(max-width:699px)]:text-[11px] [@media(min-width:600px)_and_(max-width:699px)]:tracking-tighter" : "px-2"} ${
              active
                ? isTab
                  ? "font-semibold text-royal"
                  : "font-semibold text-surface"
                : isTab
                  ? "font-medium text-muted-strong"
                  : "font-medium text-muted [@media(hover:hover)]:hover:text-ink"
            }`}
          >
            {o.icon}
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
