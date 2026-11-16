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
  if (n < 1000) return NUMBER_WORDS[Math.floor(n / 100)] + " hundred" + (n % 100 ? " " + numberToWords(n % 100) : "");
  if (n < 10000) return numberToWords(Math.floor(n / 1000)) + " thousand" + (n % 1000 ? " " + numberToWords(n % 1000) : "");
  return String(n);
}

const ORDINALS: Record<string, string> = {
  "1": "first", "2": "second", "3": "third", "4": "fourth", "5": "fifth", "6": "sixth", "7": "seventh", "8": "eighth",
  "9": "ninth", "10": "tenth", "11": "eleventh", "12": "twelfth", "13": "thirteenth", "20": "twentieth", "21": "twenty first",
  "22": "twenty second", "23": "twenty third", "30": "thirtieth", "31": "thirty first",
};

export function normalizeWords(text: string): string[] {
  let t = text.toLowerCase().replace(/[’‘`]/g, "'");
  for (const [re, rep] of CONTRACTIONS) t = t.replace(re, rep);
  // 1st → first, 22nd → twenty second(정해 둔 것만), 그 밖의 서수는 숫자만 읽는다.
  t = t.replace(/\b(\d+)(st|nd|rd|th)\b/g, (_m, d: string) => ` ${ORDINALS[d] ?? numberToWords(Number(d))} `);
  // 7:30 → seven thirty, $5.50 → five fifty, 2 → two, 150 → one hundred fifty
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
  const proper = properFlags(target);
  const n = expected.length;
  const m = heard.length;
  // 고유명사(문장 가운데 대문자로 시작하는 이름·지명)는 인식기가 철자를 달리 받아 적기 쉽다(Seoul → soul, sole).
  // 소리가 비슷하면(자음 골격이 같으면) 맞은 것으로 친다. 일반 단어에는 쓰지 않는다(너무 느슨해진다).
  const same = (i: number, j: number) =>
    expected[i] === heard[j] || (proper[i] && soundKey(expected[i]).length >= 2 && soundKey(expected[i]) === soundKey(heard[j]));
  // lcs[i][j] = expected[i..], heard[j..] 의 최장 공통 부분 수열 길이
  const lcs: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = same(i, j) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const hit = new Array<boolean>(n).fill(false);
  for (let i = 0, j = 0; i < n && j < m; ) {
    if (same(i, j)) {
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

/** 정규화한 단어마다 고유명사인가(원문에서 문장 첫머리가 아닌데 대문자로 시작하는 단어, "I" 는 제외). */
function properFlags(target: string): boolean[] {
  const out: boolean[] = [];
  const tokens = target.split(/\s+/).filter(Boolean);
  tokens.forEach((token, k) => {
    const bare = token.replace(/^[^A-Za-z]+/, "");
    const prev = k > 0 ? tokens[k - 1] : "";
    const proper = k > 0 && /^[A-Z][a-z]/.test(bare) && !/[.!?]["')]*$/.test(prev);
    for (let i = 0; i < normalizeWords(token).length; i++) out.push(proper);
  });
  return out;
}

/** 소리 골격: 첫 글자 + 모음·h·w·y 를 뺀 자음(비슷한 소리는 하나로), 겹친 글자는 하나로. seoul·soul·sole → "sl". */
function soundKey(word: string): string {
  if (!word) return "";
  const s = word.toLowerCase().replace(/ph/g, "f").replace(/[ckq]/g, "k").replace(/z/g, "s").replace(/v/g, "f");
  const key = s[0] + s.slice(1).replace(/[aeiouyhw]/g, "");
  return key.replace(/(.)\1+/g, "$1");
}

/** 인식이 어려운 것으로 넘긴 단어(정답 단어 위치)를 맞은 것으로 바꾼 결과. */
export function excuseWords(result: MatchResult, excused: ReadonlySet<number> | undefined): MatchResult {
  if (!excused || excused.size === 0) return result;
  const hits = result.hits.map((h, i) => (excused.has(i) ? { ...h, hit: true } : h));
  const n = hits.length;
  return { ratio: n ? hits.filter((h) => h.hit).length / n : 0, hits };
}

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
