/* 시안 공통: 아이콘(lucide 0.469.0 경로 일부를 그대로 옮김), 예시 데이터, 조각 템플릿. 시안 전용. */
(function () {
  const P = {
    play: '<polygon points="6 3 20 12 6 21 6 3"/>',
    prev: '<polygon points="19 20 9 12 19 4 19 20"/><line x1="5" x2="5" y1="19" y2="5"/>',
    next: '<polygon points="5 4 15 12 5 20 5 4"/><line x1="19" x2="19" y1="5" y2="19"/>',
    flame: '<path d="M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.500 3 5.500a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.500 2.500 0 0 0 2.500 2.500z"/>',
    snow: '<line x1="2" x2="22" y1="12" y2="12"/><line x1="12" x2="12" y1="2" y2="22"/><path d="m20 16-4-4 4-4"/><path d="m4 8 4 4-4 4"/><path d="m16 4-4 4-4-4"/><path d="m8 20 4-4 4 4"/>',
    redo: '<path d="M3 12a9 9 0 1 0 9-9 9.750 9.750 0 0 0-6.740 2.740L3 8"/><path d="M3 3v5h5"/>',
    check: '<path d="M20 6 9 17l-5-5"/>',
    lock: '<rect width="18" height="11" x="3" y="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
    set: '<path d="M20 7h-9"/><path d="M14 17H5"/><circle cx="17" cy="17" r="3"/><circle cx="7" cy="7" r="3"/>',
    glass: '<path d="M5 22h14"/><path d="M5 2h14"/><path d="M17 22v-4.172a2 2 0 0 0-.586-1.414L12 12l-4.414 4.414A2 2 0 0 0 7 17.828V22"/><path d="M7 2v4.172a2 2 0 0 0 .586 1.414L12 12l4.414-4.414A2 2 0 0 0 17 6.172V2"/>',
    x: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
    eye: '<path d="M2.062 12.348a1 1 0 0 1 0-.696 10.750 10.750 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.750 10.750 0 0 1-19.876 0"/><circle cx="12" cy="12" r="3"/>',
    ear: '<path d="M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3"/>',
  };
  const ic = (n, extra) => `<svg class="ic" viewBox="0 0 24 24" ${extra || ""}>${P[n]}</svg>`;
  const q = new URLSearchParams(location.search);
  const kind = q.get("c") === "whale" ? "whale" : "bird";
  // 예시 상태(시안용 가정): Day 23, 연속 9일, 오늘 아직.
  const D = { current_day: 23, total: 66, streak: 9, freezes: 2, achievedToday: false, program_complete: false };
  const stage = Char.stageOf(D.current_day, D.program_complete), mood = Char.moodOf(D);
  const lines = [
    ["B", "No, I don't think so. I'm Ava.", "아니요, 그렇게 생각하지 않아요. / 저는 에이바예요.", "아니요, 처음 뵙는 것 같아요. 저는 에이바예요."],
    ["A", "Nice to meet you, Ava. I'm Andrew.", "만나서 반가워요, 에이바. / 저는 앤드루예요.", "만나서 반가워요, 에이바. 저는 앤드루예요."],
    ["B", "Nice to meet you too. Where are you from?", "저도 만나서 반가워요. / 어디에서 / 당신은 왔나요?", "저도 반가워요. 어디에서 오셨어요?"],
    ["A", "I'm from Seoul, but I live in Busan now.", "저는 서울 출신이에요, / 하지만 저는 살아요 / 부산에 / 지금은", "서울 출신인데 지금은 부산에 살아요."],
    ["B", "Oh, really? What brings you here today?", "오, 정말요? / 무엇이 데려왔나요 / 당신을 여기에 / 오늘?", "오, 정말요? 오늘은 무슨 일로 오셨어요?"],
    ["A", "I'm here for a job interview.", "저는 여기 왔어요 / 취업 면접을 위해", "취업 면접 보러 왔어요."],
  ];
  const cur = 3;
  const order = (t) => t.split("/").map((c) => `<span>${c.trim()}</span>`).join(" ");
  const spk = (s) => `<span class="spk ${s === "B" ? "b" : ""}"><b>${s}</b>${s === "A" ? "Andrew" : "Ava"}</span>`;
  const rail = (n, now, done) => `<div class="rail">${Array.from({ length: n }, (_, i) => `<i class="${i === now ? "now" : done.includes(i) ? "done" : ""}"></i>`).join("")}</div>`;
  const dock = `<div class="dock"><button class="side" aria-label="이전 문장">${ic("prev")}</button><button class="play" aria-label="재생">${ic("play")}</button><button class="side" aria-label="다음 문장">${ic("next")}</button></div>`;
  window.M = { ic, kind, D, stage, mood, lines, cur, order, spk, rail, dock, char: (s, m) => Char.draw(kind, s || stage, m || mood), sheet: q.get("sheet") === "1" };
})();
