import { useCallback, useEffect, useState } from "react";
import { api } from "../api/client";

export type PushStatus = "unsupported" | "checking" | "off" | "on" | "denied" | "busy";

function base64UrlToBytes(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (value.length % 4)) % 4);
  const raw = atob(padded);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

/** 브라우저 구독이 지금 서버 키(VAPID)로 만든 것인가. data/vapid.json 이 바뀌면 옛 구독으로는 알림이 오지 않는다. */
function sameKey(sub: PushSubscription, serverKey: Uint8Array): boolean {
  const key = sub.options.applicationServerKey;
  if (!key) return true; // 알 수 없으면 그대로 둔다
  const bytes = new Uint8Array(key);
  return bytes.length === serverKey.length && bytes.every((b, i) => b === serverKey[i]);
}

async function registration(): Promise<ServiceWorkerRegistration | null> {
  if (!("serviceWorker" in navigator)) return null;
  return (await navigator.serviceWorker.getRegistration()) ?? null;
}

/** 22시 마감 알림(Web Push VAPID) 구독. HTTPS(Tailscale) + 설치된 PWA 에서 동작한다. */
export function usePush() {
  const supported = typeof window !== "undefined" && "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;
  const [status, setStatus] = useState<PushStatus>(supported ? "checking" : "unsupported");
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!supported) return;
    void (async () => {
      if (Notification.permission === "denied") return setStatus("denied");
      const reg = await registration();
      const sub = reg ? await reg.pushManager.getSubscription() : null;
      if (!sub) return setStatus("off");
      // 브라우저 구독만 있고 서버 등록이 빠졌을 수 있다(지난번 부분 실패). 서버 등록은 업서트라 다시 보내도 안전하다.
      try {
        const { public_key } = await api.vapidPublicKey();
        if (!sameKey(sub, base64UrlToBytes(public_key))) {
          // 서버 키가 바뀌었다: 옛 구독은 지우고 꺼짐으로 보여 다시 켜게 한다.
          await sub.unsubscribe().catch(() => undefined);
          setMessage("알림 설정이 바뀌어 다시 켜야 해요.");
          return setStatus("off");
        }
        await api.subscribePush(sub.toJSON());
        setStatus("on");
      } catch {
        setStatus("on");
        setMessage("서버에 알림 구독을 다시 등록하지 못했어요. 연결되면 설정을 다시 열어 주세요.");
      }
    })();
  }, [supported]);

  const enable = useCallback(async () => {
    setStatus("busy");
    setMessage(null);
    try {
      const permission = await Notification.requestPermission();
      if (permission !== "granted") {
        setStatus("denied");
        return;
      }
      const reg = await registration();
      if (!reg) throw new Error("서비스 워커가 아직 준비되지 않았어요. 설치된 앱에서 다시 시도해 주세요.");
      const { public_key } = await api.vapidPublicKey();
      const serverKey = base64UrlToBytes(public_key);
      let existing = await reg.pushManager.getSubscription();
      if (existing && !sameKey(existing, serverKey)) {
        await existing.unsubscribe().catch(() => undefined);
        existing = null;
      }
      const sub = existing ?? (await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: serverKey }));
      try {
        await api.subscribePush(sub.toJSON());
      } catch (err) {
        // 서버 등록에 실패하면 방금 만든 브라우저 구독을 되돌려 "켜짐"으로 오인하지 않게 한다.
        if (!existing) await sub.unsubscribe().catch(() => undefined);
        throw err;
      }
      setStatus("on");
      setMessage("알림을 켰어요. 22시에 오늘 습관이 남아 있으면 알려 드려요.");
    } catch (err) {
      setStatus("off");
      setMessage(err instanceof Error ? err.message : "알림을 켜지 못했어요.");
    }
  }, []);

  const disable = useCallback(async () => {
    setStatus("busy");
    try {
      const reg = await registration();
      const sub = reg ? await reg.pushManager.getSubscription() : null;
      if (sub) {
        await api.unsubscribePush(sub.endpoint).catch(() => undefined);
        await sub.unsubscribe();
      }
      setStatus("off");
      setMessage("알림을 껐어요.");
    } catch {
      setStatus("on");
      setMessage("알림을 끄지 못했어요.");
    }
  }, []);

  const test = useCallback(async () => {
    try {
      const r = await api.testPush();
      setMessage(`시험 알림을 보냈어요. 푸시 ${r.push_sent}건, 웹훅 ${r.webhooks_sent}건.`);
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "시험 알림을 보내지 못했어요.");
    }
  }, []);

  return { status, message, enable, disable, test };
}
