import type { Session } from "../api/types";
import { isPlayMode, type PlayMode } from "../audio/turn";

/** 갤럭시 루틴·매니페스트 단축 아이콘용 자동 실행 요청(`/?autoplay=true&mode=shadowing`). */
export interface Launch {
  mode: PlayMode;
}

/** URL 에서 자동 실행 요청을 읽고 주소창에서는 지운다(새로고침해도 다시 실행되지 않게). 앱 실행 동안 메모리에 둔다. */
export function readLaunch(): Launch | null {
  const url = new URL(window.location.href);
  const autoplay = url.searchParams.get("autoplay");
  const mode = url.searchParams.get("mode");
  if (autoplay === null && mode === null) return null;
  url.searchParams.delete("autoplay");
  url.searchParams.delete("mode");
  window.history.replaceState(null, "", url.pathname + (url.search || "") + url.hash);
  if (autoplay !== "true" && autoplay !== "1") return null;
  return { mode: isPlayMode(mode) ? mode : "full" };
}

/** 세션 탭은 URL 에 둔다(clean-blue-ui: 상태는 URL). */
export function readSessionParam(): Session {
  return new URLSearchParams(window.location.search).get("session") === "review" ? "REVIEW" : "TODAY";
}

export function writeSessionParam(session: Session): void {
  const url = new URL(window.location.href);
  if (session === "REVIEW") url.searchParams.set("session", "review");
  else url.searchParams.delete("session");
  window.history.replaceState(null, "", url.pathname + url.search + url.hash);
}

/** 학습 큐와 무관한 별도 화면(단어장, 말하기 연습). URL 에 둔다(?view=vocab|speak). 재생 큐의 세션 탭(?session)과는 별개다. */
export type SideView = "vocab" | "speak";

export function sideViewFromUrl(): SideView | null {
  const v = new URLSearchParams(window.location.search).get("view");
  return v === "vocab" || v === "speak" ? v : null;
}

export function rememberSideView(view: SideView | null): void {
  const url = new URL(window.location.href);
  if (view) url.searchParams.set("view", view);
  else url.searchParams.delete("view");
  window.history.replaceState(null, "", url.pathname + url.search + url.hash);
}

/** @deprecated sideViewFromUrl 을 쓴다. */
export function vocabViewFromUrl(): boolean {
  return sideViewFromUrl() === "vocab";
}

export function rememberVocabView(open: boolean): void {
  const url = new URL(window.location.href);
  if (open) url.searchParams.set("view", "vocab");
  else url.searchParams.delete("view");
  window.history.replaceState(null, "", url.pathname + url.search + url.hash);
}

export function routineUrls(origin = window.location.origin): { label: string; url: string }[] {
  return [
    { label: "끊어 읽기", url: `${origin}/?autoplay=true&mode=shadowing` },
    { label: "역할극", url: `${origin}/?autoplay=true&mode=roleplay` },
    { label: "연속 듣기", url: `${origin}/?autoplay=true&mode=full` },
  ];
}
