import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { ErrorBoundary } from "./components/ErrorBoundary";
import "./index.css";
import { readLaunch } from "./lib/launch";
import { playback } from "./playback/controller";
import { studyStore } from "./state/store";

// 순서: 자동 실행 요청을 URL 에서 먼저 읽고(주소창에서 지움) → 스토어 시작 → 재생 컨트롤러 연결.
const launch = readLaunch();
studyStore.start();
playback.start(launch);

if ("serviceWorker" in navigator && import.meta.env.PROD) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch(() => undefined);
  });
}

const root = document.getElementById("root");
if (!root) throw new Error("#root 가 없습니다");
createRoot(root).render(
  <StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </StrictMode>,
);
