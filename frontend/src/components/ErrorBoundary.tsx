import { Component, type ErrorInfo, type ReactNode } from "react";
import { snapshotCache } from "../state/outbox";

interface State {
  failed: boolean;
}

/**
 * 화면을 그리다 예외가 나면(깨진 캐시, 예상 못 한 서버 값) 하얀 화면 대신 복구 카드를 보인다.
 * 저장해 둔 화면 캐시만 버리고 다시 연다. 보내지 않은 학습 기록(아웃박스)은 지우지 않는다.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error("render failed", error, info.componentStack);
    snapshotCache.clear();
  }

  render(): ReactNode {
    if (!this.state.failed) return this.props.children;
    return (
      <main className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center gap-3 bg-canvas px-5 text-center">
        <h1 className="text-[17px] font-bold text-ink">화면을 그리지 못했어요</h1>
        <p className="text-pretty text-[14px] leading-relaxed text-muted">저장해 둔 화면을 비웠어요. 학습 기록은 그대로 있어요.</p>
        <button
          type="button"
          onClick={() => window.location.reload()}
          className="press mt-1 h-12 rounded-full bg-royal px-6 text-[14px] font-semibold text-surface shadow-glow"
        >
          다시 열기
        </button>
      </main>
    );
  }
}
