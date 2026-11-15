import { Bell, BellOff, Check, Copy, Send } from "lucide-react";
import { useState } from "react";
import { usePush } from "../hooks/usePush";
import { koreanDate, koreanDuration } from "../lib/format";
import { routineUrls } from "../lib/launch";
import type { StudyState } from "../state/store";
import { studyStore } from "../state/store";
import { BottomSheet } from "./BottomSheet";

interface Props {
  open: boolean;
  onClose: () => void;
  study: StudyState;
}

export function SettingsSheet({ open, onClose, study }: Props) {
  return (
    <BottomSheet open={open} onClose={onClose} title="설정">
      <div className="flex flex-col gap-7">
        <PushSection />
        <RoutineSection />
        <RecordSection study={study} />
      </div>
    </BottomSheet>
  );
}

function SectionTitle({ children }: { children: string }) {
  return <h3 className="mb-3 text-[15px] font-semibold text-ink">{children}</h3>;
}

function PushSection() {
  const push = usePush();
  const on = push.status === "on";
  const statusText: Record<typeof push.status, string> = {
    unsupported: "이 브라우저는 알림을 지원하지 않아요. 홈 화면에 설치한 앱에서 열어 주세요.",
    checking: "알림 상태를 확인하는 중이에요.",
    off: "22시에 오늘 습관이 남아 있으면 알려 드려요.",
    on: "알림이 켜져 있어요. 22시에 오늘 습관이 남아 있으면 알려 드려요.",
    denied: "알림이 차단돼 있어요. 휴대폰 설정 > 앱 > 알림에서 허용해 주세요.",
    busy: "처리하는 중이에요.",
  };
  return (
    <section>
      <SectionTitle>마감 알림</SectionTitle>
      <div className="flex items-center gap-3">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-tint text-royal">
          {on ? <Bell size={18} strokeWidth={2} aria-hidden /> : <BellOff size={18} strokeWidth={2} aria-hidden />}
        </span>
        <p className="flex-1 text-pretty text-[13.5px] leading-relaxed text-muted">{statusText[push.status]}</p>
        <button
          type="button"
          role="switch"
          aria-checked={on}
          aria-label="22시 마감 알림"
          disabled={push.status === "unsupported" || push.status === "checking" || push.status === "busy" || push.status === "denied"}
          onClick={() => void (on ? push.disable() : push.enable())}
          className={`press relative h-8 w-[52px] shrink-0 rounded-full disabled:opacity-40 ${on ? "bg-royal" : "bg-line"}`}
        >
          <span
            aria-hidden
            className="absolute left-1 top-1 h-6 w-6 rounded-full bg-surface shadow-card transition-transform duration-[220ms] ease-[var(--ease-out)]"
            style={{ transform: on ? "translateX(20px)" : "translateX(0)" }}
          />
        </button>
      </div>
      {on && (
        <button type="button" onClick={() => void push.test()} className="press mt-3 flex h-10 items-center gap-1.5 rounded-full bg-tint px-4 text-[13px] font-semibold text-royal">
          <Send size={15} strokeWidth={2} aria-hidden />
          시험 알림 보내기
        </button>
      )}
      {push.message && <p className="mt-2 text-[12.5px] text-royal">{push.message}</p>}
    </section>
  );
}

function RoutineSection() {
  const [copied, setCopied] = useState<string | null>(null);
  const copy = async (url: string) => {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(url);
      window.setTimeout(() => setCopied(null), 1600);
    } catch {
      setCopied(null);
    }
  };
  return (
    <section>
      <SectionTitle>갤럭시 루틴 주소</SectionTitle>
      <p className="mb-3 text-pretty text-[13px] leading-relaxed text-muted">모드 및 루틴에서 이 주소를 열면 바로 그 모드로 재생해요. 앱 아이콘을 길게 눌러도 같은 바로가기가 있어요.</p>
      <ul className="flex flex-col gap-2">
        {routineUrls().map((r) => (
          <li key={r.url} className="flex items-center gap-3 rounded-2xl bg-tint py-2.5 pl-4 pr-2">
            <div className="min-w-0 flex-1">
              <p className="text-[13.5px] font-semibold text-ink">{r.label}</p>
              <p className="select-all break-all text-[12px] text-muted-strong">{r.url}</p>
            </div>
            <button
              type="button"
              onClick={() => void copy(r.url)}
              aria-label={`${r.label} 주소 복사`}
              className="press flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-surface text-royal shadow-card"
            >
              {copied === r.url ? <Check size={17} strokeWidth={2} aria-hidden /> : <Copy size={17} strokeWidth={2} aria-hidden />}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

function RecordSection({ study }: { study: StudyState }) {
  if (study.kind !== "ready") return null;
  const v = study.view;
  const rows = [
    { label: "오늘", value: `${koreanDate(v.dashboard.today)}, Day ${v.dashboard.current_day}` },
    { label: "오늘 집중", value: koreanDuration(v.focusSecondsToday) },
    { label: "오늘 진행", value: `듣기 ${v.todayProgress.audioSeen}/${v.todayProgress.total}, 카드 ${v.todayProgress.cardSeen}/${v.todayProgress.total}` },
    { label: "보내지 않은 기록", value: `${study.sync.pending}건${study.sync.online ? "" : " (오프라인)"}` },
  ];
  return (
    <section>
      <SectionTitle>오늘 기록</SectionTitle>
      <dl className="flex flex-col gap-2 text-[13.5px]">
        {rows.map((r) => (
          <div key={r.label} className="flex justify-between gap-4">
            <dt className="text-muted">{r.label}</dt>
            <dd className="text-right font-semibold tabular-nums text-ink">{r.value}</dd>
          </div>
        ))}
      </dl>
      {study.sync.pending > 0 && study.sync.online && (
        <button type="button" onClick={() => void studyStore.refresh()} className="press mt-3 h-10 rounded-full bg-tint px-4 text-[13px] font-semibold text-royal">
          지금 보내기
        </button>
      )}
      {study.sync.lastError && <p className="mt-2 text-[12.5px] text-muted">마지막 동기화 오류: {study.sync.lastError}</p>}
      {study.sync.held > 0 && (
        <p className="mt-2 text-[12.5px] text-muted">서버가 받지 않은 기록 {study.sync.held}건을 기기에 따로 보관했어요.</p>
      )}
      {study.sync.saveFailed && <p className="mt-2 text-[12.5px] text-muted">기기 저장 공간이 부족해 기록을 저장하지 못했어요.</p>}
    </section>
  );
}
