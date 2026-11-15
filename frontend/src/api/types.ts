// SPEC.md 4장의 와이어 타입. snake_case 를 그대로 쓴다(CLAUDE.md: 변환 레이어 금지).
// 시각 필드(*_at, server_time, valid_*, window_*)는 모두 epoch ms.

export type Speaker = "A" | "B";
export type Session = "TODAY" | "REVIEW";
export type Via = "AUDIO" | "CARD";
export type MarkStatus = "NEW" | "MEMORIZED" | "PRACTICE";
export type LockResult = "CLEARED" | "SKIPPED";
export type LockState = "NONE" | LockResult;
export type CellStatus = "ACHIEVED" | "FROZEN" | "MISSED" | "TODAY" | "FUTURE";

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

export interface VocabList {
  entries: VocabEntry[];
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
  program_complete: boolean;
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

export interface Heatmap {
  start_date: string;
  current_day: number;
  program_complete: boolean;
  current_streak: number;
  longest_streak: number;
  achieved_days: number;
  freezes_left: number;
  cells: HeatCell[];
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
