/*
 * 66 Habit English 성장형 캐릭터 시안 (앱 코드 아님, 시안 전용).
 * 두 안 모두 색은 클래스로만 칠한다(.k-body 등 → mock.css 에서 토큰에 연결). SVG 안에 색 리터럴 없음.
 *
 * 성장 규칙(제안)
 *   단계 = current_day 만 본다. 하루 빠져도 되돌아가지 않는다.
 *     1단계 Day 1~2 · 2단계 Day 3~6 · 3단계 Day 7~20 · 4단계 Day 21~41 · 5단계 Day 42~65 · 6단계 Day 66 또는 program_complete
 *   기분 = 스트릭과 오늘 달성 여부
 *     party  : program_complete
 *     done   : streak.achievedToday
 *     sleepy : streak.current === 0 이고 current_day > 1 (끊긴 뒤 아직 오늘 안 함)
 *     wait   : 그 밖(오늘 아직, 스트릭은 이어지는 중 / 첫날)
 */
(function () {
  const STAGE_DAYS = [1, 3, 7, 21, 42, 66];

  function stageOf(day, complete) {
    if (complete) return 6;
    let s = 1;
    STAGE_DAYS.forEach((d, i) => {
      if (day >= d) s = i + 1;
    });
    return s;
  }

  function moodOf(d) {
    if (d.program_complete) return "party";
    if (d.achievedToday) return "done";
    if (d.streak === 0 && d.current_day > 1) return "sleepy";
    return "wait";
  }

  /* ── 공통 얼굴 ─────────────────────────────────────────── */
  function eye(x, y, mood, r) {
    r = r || 4.4;
    if (mood === "done" || mood === "party") {
      return `<path class="k-stroke-ink" d="M${x - r} ${y + 1.5} q${r} ${-r * 1.7} ${r * 2} 0" fill="none" stroke-width="2.8" stroke-linecap="round"/>`;
    }
    if (mood === "sleepy") {
      return `<path class="k-stroke-ink" d="M${x - r} ${y} q${r} ${r * 0.9} ${r * 2} 0" fill="none" stroke-width="2.6" stroke-linecap="round"/>`;
    }
    return `<circle class="k-ink" cx="${x}" cy="${y}" r="${r}"/><circle class="k-white" cx="${x - r * 0.32}" cy="${y - r * 0.36}" r="${r * 0.34}"/>`;
  }

  function cheeks(x1, x2, y, mood) {
    if (mood === "sleepy") return "";
    return `<ellipse class="k-blush" cx="${x1}" cy="${y}" rx="4.6" ry="3"/><ellipse class="k-blush" cx="${x2}" cy="${y}" rx="4.6" ry="3"/>`;
  }

  function star(x, y, s, cls) {
    const a = s, b = s * 0.34;
    return `<path class="${cls || "k-soft"} k-twinkle" d="M${x} ${y - a} Q${x + b} ${y - b} ${x + a} ${y} Q${x + b} ${y + b} ${x} ${y + a} Q${x - b} ${y + b} ${x - a} ${y} Q${x - b} ${y - b} ${x} ${y - a} Z"/>`;
  }

  function extras(mood, big) {
    if (mood === "done") return star(100, 26, 7) + star(20, 40, 5);
    if (mood === "party") {
      return (
        star(102, 24, 8) + star(18, 34, 6) + star(96, 62, 4.5, "k-light") +
        `<circle class="k-soft k-twinkle" cx="26" cy="16" r="2.6"/><circle class="k-light k-twinkle" cx="82" cy="10" r="2.2"/>` +
        (big ? `<rect class="k-soft k-twinkle" x="10" y="58" width="6" height="3" rx="1.5" transform="rotate(-24 13 59)"/>` : "")
      );
    }
    if (mood === "sleepy") {
      return `<path class="k-stroke-soft" d="M92 30 h9 l-9 9 h9" fill="none" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/><path class="k-stroke-soft" d="M105 16 h6 l-6 6 h6" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" opacity=".7"/>`;
    }
    return "";
  }

  const ground = `<ellipse class="k-ground" cx="60" cy="108" rx="30" ry="4.5"/>`;

  /* ── 1안 · 포롱: 알에서 깨어 파랑새가 된다 ─────────────────── */
  function beak(x, y, mood) {
    if (mood === "party" || mood === "done") {
      return `<path class="k-beak" d="M${x - 5.5} ${y - 1} L${x + 5.5} ${y - 1} L${x} ${y + 7.5} Z" stroke-linejoin="round"/>`;
    }
    return `<path class="k-beak" d="M${x - 5} ${y} Q${x} ${y - 3.5} ${x + 5} ${y} Q${x} ${y + 6} ${x - 5} ${y} Z"/>`;
  }

  function crest(n, x, y) {
    const tufts = [
      `<path class="k-body" d="M${x} ${y} q-3 -13 6 -17 q1 9 -1 17 Z"/>`,
      `<path class="k-soft" d="M${x + 5} ${y + 1} q4 -12 13 -11 q-3 8 -9 13 Z"/>`,
      `<path class="k-soft" d="M${x - 4} ${y + 1} q-8 -9 -15 -6 q5 6 11 9 Z"/>`,
    ];
    return tufts.slice(0, n).reverse().join("");
  }

  function bird(stage, mood) {
    let s = "";
    const shellBottom = `<path class="k-shell" d="M30 72 L39 64 L47 73 L55 64 L63 73 L71 64 L80 73 L90 72 A30 33 0 0 1 30 72 Z"/>`;
    if (stage === 1) {
      // 알: 아직 안에 있다. 알 자체가 표정을 짓는다.
      s += `<g class="k-bob"><ellipse class="k-shell" cx="60" cy="66" rx="29" ry="37"/>
        <circle class="k-speck" cx="44" cy="46" r="3.4"/><circle class="k-speck" cx="76" cy="52" r="2.6"/><circle class="k-speck" cx="70" cy="90" r="3"/><circle class="k-speck" cx="46" cy="86" r="2.2"/>
        ${eye(50, 68, mood, 3.8)}${eye(70, 68, mood, 3.8)}${cheeks(43, 77, 75, mood)}</g>`;
    } else if (stage === 2) {
      // 금 간 알: 윗껍질을 모자처럼 쓰고 눈만 내민다.
      s += `<g class="k-bob"><circle class="k-body" cx="60" cy="60" r="22"/>
        ${eye(52, 58, mood, 4)}${eye(68, 58, mood, 4)}
        <path class="k-shell" d="M38 46 L45 52 L52 45 L60 52 L68 45 L75 52 L82 46 A24 22 0 0 0 38 46 Z" transform="rotate(-9 60 40) translate(0 -5)"/></g>
        ${shellBottom}`;
    } else if (stage === 3) {
      // 아기 새: 아랫껍질에 앉아 부리를 내밀었다.
      s += `<g class="k-bob">${crest(1, 58, 30)}<circle class="k-body" cx="60" cy="54" r="26"/>
        <ellipse class="k-wing" cx="35" cy="62" rx="6" ry="9" transform="rotate(18 35 62)"/><ellipse class="k-wing" cx="85" cy="62" rx="6" ry="9" transform="rotate(-18 85 62)"/>
        ${eye(50, 50, mood)}${eye(70, 50, mood)}${beak(60, 59, mood)}${cheeks(42, 78, 59, mood)}</g>
        ${shellBottom}`;
    } else {
      const big = stage >= 5, top = stage === 6;
      const r = top ? 33 : big ? 32 : 29;
      const cy = top ? 62 : big ? 64 : 68;
      // 꼬리깃
      const tail = top
        ? `<path class="k-soft" d="M84 80 q22 2 28 22 q-18 -2 -32 -12 Z"/><path class="k-body" d="M82 84 q14 10 14 26 q-14 -6 -22 -18 Z"/><path class="k-light" d="M86 76 q20 -6 30 6 q-16 6 -32 2 Z"/>`
        : big
          ? `<path class="k-soft" d="M84 80 q18 4 22 18 q-14 -1 -26 -10 Z"/><path class="k-body" d="M82 84 q10 8 10 20 q-10 -4 -16 -14 Z"/>`
          : `<path class="k-soft" d="M82 84 q12 4 14 14 q-10 0 -18 -8 Z"/>`;
      // 날개: 단계가 오를수록 펴진다.
      const wing = top
        ? `<path class="k-wing" d="M30 66 q-22 -14 -22 -38 q16 4 30 22 Z"/><path class="k-wing" d="M90 66 q22 -14 22 -38 q-16 4 -30 22 Z"/>`
        : big
          ? `<path class="k-wing" d="M31 70 q-18 -2 -24 -18 q14 -2 28 6 Z"/><path class="k-wing" d="M89 70 q18 -2 24 -18 q-14 -2 -28 6 Z"/>`
          : `<ellipse class="k-wing" cx="33" cy="74" rx="7" ry="12" transform="rotate(16 33 74)"/><ellipse class="k-wing" cx="87" cy="74" rx="7" ry="12" transform="rotate(-16 87 74)"/>`;
      const feet = `<path class="k-stroke-ink" d="M51 ${cy + r - 1} v7 m-4 0 h8 M69 ${cy + r - 1} v7 m-4 0 h8" fill="none" stroke-width="2.6" stroke-linecap="round"/>`;
      s += `${tail}<g class="k-bob">${wing}${crest(top ? 3 : big ? 2 : 1, 58, cy - r + 3)}
        <circle class="k-body" cx="60" cy="${cy}" r="${r}"/>
        <ellipse class="k-belly" cx="60" cy="${cy + r * 0.5}" rx="${r * 0.58}" ry="${r * 0.38}"/>
        ${top ? star(60, cy + r * 0.52, 6, "k-body") : ""}
        ${eye(49, cy - 7, mood)}${eye(71, cy - 7, mood)}${beak(60, cy - 1, mood)}${cheeks(40, 80, cy + 3, mood)}</g>${feet}`;
    }
    return wrap(s, mood, stage);
  }

  /* ── 2안 · 소리: 물방울에서 노래하는 고래가 된다. 물줄기는 소리 파형 ── */
  function spout(n, x, y) {
    // 머리 위 물줄기 = 앱의 음성 파형 막대. n 개.
    const hs = { 3: [9, 15, 10], 5: [8, 14, 20, 13, 9], 7: [7, 12, 18, 25, 17, 13, 8] }[n];
    const w = 4.6, gap = 3.2, total = n * w + (n - 1) * gap;
    return hs
      .map((h, i) => {
        const bx = x - total / 2 + i * (w + gap);
        return `<rect class="${i % 2 ? "k-soft" : "k-light"} k-wave" style="animation-delay:${i * 90}ms" x="${bx.toFixed(1)}" y="${y - h}" width="${w}" height="${h}" rx="2.3"/>`;
      })
      .join("");
  }

  function fluke(x, y, k) {
    // 꼬리지느러미(오른쪽 위로 들린 두 잎)
    return `<g transform="translate(${x} ${y}) scale(${k})"><path class="k-body" d="M-4 6 Q6 -2 8 -14 Q16 -10 20 -22 Q26 -8 18 2 Q12 10 0 12 Z"/><path class="k-soft" d="M8 -14 Q-2 -18 -6 -28 Q8 -30 14 -18 Z"/></g>`;
  }

  function whale(stage, mood) {
    let s = "";
    const smile = (x, y) =>
      mood === "sleepy"
        ? `<path class="k-stroke-ink" d="M${x - 4} ${y} h8" fill="none" stroke-width="2.4" stroke-linecap="round"/>`
        : mood === "wait"
          ? `<path class="k-stroke-ink" d="M${x - 5} ${y - 1} q5 4.5 10 0" fill="none" stroke-width="2.4" stroke-linecap="round"/>`
          : `<path class="k-ink" d="M${x - 6} ${y - 2} h12 q-1 8 -6 8 q-5 0 -6 -8 Z"/>`;
    if (stage === 1) {
      // 물방울 하나
      s += `<g class="k-bob"><path class="k-body" d="M60 22 C60 22 33 56 33 76 A27 27 0 0 0 87 76 C87 56 60 22 60 22 Z"/>
        <path class="k-stroke-light" d="M44 78 q1 12 11 16" fill="none" stroke-width="3" stroke-linecap="round"/>
        ${eye(51, 72, mood, 4)}${eye(69, 72, mood, 4)}${smile(60, 82)}${cheeks(43, 77, 80, mood)}</g>`;
    } else if (stage === 2) {
      // 물방울이 누워 꼬리가 돋았다
      s += `${fluke(86, 70, 0.62)}<g class="k-bob"><ellipse class="k-body" cx="54" cy="74" rx="32" ry="25"/>
        <path class="k-stroke-light" d="M30 76 q2 12 12 17" fill="none" stroke-width="3" stroke-linecap="round"/>
        ${eye(42, 70, mood, 4)}${eye(60, 70, mood, 4)}${smile(51, 80)}${cheeks(34, 68, 78, mood)}</g>`;
    } else {
      const k = stage === 3 ? 0.86 : stage === 4 ? 0.93 : 1;
      const n = stage === 4 ? 3 : stage === 5 ? 5 : stage === 6 ? 7 : 0;
      const body = `<g transform="translate(60 78) scale(${k}) translate(-60 -78)">
        ${fluke(96, 70, 1)}
        <g class="k-bob">
        <path class="k-body" d="M14 76 C14 54 34 44 56 44 C82 44 100 58 102 76 C102 94 84 104 58 104 C32 104 14 96 14 76 Z"/>
        <path class="k-belly" d="M16 82 C24 98 40 104 58 104 C80 104 96 98 101 84 C86 92 70 94 54 94 C38 94 24 90 16 82 Z"/>
        ${stage >= 4 ? `<path class="k-wing" d="M58 92 q4 14 16 16 q0 -12 -6 -18 Z"/>` : ""}
        ${stage === 6 ? `<path class="k-stroke-body" d="M30 96 q4 3 8 4 M44 100 q4 2 9 2 M60 102 q5 0 9 -1" fill="none" stroke-width="2" stroke-linecap="round"/>` : ""}
        ${eye(34, 70, mood)}${eye(56, 70, mood)}${smile(45, 80)}${cheeks(26, 64, 79, mood)}
        </g></g>`;
      s += (n ? spout(n, 60 - (1 - k) * 8, 78 - 36 * k) : "") + body + (stage === 6 ? star(60, 78 - 36 - 30, 6, "k-body") : "");
    }
    return wrap(s, mood, stage);
  }

  function wrap(inner, mood, stage) {
    return `<svg class="kchar" data-mood="${mood}" data-stage="${stage}" viewBox="0 0 120 120" aria-hidden="true">${ground}${inner}${extras(mood, stage >= 4)}</svg>`;
  }

  window.Char = {
    STAGE_DAYS, stageOf, moodOf,
    draw(kind, stage, mood) { return kind === "whale" ? whale(stage, mood) : bird(stage, mood); },
    names: { bird: "포롱", whale: "소리" },
    moods: { done: "오늘 달성", wait: "오늘 아직", sleepy: "스트릭이 끊겼을 때", party: "66일 완주" },
  };
})();
