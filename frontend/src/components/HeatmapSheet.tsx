import { RotateCcw, Snowflake } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { api } from "../api/client";
import { koreanDate } from "../lib/format";
import { MASCOT_NAME, mascotLabel, nextMilestone, STAGE_DAYS, stageIs, stageName, type MascotStage, type MascotState } from "../lib/mascot";
import type { CellStatus, Heatmap } from "../api/types";
import { BottomSheet } from "./BottomSheet";
import { Mascot } from "./Mascot";

interface Props {
  open: boolean;
  onClose: () => void;
  /** 오프라인 낙관 표시: 서버가 아직 모르는 오늘 달성. */
  achievedTodayLocally: boolean;
  /** 화면 상태(낙관 반영)에서 계산한 포롱. 아직 못 받았으면 null. */
  mascot: MascotState | null;
  /** 지금 라운드(제목을 불러오기 전부터 정한다). */
  round: number;
}

type Load = { kind: "loading" } | { kind: "error" } | { kind: "ready"; data: Heatmap };

const CELL: Record<CellStatus, string> = {
  ACHIEVED: "bg-royal",
  FROZEN: "bg-frost text-surface",
  REPAIRED: "bg-tint text-royal ring-1 ring-inset ring-royal/30",
  MISSED: "bg-line",
  TODAY: "bg-tint ring-2 ring-royal ring-offset-2 ring-offset-surface",
  FUTURE: "bg-tint/70",
};

const LABEL: Record<CellStatus, string> = {
  ACHIEVED: "달성",
  FROZEN: "프리즈로 지킴",
  REPAIRED: "보충으로 지킴",
  MISSED: "놓침",
  TODAY: "오늘",
  FUTURE: "예정",
};

export function HeatmapSheet({ open, onClose, achievedTodayLocally, mascot, round }: Props) {
  const [load, setLoad] = useState<Load>({ kind: "loading" });

  const fetchMap = useCallback(() => {
    setLoad({ kind: "loading" });
    api
      .heatmap()
      .then((data) => setLoad({ kind: "ready", data }))
      .catch(() => setLoad({ kind: "error" }));
  }, []);

  useEffect(() => {
    if (open) fetchMap();
  }, [open, fetchMap]);

  return (
    <BottomSheet open={open} onClose={onClose} title={round > 1 ? `${round}라운드 기록` : "66일 기록"}>
      {load.kind === "loading" && <GridSkeleton />}
      {load.kind === "error" && (
        <div className="flex flex-col items-center gap-3 py-8 text-center">
          <p className="text-[14px] text-muted">기록을 불러오지 못했어요. 연결을 확인해 주세요.</p>
          <button type="button" onClick={fetchMap} className="press h-11 rounded-full bg-tint px-5 text-[14px] font-semibold text-royal">
            다시 불러오기
          </button>
        </div>
      )}
      {load.kind === "ready" && <HeatmapBody data={load.data} achievedTodayLocally={achievedTodayLocally} mascot={mascot} />}
    </BottomSheet>
  );
}

function HeatmapBody({ data, achievedTodayLocally, mascot }: { data: Heatmap; achievedTodayLocally: boolean; mascot: MascotState | null }) {
  const cells = data.cells.map((c) => (c.status === "TODAY" && achievedTodayLocally ? { ...c, status: "ACHIEVED" as const } : c));
  // 칸의 day 는 라운드 안의 날이다. 헤더(누적 Day)와 같은 번호로 부른다.
  const absDay = (day: number) => ((data.round ?? 1) - 1) * cells.length + day;
  const stats = [
    { label: "연속", value: `${data.current_streak}일` },
    { label: "최장", value: `${data.longest_streak}일` },
    {
      label: data.round > 1 ? "누적 달성" : "달성",
      value: `${data.achieved_days + (data.past_rounds ?? []).reduce((sum, r) => sum + r.achieved_days, 0)}일`,
    },
    { label: "프리즈", value: `${data.freezes_left}개` },
  ];
  return (
    <div className="flex flex-col gap-5">
      {mascot && <Growth mascot={mascot} currentDay={data.current_day} round={data.round} />}
      <dl className="grid grid-cols-4 gap-2">
        {stats.map((s) => (
          <div key={s.label} className="rounded-2xl bg-tint px-3 py-3">
            <dt className="text-[12px] font-medium text-muted-strong">{s.label}</dt>
            <dd className="mt-0.5 text-[19px] font-bold tabular-nums text-ink">{s.value}</dd>
          </div>
        ))}
      </dl>

      <ol
        className="grid grid-cols-11 gap-1.5"
        aria-label={`${data.round > 1 ? `${data.round}라운드, ` : ""}${koreanDate(data.round_start ?? data.start_date)}부터 ${cells.length}일`}
      >
        {cells.map((c) => (
          <li
            key={c.day}
            title={`Day ${absDay(c.day)} ${koreanDate(c.date)} ${LABEL[c.status]}`}
            aria-label={`Day ${absDay(c.day)}, ${koreanDate(c.date)}, ${LABEL[c.status]}`}
            className={`flex aspect-square items-center justify-center rounded-[10px] text-[10.5px] font-bold tabular-nums ${CELL[c.status]} ${MILE_TEXT[c.status]}`}
          >
            {c.status === "FROZEN" ? (
              <Snowflake size={12} strokeWidth={2} aria-hidden />
            ) : c.status === "REPAIRED" ? (
              <RotateCcw size={11} strokeWidth={2.25} aria-hidden />
            ) : mascot && data.round <= 1 && STAGE_DAYS.includes(c.day) ? <span aria-hidden>{c.day}</span> : null}
          </li>
        ))}
      </ol>

      {data.round > 1 && (
        <p className="-mt-2 text-[12.5px] font-medium text-muted-strong">
          {data.round}라운드 {data.round_day}일째 · 이번 라운드 <span className="font-semibold tabular-nums text-royal">{data.achieved_days}일</span> 달성
        </p>
      )}
      {(data.past_rounds ?? []).length > 0 && (
        <ul aria-label="지난 라운드(최근 3개)" className="flex flex-col gap-1.5">
          {[...data.past_rounds].reverse().slice(0, 3).map((r) => (
            <li key={r.round} className="flex items-center justify-between rounded-2xl bg-canvas px-3.5 py-2.5 text-[13px]">
              <span className="font-semibold text-ink">{r.round}라운드</span>
              <span className="tabular-nums text-muted-strong">
                {r.days}일 중 <b className="font-semibold text-royal">{r.achieved_days}일</b> 달성
              </span>
            </li>
          ))}
        </ul>
      )}
      <ul className="flex flex-wrap gap-x-4 gap-y-2 text-[12.5px] font-medium text-muted">
        {(["ACHIEVED", "FROZEN", "REPAIRED", "MISSED", "FUTURE"] as const).map((s) => (
          <li key={s} className="flex items-center gap-1.5">
            <span aria-hidden className={`h-3 w-3 rounded-[4px] ${CELL[s]}`} />
            {LABEL[s]}
          </li>
        ))}
      </ul>
      <p className="text-pretty text-[12.5px] leading-relaxed text-muted">
        {mascot && data.round <= 1 && `숫자가 적힌 칸은 ${MASCOT_NAME}이 자라는 날이에요. 하루를 놓쳐도 작아지지 않아요. `}
        오늘의 10문장을 문장마다 영어로 따라 말해 모두 통과하면 그날 달성이에요. 66일마다 한 라운드가 끝나고 기록은 계속 이어져요. 30일마다 프리즈 2개가 놓친 하루를 대신 지켜 줘요. 놓친 날은 3일 안에 말하기 탭에서 보충해도 이어져요.
      </p>
    </div>
  );
}

/** 이정표 칸의 숫자 색. 칸 바탕마다 4.5:1 을 넘는 쪽을 고른다. */
const MILE_TEXT: Record<CellStatus, string> = {
  ACHIEVED: "text-surface",
  FROZEN: "",
  REPAIRED: "text-royal",
  MISSED: "text-muted-strong",
  TODAY: "text-royal",
  FUTURE: "text-royal",
};

/** 포롱의 지금 모습과 다음에 자라는 날, 그리고 여섯 단계 성장 띠. */
function Growth({ mascot, currentDay, round }: { mascot: MascotState; currentDay: number; round: number }) {
  const next = nextMilestone(currentDay, mascot.stage);
  return (
    <section aria-label={`${MASCOT_NAME} 성장`} className="flex flex-col gap-3">
      <div className="flex items-center gap-3 rounded-3xl bg-tint py-2 pl-2 pr-4">
        <Mascot stage={mascot.stage} mood={mascot.mood} size={96} label={mascotLabel(mascot)} />
        <div className="min-w-0">
          <p className="text-[15px] font-bold text-ink">
            {MASCOT_NAME}은 지금 {stageIs(mascot.stage)}
          </p>
          <p className="mt-1 break-keep text-[13px] leading-relaxed text-muted-strong">
            {next ? (
              <>
                Day {next.day}에 {next.name}로 자라요.{" "}
                <span className="font-semibold tabular-nums text-royal">{next.daysLeft}일</span> 남았어요.
              </>
            ) : (
              round > 1 ? `66일을 함께 왔어요. 지금은 ${round}라운드예요.` : "66일을 함께 왔어요."
            )}
          </p>
        </div>
      </div>
      <ol className="grid grid-cols-6 gap-1">
        {STAGE_DAYS.map((day, i) => {
          const stage = (i + 1) as MascotStage;
          const here = stage === mascot.stage;
          const later = stage > mascot.stage;
          return (
            <li
              key={day}
              aria-current={here ? "step" : undefined}
              aria-label={`Day ${day}, ${stageName(stage)}${here ? ", 지금" : later ? ", 아직" : ", 지남"}`}
              className={`flex flex-col items-center gap-1 text-[11.5px] tabular-nums ${here ? "font-bold text-royal" : "font-medium text-muted"}`}
            >
              <span className={`flex h-11 w-11 items-center justify-center rounded-full ${here ? "bg-tint ring-2 ring-royal ring-offset-2 ring-offset-surface" : ""} ${later ? "opacity-30" : ""}`}>
                <Mascot stage={stage} mood={stage === 6 ? "party" : "wait"} size={40} still />
              </span>
              <span aria-hidden>Day {day}</span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}

function GridSkeleton() {
  return (
    <div aria-hidden className="flex flex-col gap-5">
      <div className="grid grid-cols-4 gap-2">
        {Array.from({ length: 4 }, (_, i) => (
          <span key={i} className="skeleton h-[62px] rounded-2xl bg-line" />
        ))}
      </div>
      <div className="grid grid-cols-11 gap-1.5">
        {Array.from({ length: 66 }, (_, i) => (
          <span key={i} className="skeleton aspect-square rounded-[10px] bg-line" />
        ))}
      </div>
    </div>
  );
}
