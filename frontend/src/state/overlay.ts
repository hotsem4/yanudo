import {
  termKey,
  type Dashboard,
  type MarkStatus,
  type Sentence,
  type SessionData,
  type StudyEvent,
  type VocabEntry,
  type VocabGenerator,
  type LockState,
} from "../api/types";
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
  /** spoken: 따라 말하기로 통과한 수(오늘 달성 기준), spokenIds: 그 문장들. */
  todayProgress: { total: number; audioSeen: number; cardSeen: number; spoken: number; spokenIds: ReadonlySet<number>; achieved: boolean };
  reviewProgress: { days: number[]; total: number; seen: number };
  streak: { current: number; longest: number; freezesLeft: number; perPeriod: number; achievedToday: boolean };
  morningLock: { windowStart: number; windowEnd: number; decided: boolean; result: LockState; studiedToday: boolean; sentences: Sentence[] };
  focusSecondsToday: number;
  /** 단어장(최근 담은 것 먼저). 서버 목록 + 아직 보내지 않은 VOCAB_* 이벤트. null 이면 아직 받지 못함. */
  vocab: VocabEntry[] | null;
  /** 뜻·예문 생성기 상태. 아직 받지 못했으면 null. */
  vocabGenerator: VocabGenerator | null;
  /** 아직 보내지 않은 말하기 연습 완료(문장 id). 서버 목록과 합쳐 완료 표시를 바로 보인다. */
  speakPendingIds: number[];
  /** "오늘은 어려워요" 상태(아직 보내지 않은 알림 포함). 예전 버전이 저장한 스냅샷이라 모르면 null(버튼을 숨긴다). */
  restDay: { declaredToday: boolean; sending: boolean; left: number; perPeriod: number; period: readonly [number, number] | null } | null;
  /** 끝내면 연속 기록을 지키는 보충. 아직 보내지 않은 SPEAK 를 합쳐, 다 끝낸 것은 뺀다. */
  makeupsDue: MakeupDue[];
}

export interface MakeupDue {
  day: number;
  /** 빼먹은 날짜(YYYY-MM-DD). 2라운드부터는 Day(레슨 번호)와 달력 Day 가 달라 날짜로 부른다. */
  date: string;
  effect?: import("../api/types").MakeupEffect;
  streakIfDone?: number;
  rest: boolean;
  reps: number;
  deadline: string;
  total: number;
  done: number;
}

export function applyPending(snapshot: Snapshot, pending: readonly StudyEvent[], now: number): DayView {
  const d = snapshot.dashboard;
  const inToday = (e: StudyEvent) => e.occurred_at >= d.valid_from && e.occurred_at < d.valid_until;
  const todayIds = new Set(d.today_progress.sentence_ids);

  const audioSeen = new Set(d.today_progress.audio_seen_ids);
  const cardSeen = new Set(d.today_progress.card_seen_ids);
  const spokenSeen = new Set(d.today_progress.spoken_seen_ids ?? []);
  const reviewSeen = new Set(d.review_gate.seen_review_ids);
  const marks = new Map<number, { status: MarkStatus; at: number }>();
  const focusByStart = new Map<number, number>();
  let lockDecidedLocally = false;
  let localLock: LockState = "NONE";
  let viewedLocally = false;
  let restLocally = false;

  const speakPendingIds: number[] = [];
  const speakPending: { id: number; reps: number; at: number }[] = [];
  for (const e of pending) {
    if (e.type.startsWith("VOCAB_")) continue; // 단어장은 applyVocab 이 따로 처리(날짜와 무관)
    if (e.type === "SPEAK") {
      speakPendingIds.push(e.sentence_id);
      speakPending.push({ id: e.sentence_id, reps: e.reps, at: e.occurred_at });
      continue;
    }
    if (e.type === "MARK") {
      const prev = marks.get(e.sentence_id);
      if (!prev || prev.at <= e.occurred_at) marks.set(e.sentence_id, { status: e.status, at: e.occurred_at });
      continue;
    }
    if (!inToday(e)) continue;
    switch (e.type) {
      case "REST_DAY":
        restLocally = true;
        break;
      case "VIEW": {
        viewedLocally = true;
        // 서버와 같은 재분류: 오늘 레슨이 아닌 TODAY 열람은 복습으로 센다.
        const session = e.session === "TODAY" && !todayIds.has(e.sentence_id) ? "REVIEW" : e.session;
        if (session === "REVIEW") reviewSeen.add(e.sentence_id);
        else if (e.via === "AUDIO") audioSeen.add(e.sentence_id);
        else if (e.via === "SPEAK") spokenSeen.add(e.sentence_id);
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
        // 서버와 같이 그날 첫 결정을 따른다.
        if (localLock === "NONE") localLock = e.result;
        break;
    }
  }

  const total = d.today_progress.total;
  const coversAll = (seen: Set<number>) => total > 0 && d.today_progress.sentence_ids.every((id) => seen.has(id));
  // 학습 앱: 오늘 달성은 10문장을 모두 따라 말하기로 통과했을 때(듣기·카드는 진행으로만 보인다).
  const achieved = d.streak.achieved_today || coversAll(spokenSeen);
  const current = d.streak.achieved_today ? d.streak.current : achieved ? d.streak.if_achieved_today : d.streak.current;

  const unlock = d.review_gate.unlock_ids;
  const gateSeen = unlock.filter((id) => reviewSeen.has(id)).length;
  const gateOpen = d.review_gate.cleared || !d.review_gate.required || gateSeen === unlock.length;

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
    d.today_progress.audio_seen_ids.length > 0 ||
    d.today_progress.card_seen_ids.length > 0 ||
    (d.today_progress.spoken_seen_ids ?? []).length > 0 ||
    d.review_gate.seen_review_ids.length > 0;

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
    todayProgress: {
      total,
      audioSeen: countIn(audioSeen, todayIds),
      cardSeen: countIn(cardSeen, todayIds),
      spoken: countIn(spokenSeen, todayIds),
      spokenIds: spokenSeen,
      achieved,
    },
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
      result: d.morning_lock.state !== "NONE" ? d.morning_lock.state : localLock,
      studiedToday: serverStudied || viewedLocally,
      sentences: d.morning_lock.sentences,
    },
    focusSecondsToday: d.focus.seconds_today + focusPending,
    vocab: applyVocab(snapshot, pending),
    vocabGenerator: snapshot.vocabGenerator ?? null,
    speakPendingIds,
    restDay: restDayView(d, restLocally),
    makeupsDue: makeupsDueView(d, speakPending),
  };
}

/** "오늘은 어려워요". 예전 버전이 저장한 스냅샷에는 rest_day 가 없어 남은 횟수를 모른다(null). */
function restDayView(d: Dashboard, restLocally: boolean): DayView["restDay"] {
  const server = d.rest_day;
  if (!server) return null;
  return {
    declaredToday: server.declared_today || restLocally,
    sending: restLocally && !server.declared_today,
    left: server.declared_today || !restLocally ? server.left : Math.max(0, server.left - 1),
    perPeriod: server.per_period,
    period: server.period_from && server.period_to ? [server.period_from, server.period_to] : null,
  };
}

/**
 * 보충 목록 + 아직 보내지 않은 SPEAK. 서버와 같이 필요한 횟수 이상인 것만, 그리고 오늘(valid_from 이후) 한 것만 센다
 * (보충할 날짜는 모두 오늘 전이라 오늘 기록은 항상 "그 날짜 다음 날부터"에 든다). 다 끝낸 것은 뺀다.
 */
function makeupsDueView(d: Dashboard, speakPending: readonly { id: number; reps: number; at: number }[]): MakeupDue[] {
  return (d.makeups_due ?? [])
    .map((m) => {
      const ids = m.sentence_ids ?? [];
      const done = new Set(m.done_ids ?? []);
      const counted = new Set(speakPending.filter((p) => p.reps >= m.reps && p.at >= d.valid_from).map((p) => p.id));
      ids.forEach((id) => counted.has(id) && done.add(id));
      return {
        day: m.day,
        date: m.date,
        effect: m.effect,
        streakIfDone: m.streak_if_done,
        rest: m.rest,
        reps: m.reps,
        deadline: m.deadline,
        total: ids.length,
        done: done.size,
      };
    })
    .filter((m) => m.done < m.total);
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
        // 서버 목록에 있던 표현을 뺐다가 다시 담은 경우(이미 서버에 반영됐을 수도 있다): 서버가 가진 뜻·예문을 그대로 쓴다.
        const fromServer = server?.find((v) => v.term_key === key);
        if (fromServer) {
          map.set(key, {
            ...fromServer,
            meaning: e.meaning ?? fromServer.meaning,
            source: e.meaning ? "MANUAL" : fromServer.source,
            status: e.meaning ? "READY" : fromServer.status,
            note: e.note,
            mastered: false,
            added_at: e.occurred_at,
          });
          break;
        }
        const s = e.sentence_id !== null ? sentences.get(e.sentence_id) : undefined;
        const known = s?.terms?.find((t) => t.term_key === key);
        map.set(key, {
          term_key: key,
          text: known?.text ?? e.text,
          meaning: e.meaning ?? known?.meaning ?? null,
          nuance: null,
          examples: [],
          // 미리 만든 표현(known)은 서버도 만들지 않고 READY 로 둔다. 기다리는 것처럼 보이지 않게 같은 값을 쓴다.
          status: e.meaning || known ? "READY" : "PENDING",
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
        // 서버와 같은 규칙: 뜻을 고쳤을 때만 직접 입력(MANUAL)이 된다. 메모만 고치면 출처는 그대로다.
        if (v) map.set(e.term_key, { ...v, meaning: e.meaning ?? v.meaning, note: e.note, source: e.meaning ? "MANUAL" : v.source, status: e.meaning ? "READY" : v.status });
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
export function shouldShowMorningLock(view: DayView, now: number, autoplayExempt: boolean): boolean {
  const m = view.morningLock;
  return (
    !autoplayExempt &&
    !view.stale &&
    m.sentences.length > 0 &&
    !m.decided &&
    !m.studiedToday &&
    now >= m.windowStart &&
    now < m.windowEnd
  );
}
