import { termKey, type SentenceTerm, type VocabEntry } from "../api/types";

/** 단어장 화면의 순수 함수들(SPEC F-08). 서버 규칙은 건드리지 않고 화면에 필요한 가공만 한다. */

export type VocabFilter = "learning" | "mastered" | "all";

export const VOCAB_FILTERS: readonly VocabFilter[] = ["learning", "mastered", "all"];

/** 담은 뒤 이 시간 안이면 예문 MP3 를 서버가 아직 만드는 중으로 본다(보통 30초 안팎). 넘으면 만들지 못한 것으로 보고 기기 음성으로 읽는다. */
export const EXAMPLE_AUDIO_WAIT_MS = 10 * 60_000;

/** 예문 음성(MP3)을 서버가 아직 만드는 중인가. 시각은 서버가 준 added_at 과 서버 시계 추정값으로 비교한다. */
export function examplesPreparing(entry: VocabEntry, now: number): boolean {
  return entry.examples.some((x) => !x.audio_url) && now - entry.added_at < EXAMPLE_AUDIO_WAIT_MS;
}

export function filterVocab(entries: readonly VocabEntry[], filter: VocabFilter): VocabEntry[] {
  if (filter === "all") return [...entries];
  return entries.filter((v) => (filter === "mastered" ? v.mastered : !v.mastered));
}

export function vocabCounts(entries: readonly VocabEntry[]): Record<VocabFilter, number> {
  const mastered = entries.filter((v) => v.mastered).length;
  return { learning: entries.length - mastered, mastered, all: entries.length };
}

/** 뜻을 직접 적어야 하는 상태: 생성에 실패했거나, 끝났는데도 뜻이 비어 있다. */
export function needsMeaning(entry: VocabEntry): boolean {
  return entry.status === "FAILED" || (entry.status === "READY" && !entry.meaning);
}

export interface WordChip {
  /** 담을 때 서버로 보내는 글자. */
  text: string;
  key: string;
}

const EDGE_PUNCT = /^[^A-Za-z0-9']+|[^A-Za-z0-9']+$/g;

/**
 * 문장을 담을 수 있는 단어 칩으로 나눈다.
 * - 앞뒤 구두점을 떼고(I'm, don't 의 아포스트로피는 남긴다), 숫자뿐인 조각은 버린다.
 * - 같은 단어는 한 번만. 대소문자는 구분하지 않는다.
 * - 미리 만든 표현(text 또는 문장 속 형태)에 들어 있는 단어는 뺀다. 위쪽 표현 목록과 겹치지 않게.
 * - 문장 첫 단어는 소문자로 돌린다. 단, I 와 I'm 같은 축약은 그대로 둔다.
 */
export function sentenceWords(enText: string, terms: readonly SentenceTerm[] = []): WordChip[] {
  const covered = new Set<string>();
  for (const t of terms) {
    for (const phrase of [t.text, t.form ?? ""]) {
      for (const w of phrase.split(/\s+/)) {
        const k = termKey(w.replace(EDGE_PUNCT, ""));
        if (k) covered.add(k);
      }
    }
  }

  const seen = new Set<string>();
  const chips: WordChip[] = [];
  enText.split(/\s+/).forEach((raw, i) => {
    let word = raw.replace(EDGE_PUNCT, "").replace(/^'+|'+$/g, "");
    if (!word || !/[A-Za-z]/.test(word)) return;
    const startsSentence = i === 0 || /[.!?]["')]*$/.test(enText.split(/\s+/)[i - 1] ?? "");
    if (startsSentence && !/^I('|$)/.test(word)) word = word.charAt(0).toLowerCase() + word.slice(1);
    const key = termKey(word);
    if (seen.has(key) || covered.has(key)) return;
    seen.add(key);
    chips.push({ text: word, key });
  });
  return chips;
}

/** 예문 듣기에 쓰는 음수 id. 학습 문장 id(양수)와 겹치지 않고 표현·예문마다 고르게 다르다. */
export function exampleItemId(key: string, index: number): number {
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) % 1_000_000;
  return -(h * 10 + index + 1);
}
