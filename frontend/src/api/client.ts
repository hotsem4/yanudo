import type { ApiErrorBody, Dashboard, EventsResponse, Heatmap, SessionData, StudyEvent, VocabList } from "./types";

/** 서버가 오류 본문을 돌려준 경우. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/** 네트워크에 닿지 못한 경우(지하철, 서버 꺼짐, Tailscale 끊김). */
export class NetworkError extends Error {
  constructor(message = "서버에 연결하지 못했어요.") {
    super(message);
    this.name = "NetworkError";
  }
}

// ── 서버 시계 보정 ──────────────────────────────────────────────
// 이벤트의 occurred_at 은 서버 시계 추정값으로 찍는다(docs/architecture.md "시각 보정").
// 다시 켠 뒤 서버에 닿기 전(오프라인)에도 같은 보정을 쓰도록 마지막 값을 기기에 남긴다.
const OFFSET_KEY = "habit66.clockOffset.v1";
let offsetMs = readOffset();

function readOffset(): number {
  try {
    const v = Number(window.localStorage.getItem(OFFSET_KEY));
    return Number.isFinite(v) ? v : 0;
  } catch {
    return 0;
  }
}

/** 서버 시계 추정값(정수 epoch ms). 이벤트 id(`focus:<started_at>`)와 서버의 정수 필드를 맞추려고 반올림한다. */
export function serverNow(): number {
  return Math.round(Date.now() + offsetMs);
}

function observeServerTime(serverTime: number, sentAt: number, receivedAt: number): void {
  const midpoint = sentAt + (receivedAt - sentAt) / 2;
  offsetMs = Math.round(serverTime - midpoint);
  try {
    window.localStorage.setItem(OFFSET_KEY, String(offsetMs));
  } catch {
    /* 저장할 수 없어도 이번 실행에서는 쓴다 */
  }
}

const TIMEOUT_MS = 15_000;

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const controller = new AbortController();
  // 본문을 다 읽을 때까지 건다. 헤더만 받고 본문에서 멈추면 동기화 전체가 멈춘다.
  const timer = window.setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    let response: Response;
    try {
      response = await fetch(path, {
        ...init,
        signal: controller.signal,
        headers: { Accept: "application/json", ...(init.body ? { "Content-Type": "application/json" } : {}), ...init.headers },
      });
    } catch {
      throw new NetworkError();
    }
    if (!response.ok) {
      let body: ApiErrorBody | null = null;
      try {
        body = (await response.json()) as ApiErrorBody;
      } catch {
        if (controller.signal.aborted) throw new NetworkError();
        body = null;
      }
      throw new ApiError(response.status, body?.error ?? "HTTP_" + response.status, body?.message ?? "요청을 처리하지 못했어요.");
    }
    if (response.status === 204) return undefined as T;
    try {
      return (await response.json()) as T;
    } catch (e) {
      if (controller.signal.aborted) throw new NetworkError();
      throw e;
    }
  } finally {
    window.clearTimeout(timer);
  }
}

async function withClock<T extends { server_time: number }>(load: () => Promise<T>): Promise<T> {
  const sentAt = Date.now();
  const data = await load();
  observeServerTime(data.server_time, sentAt, Date.now());
  return data;
}

export const api = {
  dashboard: () => withClock(() => request<Dashboard>("/api/v1/dashboard")),
  session: (which: "today" | "review") => request<SessionData>(`/api/v1/sessions/${which}`),
  heatmap: () => request<Heatmap>("/api/v1/habit/heatmap"),
  vocab: () => request<VocabList>("/api/v1/vocab"),
  async events(events: StudyEvent[]): Promise<EventsResponse> {
    const sentAt = Date.now();
    const res = await request<EventsResponse>("/api/v1/events", { method: "POST", body: JSON.stringify({ events }) });
    observeServerTime(res.dashboard.server_time, sentAt, Date.now());
    return res;
  },
  vapidPublicKey: () => request<{ public_key: string }>("/api/v1/push/vapid-public-key"),
  subscribePush: (subscription: PushSubscriptionJSON) =>
    request<unknown>("/api/v1/push/subscribe", { method: "POST", body: JSON.stringify(subscription) }),
  unsubscribePush: (endpoint: string) =>
    request<unknown>("/api/v1/push/unsubscribe", { method: "POST", body: JSON.stringify({ endpoint }) }),
  testPush: () => request<{ push_sent: number; webhooks_sent: number }>("/api/v1/push/test", { method: "POST" }),
};

/** 화면을 떠날 때 응답을 기다리지 않고 보낸다. 멱등이라 다음 정상 전송이 같은 이벤트를 다시 보내도 안전하다. */
export function sendEventsKeepalive(events: StudyEvent[]): void {
  if (events.length === 0) return;
  try {
    void fetch("/api/v1/events", {
      method: "POST",
      keepalive: true,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ events: events.slice(0, 200) }),
    }).catch(() => undefined);
  } catch {
    // keepalive 본문 한도(64KB)를 넘거나 지원하지 않으면 다음 정상 전송에 맡긴다.
  }
}
