import { useSyncExternalStore } from "react";
import type { Session } from "../api/types";
import { audioEngine } from "../audio/useEngine";
import type { Launch } from "../lib/launch";
import { readSessionParam, writeSessionParam } from "../lib/launch";
import { prefs } from "../lib/prefs";
import { showToast } from "../lib/toast";
import { studyStore } from "../state/store";

/**
 * 엔진과 스토어를 잇는 유일한 접착층(docs/architecture.md).
 *  - 현재 탭의 세션을 엔진 큐로 싣는다(잠긴 TODAY 는 playable=false).
 *  - 엔진 heard → VIEW·AUDIO 기록.
 *  - 자동 실행: 게이트가 닫혀 있으면 복습부터 재생하고, 큐가 끝났을 때 게이트가 열렸으면 오늘 큐로 이어 재생한다.
 */
class PlaybackController {
  private tab: Session = readSessionParam();
  private cursors: Record<Session, number> = { TODAY: 0, REVIEW: 0 };
  private chain = false;
  /** 자동 실행으로 연 이번 실행의 첫 모닝 락 판단만 건너뛴다. 백그라운드에 두었다가 다음 날 돌아오면 다시 판단해야 한다. */
  private lockExempt = false;
  private started = false;
  private listeners = new Set<() => void>();

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getTab = (): Session => this.tab;

  /** 모닝 락 면제를 한 번 쓴다. 자동 실행으로 열린 직후의 첫 판단에서만 true. */
  takeLockExemption(): boolean {
    const exempt = this.lockExempt;
    this.lockExempt = false;
    return exempt;
  }

  start(launch: Launch | null): void {
    if (this.started) return;
    this.started = true;
    this.lockExempt = launch !== null;
    studyStore.subscribe(() => this.sync());
    audioEngine.subscribe(() => {
      const s = audioEngine.getSnapshot();
      if (s.session) this.cursors[s.session] = s.cursor;
    });
    audioEngine.on((e) => {
      if (e.type === "heard") {
        studyStore.record({ type: "VIEW", sentence_id: e.item.id, session: e.session, via: "AUDIO" });
      } else if (e.type === "queueEnd") {
        this.onQueueEnd(e.session);
      } else if (e.type === "speechFailed") {
        showToast("이 문장은 소리를 낼 수 없어 멈췄어요. 연결된 뒤 다시 재생해 주세요.", "info", 3600);
      }
    });
    this.sync();
    if (launch) void this.autoplay(launch);
  }

  selectTab(tab: Session): void {
    if (tab === this.tab) return;
    this.tab = tab;
    this.chain = false;
    writeSessionParam(tab);
    this.sync();
    this.listeners.forEach((l) => l());
  }

  private sync(): void {
    const state = studyStore.getState();
    if (state.kind !== "ready") return;
    const view = state.view;
    const session = this.tab === "TODAY" ? view.today : view.review;
    if (!session || session.sentences.length === 0) {
      // 화면에 문장이 없으면 이전 큐의 재생도 멈춘다.
      audioEngine.load({ key: `${this.tab}:empty`, session: this.tab, items: [], playable: false });
      return;
    }
    const items = session.sentences;
    audioEngine.load(
      {
        key: `${this.tab}:${items.map((i) => i.id).join(",")}`,
        session: this.tab,
        items,
        playable: this.tab === "REVIEW" || view.gate.open,
      },
      this.cursors[this.tab],
    );
  }

  private onQueueEnd(session: Session): void {
    if (!this.chain || session !== "REVIEW") {
      this.chain = false;
      return;
    }
    this.chain = false;
    const state = studyStore.getState();
    if (state.kind === "ready" && state.view.gate.open) {
      this.cursors.TODAY = 0;
      this.selectTab("TODAY");
      void audioEngine.play();
    }
  }

  /**
   * 최신 서버 상태를 최대 2.5초 기다렸다가 시작한다(오프라인이면 캐시로).
   * 첫 상태가 아직 없으면(캐시 없음 + 느린 연결) 준비될 때까지 의도를 보관한다 — 상한으로 버리지 않는다.
   */
  private async autoplay(launch: Launch): Promise<void> {
    prefs.set("mode", launch.mode);
    audioEngine.setMode(launch.mode);
    let readyAt: number | null = null;
    await new Promise<void>((resolve) => {
      const check = () => {
        const s = studyStore.getState();
        if (s.kind === "ready") {
          readyAt ??= Date.now();
          const waited = Date.now() - readyAt;
          if ((!s.sync.syncing && waited > 250) || waited > 2500) return resolve();
        }
        window.setTimeout(check, 120);
      };
      check();
    });
    const s = studyStore.getState();
    if (s.kind !== "ready") return;
    const target: Session = s.view.gate.open ? "TODAY" : "REVIEW";
    this.cursors[target] = 0;
    if (this.tab !== target) this.selectTab(target);
    else this.sync();
    this.chain = target === "REVIEW";
    await audioEngine.play();
  }
}

export const playback = new PlaybackController();

export function usePlaybackTab(): Session {
  return useSyncExternalStore(playback.subscribe, playback.getTab);
}
