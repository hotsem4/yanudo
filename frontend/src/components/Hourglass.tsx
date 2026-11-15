interface Props {
  /** 0..1, 위쪽 모래가 남은 비율. */
  remaining: number;
  size?: number;
}

/** 25분 모래시계 게이지. 위 모래는 목 쪽으로 줄고 아래 모래는 바닥에서 쌓인다(transform 만 사용). */
export function Hourglass({ remaining, size = 30 }: Props) {
  const r = Math.min(1, Math.max(0, remaining));
  return (
    <svg width={size} height={size} viewBox="0 0 28 28" aria-hidden className="shrink-0 text-royal">
      <path
        d="M7 4h14M7 24h14M8.5 4c0 5.5 5.5 7.2 5.5 10s-5.5 4.5-5.5 10M19.5 4c0 5.5-5.5 7.2-5.5 10s5.5 4.5 5.5 10"
        fill="none"
        stroke="currentColor"
        strokeWidth={1.75}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path
        d="M10 7.5h8L14 13z"
        fill="currentColor"
        style={{
          transformBox: "fill-box",
          transformOrigin: "50% 100%",
          transform: `scale(${r})`,
          transition: "transform 900ms linear",
        }}
      />
      <path
        d="M14 16.5l4.8 5.5H9.2z"
        fill="currentColor"
        opacity={0.45}
        style={{
          transformBox: "fill-box",
          transformOrigin: "50% 100%",
          transform: `scale(${1 - r})`,
          transition: "transform 900ms linear",
        }}
      />
    </svg>
  );
}
