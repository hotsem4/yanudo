import { termKey, type Dashboard, type MarkStatus, type Sentence, type SessionData, type StudyEvent, type VocabEntry } from "../api/types";
import type { Snapshot } from "./outbox";

/**
 * 서버 스냅샷 + 아직 서버가 모르는 이벤트 → 화면 상태. 순수 함수이고, 프론트의 유일한 학습 규칙이다.
 * 모든 규칙이 멱등이다: 서버가 이미 반영한 이벤트가 아웃박스에 남아 있어도 두 번 세지 않는다.
 * 날짜·Day·스트릭 걷기는 하지 않는다(그건 서버 HabitRules 만 한다).
 */
export interface DayView {
  dashboard: Dashboard;
  stale: boolean;
  today: SessionData | null;
  review: SessionData | null;
  gate: { required: boolean; open: boolean; seen: number; total: number; yesterdayDay: number | null };
  todayProgress: { total: number; audioSeen: number; cardSeen: number; achieved: boolean };
  reviewProgress: { days: number[]; total: number; seen: number };
  streak: { current: number; longest: number; freezesLeft: number; perPeriod: number; achievedToday: boolean };
  morningLock: { windowStart: number; windowEnd: number; decided: boolean; studiedToday: boolean; sentences: Sentence[] };
  focusSecondsToday: number;
  /** 단어장(최근 담은 것 먼저). 서버 목록 + 아직 보내지 않은 VOCAB_* 이벤트. null 이면 아직 받지 못함. */
  vocab: VocabEntry[] | null;
}

export function applyPending(snapshot: Snapshot, pending: readonly StudyEvent[], now: number): DayView {
  const d = snapshot.dashboard;
  const inToday = (e: StudyEvent) => e.occurred_at >= d.valid_from && e.occurred_at < d.valid_until;
  const todayIds = new Set(d.today_progress.sentence_ids);

  const audioSeen = new Set(d.today_progress.audio_seen_ids);
  const cardSeen = new Set(d.today_progress.card_seen_ids);
  const reviewSeen = new Set(d.review_gate.seen_review_ids);
  const marks = new Map<number, { status: MarkStatus; at: number }>();
  const focusByStart = new Map<number, number>();
  let lockDecidedLocally = false;
  let viewedLocally = false;

  for (const e of pending) {
    if (e.type.startsWith("VOCAB_")) continue; // 단어장은 applyVocab 이 따로 처리(날짜와 무관)
    if (e.type === "MARK") {
      const prev = marks.get(e.sentence_id);
      if (!prev || prev.at <= e.occurred_at) marks.set(e.sentence_id, { status: e.status, at: e.occurred_at });
      continue;
    }
    if (!inToday(e)) continue;
    switch (e.type) {
      case "VIEW": {
        viewedLocally = true;
        // 서버와 같은 재분류: 오늘 레슨이 아닌 TODAY 열람은 복습으로 센다.
        const session = e.session === "TODAY" && !todayIds.has(e.sentence_id) ? "REVIEW" : e.session;
        if (session === "REVIEW") reviewSeen.add(e.sentence_id);
        else if (e.via === "AUDIO") audioSeen.add(e.sentence_id);
        else cardSeen.add(e.sentence_id);
        break;
      }
      case "FOCUS":
        if (!d.focus.started_ats.includes(e.started_at)) {
          focusByStart.set(e.started_at, Math.max(focusByStart.get(e.started_at) ?? 0, e.seconds));
        }
        break;
      case "MORNING_LOCK":
        lockDecidedLocally = true;
        break;
    }
  }

  const total = d.today_progress.total;
  const coversAll = (seen: Set<number>) => total > 0 && d.today_progress.sentence_ids.every((id) => seen.has(id));
  const achieved = d.streak.achieved_today || coversAll(audioSeen) || coversAll(cardSeen);
  const current = d.streak.achieved_today ? d.streak.current : achieved ? d.streak.if_achieved_today : d.streak.current;

  const unlock = d.review_gate.unlock_ids;
  const gateSeen = unlock.filter((id) => reviewSeen.has(id)).length;
  const gateOpen = !d.review_gate.required || (unlock.length > 0 && gateSeen === unlock.length);

  const reviewIds = snapshot.review?.sentences.map((s) => s.id) ?? [];
  const reviewSeenCount = reviewIds.length > 0 ? reviewIds.filter((id) => reviewSeen.has(id)).length : d.review_progress.seen;

  const withMarks = (session: SessionData | null): SessionData | null => {
    if (!session || marks.size === 0) return session;
    let changed = false;
    const sentences = session.sentences.map((s) => {
      const m = marks.get(s.id);
      if (!m || (s.status_changed_at !== null && s.status_changed_at >= m.at) || s.status === m.status) return s;
      changed = true;
      return { ...s, status: m.status, status_changed_at: m.at };
    });
    return changed ? { ...session, sentences } : session;
  };

  const serverStudied =
    d.today_progress.audio_seen_ids.length > 0 || d.today_progress.card_seen_ids.length > 0 || d.review_gate.seen_review_ids.length > 0;

  let focusPending = 0;
  focusByStart.forEach((sec) => (focusPending += sec));

  return {
    dashboard: d,
    stale: now >= d.valid_until,
    today: withMarks(snapshot.today),
    review: withMarks(snapshot.review),
    gate: {
      required: d.review_gate.required,
      open: gateOpen,
      seen: gateSeen,
      total: unlock.length,
      yesterdayDay: d.review_gate.yesterday_day,
    },
    todayProgress: { total, audioSeen: countIn(audioSeen, todayIds), cardSeen: countIn(cardSeen, todayIds), achieved },
    reviewProgress: { days: d.review_progress.days, total: d.review_progress.total, seen: reviewSeenCount },
    streak: {
      current,
      longest: Math.max(d.streak.longest, current),
      freezesLeft: d.streak.freezes_left,
      perPeriod: d.streak.freezes_per_period,
      achievedToday: achieved,
    },
    morningLock: {
      windowStart: d.morning_lock.window_start,
      windowEnd: d.morning_lock.window_end,
      decided: d.morning_lock.state !== "NONE" || lockDecidedLocally,
      studiedToday: serverStudied || viewedLocally,
      sentences: d.morning_lock.sentences,
    },
    focusSecondsToday: d.focus.seconds_today + focusPending,
    vocab: applyVocab(snapshot, pending),
  };
}

/**
 * 단어장 오버레이. 멱등: 서버가 이미 반영한 이벤트가 남아 있어도 결과가 같다
 * (이미 있는 표현 담기는 무시, 빼기·외움·수정은 같은 값으로 덮어씀).
 */
function applyVocab(snapshot: Snapshot, pending: readonly StudyEvent[]): VocabEntry[] | null {
  const server = snapshot.vocab ?? null;
  const vocabEvents = pending.filter((e) => e.type.startsWith("VOCAB_"));
  if (server === null && vocabEvents.length === 0) return null;

  const sentences = new Map<number, Sentence>();
  for (const s of [...(snapshot.today?.sentences ?? []), ...(snapshot.review?.sentences ?? [])]) sentences.set(s.id, s);

  const map = new Map<string, VocabEntry>((server ?? []).map((v) => [v.term_key, v]));
  for (const e of vocabEvents) {
    switch (e.type) {
      case "VOCAB_ADD": {
        const key = termKey(e.text);
        if (map.has(key)) break;
        const s = e.sentence_id !== null ? sentences.get(e.sentence_id) : undefined;
        const known = s?.terms?.find((t) => t.term_key === key);
        map.set(key, {
          term_key: key,
          text: known?.text ?? e.text,
          meaning: e.meaning ?? known?.meaning ?? null,
          nuance: null,
          examples: [],
          status: e.meaning ? "READY" : "PENDING",
          source: e.meaning ? "MANUAL" : known ? "CONTENT" : "GENERATED",
          note: e.note,
          mastered: false,
          added_at: e.occurred_at,
          sentence: s ? { id: s.id, day: s.day, en_text: s.en_text, ko_natural: s.ko_natural } : null,
        });
        break;
      }
      case "VOCAB_REMOVE":
        map.delete(e.term_key);
        break;
      case "VOCAB_MASTER": {
        const v = map.get(e.term_key);
        if (v) map.set(e.term_key, { ...v, mastered: e.mastered });
        break;
      }
      case "VOCAB_EDIT": {
        const v = map.get(e.term_key);
        if (v) map.set(e.term_key, { ...v, meaning: e.meaning ?? v.meaning, note: e.note, source: "MANUAL", status: e.meaning ? "READY" : v.status });
        break;
      }
    }
  }
  return [...map.values()].sort((a, b) => b.added_at - a.added_at);
}

function countIn(seen: Set<number>, ids: Set<number>): number {
  let n = 0;
  seen.forEach((id) => {
    if (ids.has(id)) n++;
  });
  return n;
}

/** 모닝 락을 지금 띄울지. 앱을 열 때와 화면 복귀 때만 묻는다(SPEC F-04-2). */
export function shouldShowMorningLock(view: DayView, now: number, launchedByAutoplay: boolean): boolean {
  const m = view.morningLock;
  return (
    !launchedByAutoplay &&
    !view.stale &&
    m.sentences.length > 0 &&
    !m.decided &&
    !m.studiedToday &&
    now >= m.windowStart &&
    now < m.windowEnd
  );
}
