// SPEC.md 4장의 와이어 타입. snake_case 를 그대로 쓴다(CLAUDE.md: 변환 레이어 금지).
// 시각 필드(*_at, server_time, valid_*, window_*)는 모두 epoch ms.

export type Speaker = "A" | "B";
export type Session = "TODAY" | "REVIEW";
/** SPEAK: 오늘의 10문장을 따라 말하기(또는 받아 적기)로 통과. 오늘 달성은 이것만 센다. */
export type Via = "AUDIO" | "CARD" | "SPEAK";
export type MarkStatus = "NEW" | "MEMORIZED" | "PRACTICE";
export type LockResult = "CLEARED" | "SKIPPED";
export type LockState = "NONE" | LockResult;
/** REPAIRED: 빼먹었지만 기한 안에 보충해서 연속 기록이 이어진 날. */
export type CellStatus = "ACHIEVED" | "FROZEN" | "REPAIRED" | "MISSED" | "TODAY" | "FUTURE";

export interface Sentence {
  id: number;
  day: number;
  seq: number;
  speaker: Speaker;
  en_text: string;
  ko_word_order: string;
  ko_natural: string;
  core: boolean;
  status: MarkStatus;
  status_changed_at: number | null;
  audio_url: string | null;
  duration_ms: number;
  duration_estimated: boolean;
  /** 이 문장에서 미리 만든 표현(단어 고르기용, SPEC 4.1). 단어장 이전 서버·캐시에는 없을 수 있다. */
  terms?: SentenceTerm[];
}

export interface SentenceTerm {
  term_key: string;
  text: string;
  /** 문장에 실제로 나온 형태(없으면 text 와 같음). */
  form: string | null;
  meaning: string | null;
}

// ── 단어장 (SPEC F-08, 4.9) ─────────────────────────────────────

export type TermStatus = "READY" | "PENDING" | "FAILED";
export type TermSource = "CONTENT" | "GENERATED" | "MANUAL";

export interface VocabExample {
  en: string;
  ko: string;
  audio_url: string | null;
  duration_ms: number;
  duration_estimated: boolean;
}

export interface VocabEntry {
  term_key: string;
  text: string;
  meaning: string | null;
  nuance: string | null;
  examples: VocabExample[];
  status: TermStatus;
  source: TermSource;
  note: string | null;
  mastered: boolean;
  added_at: number;
  sentence: { id: number; day: number; en_text: string; ko_natural: string } | null;
}

/** 뜻·예문 생성기 상태(SPEC 4.9). LOGIN_REQUIRED 면 PC 의 Claude 로그인이 끊겨 새 단어 생성이 멈춘 상태. */
export type GeneratorState = "OK" | "EXPIRING" | "LOGIN_REQUIRED" | "OFF";

export interface VocabGenerator {
  state: GeneratorState;
  message: string | null;
  expires_at: number | null;
}

/** 말하기 연습(SPEC F-09): 배운 Day 목록(최근 먼저). */
export interface SpeakOverview {
  lesson_day: number;
  days: SpeakDayRow[];
}

/** OPEN: 자유 연습. MAKEUP: 빼먹은 날 보충. TODAY_LOCKED: 오늘의 10문장을 끝내기 전. */
export type SpeakState = "OPEN" | "MAKEUP" | "TODAY_LOCKED";

/**
 * 말하기 목록의 한 Day. done_ids: OPEN 은 연습을 끝낸 문장, MAKEUP 은 보충으로 센 문장.
 * reps: 문장마다 맞게 말할 횟수(미리 알린 날 보충은 1). seen: TODAY_LOCKED 일 때 오늘의 10문장 진행.
 * deadline·repairable: MAKEUP 일 때 이날까지 끝내면 그날 연속 기록이 이어진다.
 */
export interface SpeakDayRow {
  day: number;
  title: string;
  sentence_ids: number[];
  done_ids: number[];
  state: SpeakState;
  reps: number;
  seen: number;
  rest: boolean;
  deadline: string | null;
  repairable: boolean;
}

/** 말하기 연습 한 Day 의 문장과 진행. */
export interface SpeakLesson {
  day: number;
  title: string;
  sentences: Sentence[];
  done_ids: number[];
  state: SpeakState;
  reps: number;
  rest: boolean;
  deadline: string | null;
  repairable: boolean;
}

export interface VocabList {
  entries: VocabEntry[];
  generator?: VocabGenerator;
}

/** 서버와 같은 표현 키: 소문자, 앞뒤 공백 제거, 연속 공백 하나로(SPEC F-08). */
export function termKey(text: string): string {
  return text.toLowerCase().trim().replace(/\s+/g, " ");
}

export interface Dashboard {
  today: string;
  server_time: number;
  valid_from: number;
  valid_until: number;
  current_day: number;
  total_days: number;
  lesson_day: number;
  max_lesson_day: number;
  content_missing: boolean;
  /** 라운드(1부터, total_days 일마다). 66일은 끝이 아니라 한 바퀴다. 예전 스냅샷에는 없다. */
  round?: number;
  /** 라운드 안의 Day(1~total_days). */
  round_day?: number;
  streak: {
    current: number;
    if_achieved_today: number;
    longest: number;
    achieved_today: boolean;
    freezes_left: number;
    freezes_per_period: number;
  };
  today_progress: {
    total: number;
    sentence_ids: number[];
    audio_seen_ids: number[];
    card_seen_ids: number[];
    /** 따라 말하기로 통과한 문장. 예전 스냅샷에는 없다. */
    spoken_seen_ids?: number[];
  };
  review_gate: {
    required: boolean;
    cleared: boolean;
    yesterday_day: number | null;
    unlock_ids: number[];
    seen_review_ids: number[];
  };
  review_progress: { days: number[]; total: number; seen: number };
  morning_lock: {
    window_start: number;
    window_end: number;
    state: LockState;
    sentences: Sentence[];
  };
  focus: { seconds_today: number; started_ats: number[] };
  /** "오늘은 어려워요"(SPEC F-05): 오늘 알렸는지, 이번 30일 구간에 남은 횟수. */
  /** 이 기능 전에 저장된 스냅샷에는 없다(optional). */
  rest_day?: { declared_today: boolean; left: number; per_period: number; period_from?: number; period_to?: number };
  /** 끝내면 연속 기록을 지키는(기한 안이고 그 전날까지 연속 기록이 있는), 아직 끝내지 않은 보충. 말하기 탭에서 한다. */
  makeups_due?: { day: number; date: string; rest: boolean; reps: number; deadline: string; sentence_ids: number[]; done_ids: number[] }[];
}

export interface SessionData {
  session: Session;
  days: number[];
  title: string;
  preview: boolean;
  sentences: Sentence[];
}

export interface HeatCell {
  day: number;
  date: string;
  status: CellStatus;
}

/** 이번 라운드의 기록. cells 는 66칸, day 는 라운드 안의 Day. achieved_days 는 이번 라운드에서 달성한 날. */
export interface Heatmap {
  start_date: string;
  current_day: number;
  round: number;
  round_day: number;
  round_start: string;
  current_streak: number;
  longest_streak: number;
  achieved_days: number;
  freezes_left: number;
  cells: HeatCell[];
  past_rounds: { round: number; achieved_days: number; days: number }[];
}

interface EventBase {
  id: string;
  occurred_at: number;
}

export type StudyEvent =
  | (EventBase & { type: "VIEW"; sentence_id: number; session: Session; via: Via })
  | (EventBase & { type: "MARK"; sentence_id: number; status: MarkStatus })
  | (EventBase & { type: "FOCUS"; started_at: number; seconds: number })
  | (EventBase & { type: "MORNING_LOCK"; result: LockResult })
  /** 말하기 연습: 한 문장을 reps 번 맞게 말해 끝냄. 빼먹은 날의 보충도 이것으로 센다. */
  | (EventBase & { type: "SPEAK"; sentence_id: number; reps: number })
  /** "오늘은 어려워요": 오늘을 쉬는 날로 미리 알림(보충이 1번씩으로 가벼워진다). */
  | (EventBase & { type: "REST_DAY" })
  | (EventBase & { type: "VOCAB_ADD"; text: string; sentence_id: number | null; meaning: string | null; note: string | null })
  | (EventBase & { type: "VOCAB_REMOVE"; term_key: string })
  | (EventBase & { type: "VOCAB_MASTER"; term_key: string; mastered: boolean })
  | (EventBase & { type: "VOCAB_EDIT"; term_key: string; meaning: string | null; note: string | null });

export type VocabEventType = "VOCAB_ADD" | "VOCAB_REMOVE" | "VOCAB_MASTER" | "VOCAB_EDIT";

type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never;

/** 화면이 기록할 때 쓰는 모양. id 를 주지 않으면 스토어가 UUID 를 붙이고, occurred_at 은 늘 스토어가 찍는다. */
export type NewEvent = DistributiveOmit<StudyEvent, "id" | "occurred_at"> & { id?: string };

export interface EventsResponse {
  accepted: string[];
  rejected: { id: string; reason: string }[];
  dashboard: Dashboard;
}

export interface ApiErrorBody {
  error: string;
  message: string;
}
