/** 1500 → "25:00", 65 → "01:05" */
export function mmss(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

/** 5400 → "1시간 30분", 900 → "15분" */
export function koreanDuration(totalSeconds: number): string {
  const m = Math.round(totalSeconds / 60);
  if (m < 60) return `${m}분`;
  const h = Math.floor(m / 60);
  return m % 60 ? `${h}시간 ${m % 60}분` : `${h}시간`;
}

const dateFormatter = new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric", weekday: "short", timeZone: "UTC" });

/** "2026-11-15" → "11월 15일 (일)". 서버가 준 논리 날짜 문자열을 시간대 이동 없이 보여 준다. */
export function koreanDate(isoDate: string): string {
  const [y, m, d] = isoDate.split("-").map(Number);
  if (!y || !m || !d) return isoDate;
  return dateFormatter.format(new Date(Date.UTC(y, m - 1, d)));
}

export function dayRange(days: number[]): string {
  if (days.length === 0) return "";
  const sorted = [...days].sort((a, b) => a - b);
  return sorted.length === 1 ? `Day ${sorted[0]}` : `Day ${sorted[0]}–${sorted[sorted.length - 1]}`;
}
