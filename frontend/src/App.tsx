import { MotionConfig } from "framer-motion";
import { Lock } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { serverNow } from "./api/client";
import type { Sentence, Session } from "./api/types";
import { audioEngine, useEngine } from "./audio/useEngine";
import { BentoHeader } from "./components/BentoHeader";
import { Controls } from "./components/Controls";
import { HeatmapSheet } from "./components/HeatmapSheet";
import { MorningLockOverlay } from "./components/MorningLockOverlay";
import { BlockedPrompt, PlayerDock } from "./components/PlayerDock";
import { Segmented } from "./components/Segmented";
import { SentenceMap } from "./components/SentenceMap";
import { SettingsSheet } from "./components/SettingsSheet";
import { StudyStage } from "./components/StudyStage";
import { VocabList } from "./components/VocabList";
import { VocabPickSheet } from "./components/VocabPickSheet";
import { VocabStage } from "./components/VocabStage";
import { SpeakDayList, SpeakHome } from "./components/SpeakDayList";
import { SpeakPractice } from "./components/SpeakPractice";
import { api } from "./api/client";
import type { SpeakOverview } from "./api/types";
import { Toaster } from "./components/Toaster";
import { TodayPlan } from "./components/TodayPlan";
import { TodoSheet } from "./components/TodoSheet";
import { summarize, todayTodos, type Todo } from "./lib/todo";
import { useFocusTimer } from "./hooks/useFocusTimer";
import { useWakeLock } from "./hooks/useWakeLock";
import { rememberSideView, sideViewFromUrl, type SideView } from "./lib/launch";
import { MASCOT_NAME, mascotOf, noteStage, stageIs } from "./lib/mascot";
import { prefs, type RevealFilter } from "./lib/prefs";
import { showToast } from "./lib/toast";
import { filterVocab, type VocabFilter } from "./lib/vocab";
import { playback, usePlaybackTab } from "./playback/controller";
import { shouldShowMorningLock, type DayView } from "./state/overlay";
import { useStudy } from "./state/useStudy";

type Sheet = "heatmap" | "settings" | "words" | "todo" | null;
type MainTab = Session | "VOCAB" | "SPEAK";

export function App() {
  const study = useStudy();
  const eng = useEngine();
  const tab = usePlaybackTab();
  const timer = useFocusTimer();
  const [filter, setFilter] = useState<RevealFilter>(() => prefs.get("filter"));
  const [sheet, setSheet] = useState<Sheet>(null);
  const [morningOpen, setMorningOpen] = useState(false);
  const view = study.kind === "ready" ? study.view : null;
  const closeMorning = useCallback(() => setMorningOpen(false), []);

  useWakeLock(eng.playing || timer.running);
  useMorningLock(view, setMorningOpen);
  useCelebrations(view);
  const todos = view ? todayTodos(view, serverNow()) : null;
  useTodoCelebrations(todos);
  useMascotGrowth(view);
  const mascot = view ? mascotOf(view) : null;

  // 단어장은 재생 큐와 무관한 별도 화면이라 선택 상태를 여기서 따로 든다(playback 의 탭은 TODAY/REVIEW 그대로).
  // 학습 큐와 무관한 별도 화면(단어장, 말하기 연습). null 이면 학습 화면.
  const [side, setSide] = useState<SideView | null>(sideViewFromUrl);
  const vocabOpen = side === "vocab";
  const speakOpen = side === "speak";
  const [vocabFilter, setVocabFilter] = useState<VocabFilter>("learning");
  const [vocabKey, setVocabKey] = useState<string | null>(null);
  const [wordsFor, setWordsFor] = useState<Sentence | null>(null);
  const vocabAll = view?.vocab ?? null;
  const deck = useMemo(() => filterVocab(vocabAll ?? [], vocabFilter), [vocabAll, vocabFilter]);
  // 보던 단어가 목록에서 빠지면(빼기, 외움 처리) 같은 자리의 다음 단어를 보여 준다.
  const lastIndex = useRef(0);
  const found = vocabKey ? deck.findIndex((v) => v.term_key === vocabKey) : -1;
  const vocabIndex = found >= 0 ? found : Math.min(lastIndex.current, Math.max(0, deck.length - 1));
  lastIndex.current = vocabIndex;

  const showSide = (next: SideView | null) => {
    setSide(next);
    rememberSideView(next);
  };
  const showVocab = (open: boolean) => showSide(open ? "vocab" : null);

  // 말하기 연습(부가 기능): Day 목록과 고른 Day.
  const [speakDay, setSpeakDay] = useState<number | null>(null);
  const [speakOverview, setSpeakOverview] = useState<SpeakOverview | null>(null);
  const [speakFailed, setSpeakFailed] = useState(false);
  const loadSpeak = useCallback(() => {
    setSpeakFailed(false);
    api.speak().then(setSpeakOverview, () => setSpeakFailed(true));
  }, []);
  useEffect(() => {
    if (speakOpen) loadSpeak();
  }, [speakOpen, loadSpeak]);
  // 이 화면에서 끝낸 문장. 기록을 보내기 전에 받은 목록에도 완료로 보이게 서버 값·대기 이벤트와 합친다.
  const [speakDone, setSpeakDone] = useState<number[]>([]);
  const speakMarks = useMemo(() => [...(view?.speakPendingIds ?? []), ...speakDone], [view?.speakPendingIds, speakDone]);
  // 알림을 눌렀을 때 서비스 워커가 열린 창을 새로 고치지 않고 보내는 목적지(sw.js notificationclick).
  useEffect(() => {
    if (!("serviceWorker" in navigator)) return;
    const onMessage = (e: MessageEvent) => {
      const data = e.data as { type?: unknown; url?: unknown } | null;
      if (data?.type !== "open-url" || typeof data.url !== "string") return;
      const target = new URL(data.url, window.location.origin);
      if (target.searchParams.get("view") === "vocab") {
        setSide("vocab");
        rememberSideView("vocab");
      } else if (target.searchParams.has("autoplay")) {
        // 자동 재생 요청은 앱을 여는 흐름(readLaunch)을 타야 한다.
        window.location.assign(target.href);
      }
    };
    navigator.serviceWorker.addEventListener("message", onMessage);
    return () => navigator.serviceWorker.removeEventListener("message", onMessage);
  }, []);
  // 단어장에서는 독을 숨긴다(독은 학습 큐를 움직이므로 단어 카드와 헷갈린다). 학습 오디오가 나오는 중이면 멈출 수 있게 남긴다.
  const dockHidden = side !== null && !eng.playing && eng.phase !== "blocked";
  const selectMainTab = (t: MainTab) => {
    if (t === "VOCAB") return showSide("vocab");
    if (t === "SPEAK") return showSide("speak");
    showSide(null);
    playback.selectTab(t);
  };

  const changeFilter = (f: RevealFilter) => {
    setFilter(f);
    prefs.set("filter", f);
  };

  // 오늘 할 일의 단계 버튼: 그 탭으로 가서 학습 방식을 맞추고 바로 재생한다. 이미 그 탭을 재생 중이면 멈춘다.
  // 복습은 "한글만 + 끊어읽기"(해석을 보며 듣고 따라 말하기), 오늘 10문장은 처음 배우므로 "전체 + 연속 듣기".
  const startStep = (session: Session) => {
    const now = audioEngine.getSnapshot();
    if (!vocabOpen && now.playing && now.session === session) {
      audioEngine.pause();
      return;
    }
    showVocab(false);
    playback.selectTab(session);
    changeFilter(session === "REVIEW" ? "korean" : "all");
    const mode = session === "REVIEW" ? "shadowing" : "full";
    audioEngine.setMode(mode);
    prefs.set("mode", mode);
    void audioEngine.play();
  };

  // 오늘 할 일의 보너스 항목 시작.
  const startFocus = () => {
    if (!timer.running) timer.start();
  };
  const addWord = () => {
    // 지금 보고 있는(재생 중인) 문장의 단어 담기 시트를 연다. 오늘 탭이 잠겨 있으면 복습 문장에서.
    const session = tab === "TODAY" && !locked ? view?.today : view?.review;
    const sentence = session?.sentences[eng.session === session?.session ? eng.cursor : 0] ?? session?.sentences[0];
    if (!sentence) return;
    showVocab(false);
    setWordsFor(sentence);
    setSheet("words");
  };
  const todoActions = { onStart: startStep, onStartFocus: startFocus, onAddWord: addWord, onOpenMorning: () => setMorningOpen(true) };

  const locked = !!view && !view.gate.open;
  const tabs: { value: MainTab; label: string; icon?: JSX.Element }[] = [
    { value: "TODAY", label: "오늘의 10문장", icon: locked ? <Lock size={13} strokeWidth={2} aria-hidden /> : undefined },
    { value: "REVIEW", label: "누적 복습" },
    { value: "VOCAB", label: "단어장" },
    { value: "SPEAK", label: "말하기" },
  ];

  return (
    <MotionConfig reducedMotion="user">
      <main
        className={`app-grid mx-auto min-h-dvh max-w-md bg-canvas px-5 pt-safe unfolded:max-w-[960px] unfolded:px-8 unfolded:pb-6 ${side ? "app-grid--vocab" : ""} ${
          dockHidden ? "app-grid--nodock pb-6" : "pb-dock"
        }`}
      >
        <h1 className="sr-only" translate="no">
          66 Habit English
        </h1>
        <div className="area-head">
          <BentoHeader view={view} timer={timer} onOpenHeatmap={() => setSheet("heatmap")} />
        </div>
        <div className="area-tabs flex flex-col gap-3">
          {/* 단어장·말하기 연습은 학습과 별개인 도구라 오늘 할 일 줄을 숨기고 그 자리를 카드에 준다. */}
          {view && todos && !side && (
            <TodayPlan view={view} todos={todos} focusRunning={timer.running} onOpenList={() => setSheet("todo")} {...todoActions} />
          )}
          <Segmented variant="tab" label="학습 세션" options={tabs} value={vocabOpen ? "VOCAB" : speakOpen ? "SPEAK" : tab} onChange={selectMainTab} />
        </div>
        <div className="area-map">
          {vocabOpen ? (
            <VocabList all={vocabAll} deck={deck} index={vocabIndex} filter={vocabFilter} onSelect={setVocabKey} />
          ) : speakOpen ? (
            <div className="thin-scroll h-full overflow-y-auto">
              <SpeakDayList
                overview={speakOverview}
                pendingIds={speakMarks}
                selected={speakDay}
                failed={speakFailed}
                onSelect={setSpeakDay}
                onRetry={loadSpeak}
              />
            </div>
          ) : (
            <SentenceMap view={view} tab={tab} />
          )}
        </div>
        <div className="area-card">
          {speakOpen ? (
            speakDay !== null ? (
              <SpeakPractice
                key={speakDay}
                day={speakDay}
                pendingIds={speakMarks}
                mascot={mascot}
                onBack={() => setSpeakDay(null)}
                onDone={(id) => setSpeakDone((ids) => [...ids, id])}
              />
            ) : (
              <SpeakHome
                overview={speakOverview}
                pendingIds={speakMarks}
                failed={speakFailed}
                onSelect={setSpeakDay}
                onRetry={loadSpeak}
              />
            )
          ) : vocabOpen ? (
            <VocabStage
              study={study}
              all={vocabAll}
              deck={deck}
              index={vocabIndex}
              filter={vocabFilter}
              onFilter={setVocabFilter}
              onSelect={setVocabKey}
              onGoStudy={() => showVocab(false)}
              mascot={mascot}
            />
          ) : (
            <StudyStage
              study={study}
              tab={tab}
              filter={filter}
              covered={sheet !== null || morningOpen}
              onGoReview={() => playback.selectTab("REVIEW")}
              onPickWords={(s) => {
                setWordsFor(s);
                setSheet("words");
              }}
            />
          )}
        </div>
        {/* 단어장에는 가리기·재생 모드가 쓰이지 않아 컨트롤을 그리지 않는다. 설정은 학습 화면에서 연다. */}
        {!side && <div className="area-ctrl">
          <Controls
            filter={filter}
            onFilter={changeFilter}
            mode={eng.mode}
            onMode={(m) => {
              audioEngine.setMode(m);
              prefs.set("mode", m);
            }}
            rate={eng.rate}
            onCycleRate={() => {
              audioEngine.cycleRate();
              prefs.set("rate", audioEngine.getSnapshot().rate);
            }}
            onOpenSettings={() => setSheet("settings")}
          />
        </div>}
      </main>

      {!dockHidden && <PlayerDock />}
      <BlockedPrompt />
      <HeatmapSheet open={sheet === "heatmap"} onClose={() => setSheet(null)} achievedTodayLocally={!!view?.streak.achievedToday} mascot={mascot} />
      <VocabPickSheet
        open={sheet === "words"}
        onClose={() => setSheet(null)}
        sentence={wordsFor}
        vocab={vocabAll}
        onOpenVocab={() => {
          setSheet(null);
          setVocabFilter("all");
          // 방금 이 문장에서 담은 단어(최근 먼저)를 바로 보여 준다. 없으면 가장 최근에 담은 단어.
          const recent = vocabAll?.find((v) => v.sentence?.id === wordsFor?.id) ?? vocabAll?.[0];
          setVocabKey(recent?.term_key ?? null);
          showVocab(true);
        }}
      />
      <SettingsSheet open={sheet === "settings"} onClose={() => setSheet(null)} study={study} />
      <TodoSheet
        open={sheet === "todo" && !!todos}
        onClose={() => setSheet(null)}
        todos={todos ?? []}
        streak={view?.streak.current ?? 0}
        mascot={mascot}
        {...todoActions}
      />
      <MorningLockOverlay open={morningOpen} sentences={view?.morningLock.sentences ?? []} onDone={closeMorning} mascot={mascot} />
      <Toaster mascot={mascot} />
    </MotionConfig>
  );
}

/** 앱을 열 때(첫 상태)와 화면 복귀 때만 모닝 락을 판단한다(SPEC F-04-2). */
function useMorningLock(view: DayView | null, open: (v: boolean) => void): void {
  // 판단을 기다리는 중인가. 앱을 열 때와 화면에 돌아올 때 켜고, 오늘 것(stale 이 아닌 뷰)으로 한 번 판단하면 끈다.
  // 아침에 열거나 밤새 백그라운드에 있던 앱으로 돌아오면 먼저 전날 캐시(논리 날짜가 지나 stale)로 그리므로,
  // 그때 판단하면 락이 뜨지 않는다. 새 대시보드를 받은 뒤에 판단한다.
  const [asking, setAsking] = useState(true);
  // 자동 실행으로 연 날(서버가 준 논리 날짜). 그날 안의 화면 전환에는 면제를 유지하고, 다음 날 돌아오면 다시 판단한다.
  const autoplayDay = useRef<string | null | undefined>(undefined);

  useEffect(() => {
    if (!asking || !view || view.stale) return;
    setAsking(false);
    if (autoplayDay.current === undefined) autoplayDay.current = playback.launchedByAutoplay() ? view.dashboard.today : null;
    const exempt = autoplayDay.current === view.dashboard.today;
    if (shouldShowMorningLock(view, serverNow(), exempt)) open(true);
  }, [asking, view, open]);

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === "visible") setAsking(true);
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, []);
}

/**
 * 오늘 할 일의 보너스 항목이 끝나는 순간과 모두 끝낸 순간을 알린다(첫 상태·날짜가 바뀐 직후는 알리지 않는다).
 * 복습·오늘 10문장·모닝 스타터는 이미 각자 알림이 있다(게이트 열림, 습관 달성, 모닝 스타터 완료).
 */
function useTodoCelebrations(todos: Todo[] | null): void {
  const prev = useRef<Map<string, string> | null>(null);
  useEffect(() => {
    if (!todos) return;
    const now = new Map(todos.map((t) => [t.id, t.state]));
    const before = prev.current;
    prev.current = now;
    if (!before) return;
    // 날짜가 바뀌어 목록이 새로 시작되면(끝났던 것이 다시 열림) 알리지 않는다.
    if (todos.some((t) => before.get(t.id) === "done" && t.state !== "done")) return;
    const newlyDone = todos.filter((t) => t.state === "done" && before.get(t.id) !== "done");
    for (const t of newlyDone) {
      if (t.id === "focus") showToast("✓ 25분 집중 끝! 보너스 하나 완료", "success", 3000);
      if (t.id === "word") showToast("✓ 새 단어 담기 끝! 보너스 하나 완료", "success", 3000);
    }
    const wasAllDone = [...before.entries()].every(([, s]) => s !== "open");
    if (newlyDone.length > 0 && !wasAllDone && summarize(todos).allDone) {
      showToast("오늘 할 일 모두 끝! 정말 잘했어요", "success", 3600);
    }
  }, [todos]);
}

/** 포롱이 새 단계로 자란 날 한 번만 알린다. 이미 본 단계는 기기에 기억해 되풀이하지 않는다. */
function useMascotGrowth(view: DayView | null): void {
  const stage = view ? mascotOf(view).stage : null;
  useEffect(() => {
    if (stage === null) return;
    if (noteStage(stage)) showToast(`${MASCOT_NAME}이 자랐어요. 이제 ${stageIs(stage)}`, "success", 3600);
  }, [stage]);
}

/** 게이트가 열리는 순간과 오늘 달성 순간을 알린다(첫 로드 상태는 알리지 않는다). */
function useCelebrations(view: DayView | null): void {
  const prev = useRef<{ gate: boolean; achieved: boolean } | null>(null);
  useEffect(() => {
    if (!view) return;
    const now = { gate: view.gate.open, achieved: view.streak.achievedToday };
    const before = prev.current;
    prev.current = now;
    if (!before) return;
    if (!before.gate && now.gate && view.gate.required) showToast("오늘의 10문장이 열렸어요", "success");
    if (!before.achieved && now.achieved) showToast(`오늘 습관 달성! 연속 ${view.streak.current}일`, "success", 3200);
  }, [view]);
}
