// 모닝 보이스 락 일치율(SPEC F-04-2): 정답 단어 중 인식된 단어의 비율. 중복은 개수만큼 센다.

const CONTRACTIONS: [RegExp, string][] = [
  [/\bcan't\b/g, "can not"],
  [/\bwon't\b/g, "will not"],
  [/\bshan't\b/g, "shall not"],
  [/\blet's\b/g, "let us"],
  [/\bi'm\b/g, "i am"],
  [/\b(it|that|what|there|here|he|she|who|where|how)'s\b/g, "$1 is"],
  [/n't\b/g, " not"],
  [/'re\b/g, " are"],
  [/'ve\b/g, " have"],
  [/'ll\b/g, " will"],
  [/'d\b/g, " would"],
];

const NUMBER_WORDS = [
  "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
  "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
];
const TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"];

function numberToWords(n: number): string {
  if (n < 20) return NUMBER_WORDS[n];
  if (n < 100) return TENS[Math.floor(n / 10)] + (n % 10 ? " " + NUMBER_WORDS[n % 10] : "");
  return String(n);
}

export function normalizeWords(text: string): string[] {
  let t = text.toLowerCase().replace(/[’‘`]/g, "'");
  for (const [re, rep] of CONTRACTIONS) t = t.replace(re, rep);
  // 7:30 → seven thirty, $5.50 → five fifty, 2 → two
  t = t.replace(/\d+/g, (m) => ` ${numberToWords(Number(m))} `);
  t = t.replace(/o'clock/g, "oclock").replace(/[^a-z\s]/g, " ");
  return t.split(/\s+/).filter(Boolean);
}

export interface MatchResult {
  ratio: number;
  /** 정답 단어별 일치 여부(화면 강조용, 정답 순서). */
  hits: { word: string; hit: boolean }[];
}

export function matchWords(target: string, spoken: string): MatchResult {
  const expected = normalizeWords(target);
  const bag = new Map<string, number>();
  for (const w of normalizeWords(spoken)) bag.set(w, (bag.get(w) ?? 0) + 1);
  const hits = expected.map((word) => {
    const left = bag.get(word) ?? 0;
    if (left > 0) bag.set(word, left - 1);
    return { word, hit: left > 0 };
  });
  const matched = hits.filter((h) => h.hit).length;
  return { ratio: expected.length ? matched / expected.length : 0, hits };
}

export const PASS_RATIO = 0.6;
