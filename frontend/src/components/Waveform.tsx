const DELAYS = [0, 140, 60, 220, 100, 180, 30];

/** 음성 파형. active: 듣는 중(빠름). calm: 사용자 차례(느리게 숨쉬기, 옅게). */
export function Waveform({ calm = false }: { calm?: boolean }) {
  return (
    <span aria-hidden className="flex h-5 items-center gap-[3px]">
      {DELAYS.map((delay, i) => (
        <span
          key={i}
          data-calm={calm}
          className={`wave-bar block w-1 rounded-full ${calm ? "bg-royal/40" : "bg-royal"}`}
          style={{ height: `${[10, 16, 20, 14, 18, 12, 8][i]}px`, animationDelay: `${delay}ms` }}
        />
      ))}
    </span>
  );
}
