import { Settings2 } from "lucide-react";
import type { PlayMode } from "../audio/turn";
import type { RevealFilter } from "../lib/prefs";
import { Segmented, type SegmentOption } from "./Segmented";

const FILTERS: readonly SegmentOption<RevealFilter>[] = [
  { value: "all", label: "전체" },
  { value: "hint", label: "어순힌트" },
  { value: "korean", label: "한글만" },
];

const MODES: readonly SegmentOption<PlayMode>[] = [
  { value: "full", label: "연속" },
  { value: "shadowing", label: "끊어읽기" },
  { value: "roleplay", label: "역할극" },
];

interface Props {
  /** 오늘 탭(따라 말하기): 가리기 필터·재생 모드는 쓰이지 않으므로 배속과 설정만 둔다. */
  speaking: boolean;
  filter: RevealFilter;
  onFilter: (f: RevealFilter) => void;
  mode: PlayMode;
  onMode: (m: PlayMode) => void;
  rate: number;
  onCycleRate: () => void;
  onOpenSettings: () => void;
}

/** 하단 컨트롤 1단: 가리기 필터 + 배속, 오디오 모드 + 설정. 엄지가 닿는 높이에 둔다. */
export function Controls({ speaking, filter, onFilter, mode, onMode, rate, onCycleRate, onOpenSettings }: Props) {
  const chip = "press flex h-12 min-w-[56px] items-center justify-center rounded-full bg-surface px-3 text-[12.5px] font-bold text-royal shadow-card";
  if (speaking) {
    return (
      <div className="flex items-center justify-end gap-2">
        <button type="button" onClick={onCycleRate} aria-label={`듣기 속도 ${rate}배. 눌러서 바꾸기`} className={`${chip} tabular-nums`}>
          {rate === 1 ? "1.0" : rate}x
        </button>
        <button type="button" onClick={onOpenSettings} aria-label="설정 열기" className={chip}>
          <Settings2 size={18} strokeWidth={2} aria-hidden />
        </button>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-2.5">
      <div className="flex items-center gap-2">
        <Segmented label="가리기 필터" options={FILTERS} value={filter} onChange={onFilter} className="flex-1" />
        <button type="button" onClick={onCycleRate} aria-label={`재생 속도 ${rate}배. 눌러서 바꾸기`} className={`${chip} tabular-nums`}>
          {rate === 1 ? "1.0" : rate}x
        </button>
      </div>
      <div className="flex items-center gap-2">
        <Segmented label="오디오 모드" options={MODES} value={mode} onChange={onMode} className="flex-1" />
        <button type="button" onClick={onOpenSettings} aria-label="설정 열기" className={chip}>
          <Settings2 size={18} strokeWidth={2} aria-hidden />
        </button>
      </div>
    </div>
  );
}
