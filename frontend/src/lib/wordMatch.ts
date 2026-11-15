// 모닝 보이스 락 일치율(SPEC F-04-2): 정답 단어 중 순서대로 인식된 단어의 비율. 받아 적기 통과에도 같은 규칙을 쓴다.

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

/**
 * 정답 순서를 지킨 단어만 센다(최장 공통 부분 수열). 단어를 뒤섞어 말하면 통과하지 못한다:
 * "I need to go home"에 "home go to need I"는 20%다. 중간에 낀 군말("um")이나 빠진 단어는 그 단어만 손해를 본다.
 */
export function matchWords(target: string, spoken: string): MatchResult {
  const expected = normalizeWords(target);
  const heard = normalizeWords(spoken);
  const n = expected.length;
  const m = heard.length;
  // lcs[i][j] = expected[i..], heard[j..] 의 최장 공통 부분 수열 길이
  const lcs: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = expected[i] === heard[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const hit = new Array<boolean>(n).fill(false);
  for (let i = 0, j = 0; i < n && j < m; ) {
    if (expected[i] === heard[j]) {
      hit[i] = true;
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      i++;
    } else {
      j++;
    }
  }
  const hits = expected.map((word, i) => ({ word, hit: hit[i] }));
  return { ratio: n ? lcs[0][0] / n : 0, hits };
}

export const PASS_RATIO = 0.6;

/**
 * 화면에 그릴 원문 단어별 일치 여부. 채점은 정규화한 단어(소문자, 문장부호 없음, 축약 풀기, 숫자→영어)로 하지만
 * 화면에는 원문을 그대로 둔다(정규화한 단어를 그리면 대소문자·길이·줄바꿈이 바뀌어 글자가 달라 보인다).
 * 원문 단어 하나가 정규화 단어 여럿이 되면("don't" → do not) 모두 맞았을 때만 맞은 것으로 본다.
 * 문장부호만 있는 조각은 채점 대상이 아니므로 맞은 것으로 그린다.
 */
export function displayHits(target: string, result: MatchResult): { text: string; hit: boolean }[] {
  let k = 0;
  return target
    .split(/\s+/)
    .filter(Boolean)
    .map((text) => {
      const count = normalizeWords(text).length;
      const parts = result.hits.slice(k, k + count);
      k += count;
      return { text, hit: parts.every((p) => p.hit) };
    });
}
