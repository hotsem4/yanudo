import type { Dashboard, SessionData, StudyEvent, VocabEntry, VocabGenerator } from "../api/types";

// localStorage 영속화. 접근이 막혀 있어도(사생활 모드 등) 앱은 메모리로 계속 돈다.
const OUTBOX_KEY = "habit66.outbox.v1";
const SNAPSHOT_KEY = "habit66.snapshot.v1";
const HELD_KEY = "habit66.outbox.held.v1";

export interface Snapshot {
  dashboard: Dashboard;
  today: SessionData | null;
  review: SessionData | null;
  /** 단어장. 아직 받지 못했으면 null(이전 캐시에는 없을 수 있다). */
  vocab?: VocabEntry[] | null;
  /** 단어장과 함께 받은 생성기 상태(이전 캐시에는 없을 수 있다). */
  vocabGenerator?: VocabGenerator | null;
}

function readJson<T>(key: string): T | null {
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

function writeJson(key: string, value: unknown): boolean {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

export const outbox = {
  key: OUTBOX_KEY,
  load(): StudyEvent[] {
    const list = readJson<StudyEvent[]>(OUTBOX_KEY);
    return Array.isArray(list) ? list : [];
  },
  /** 동기 저장. 화면 반영보다 먼저 부른다(docs/architecture.md "프론트 상태"). */
  save(events: StudyEvent[]): boolean {
    return writeJson(OUTBOX_KEY, events);
  },
  /** 서버가 계속 거절해 따로 둔 기록. 버리지 않고 남겨 둔다(나중에 원인을 고친 뒤 확인할 수 있게). */
  heldCount(): number {
    const list = readJson<StudyEvent[]>(HELD_KEY);
    return Array.isArray(list) ? list.length : 0;
  },
  hold(event: StudyEvent): boolean {
    const list = readJson<StudyEvent[]>(HELD_KEY);
    return writeJson(HELD_KEY, [...(Array.isArray(list) ? list : []), event]);
  },
  /** 보관한 기록(앱을 시작할 때마다 한 번 더 보내 본다). */
  loadHeld(): StudyEvent[] {
    const list = readJson<StudyEvent[]>(HELD_KEY);
    return Array.isArray(list) ? list : [];
  },
  /** 보관한 기록을 아웃박스로 옮겨 저장한 뒤에만 부른다(먼저 비우면 저장 실패 때 기록을 잃는다). */
  clearHeld(): void {
    writeJson(HELD_KEY, []);
  },
};

export const snapshotCache = {
  load(): Snapshot | null {
    const snap = readJson<Snapshot>(SNAPSHOT_KEY);
    return snap && snap.dashboard ? snap : null;
  },
  save(snapshot: Snapshot): void {
    writeJson(SNAPSHOT_KEY, snapshot);
  },
};

export function newEventId(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === "function") return c.randomUUID();
  const bytes = new Uint8Array(16);
  if (c && typeof c.getRandomValues === "function") c.getRandomValues(bytes);
  else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}
