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

/**
 * 브라우저 구독을 서버에 등록한다. 서버가 "만료"라고 답하면(푸시 서비스가 이 구독을 404/410 으로 거절한 적이 있다)
 * 브라우저에 남은 옛 구독을 지우고 새로 구독해 다시 등록한다. 옛 구독을 계속 다시 보내면 서버는 받아 두지만
 * 알림은 영영 오지 않는다(화면만 "켜짐"). 새로 만들었으면 true.
 */
async function registerOrRenew(reg: ServiceWorkerRegistration, sub: PushSubscription, serverKey: Uint8Array): Promise<boolean> {
  const r = await api.subscribePush(sub.toJSON());
  if (!r.expired) return false;
  await sub.unsubscribe().catch(() => undefined);
  const fresh = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: serverKey });
  const again = await api.subscribePush(fresh.toJSON());
  if (again.expired) throw new Error("알림 구독을 새로 만들지 못했어요. 알림을 껐다가 다시 켜 주세요.");
  return true;
}

/**
 * 앱이 열릴 때·화면으로 돌아올 때 조용히 구독을 점검한다(설정 화면을 열지 않아도). 권한이 이미 있고 브라우저 구독이
 * 있을 때만 한다: 서버 키가 바뀌었거나 서버가 "만료"로 들은 구독이면 새로 구독해 등록한다. 실패해도 조용히 넘어간다.
 */
export async function renewPushSubscription(): Promise<void> {
  try {
    if (!("serviceWorker" in navigator) || !("PushManager" in window) || !("Notification" in window)) return;
    if (Notification.permission !== "granted") return;
    const reg = await registration();
    const sub = reg ? await reg.pushManager.getSubscription() : null;
    if (!reg || !sub) return;
    const { public_key } = await api.vapidPublicKey();
    const serverKey = base64UrlToBytes(public_key);
    if (!sameKey(sub, serverKey)) {
      await sub.unsubscribe().catch(() => undefined);
      const fresh = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: serverKey });
      await api.subscribePush(fresh.toJSON());
      return;
    }
    await registerOrRenew(reg, sub, serverKey);
  } catch {
    // 연결이 없거나 서버가 잠깐 못 받는다. 다음에 열 때 다시 한다.
  }
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
        const serverKey = base64UrlToBytes(public_key);
        if (!sameKey(sub, serverKey)) {
          // 서버 키가 바뀌었다: 권한은 그대로이니 옛 구독을 지우고 새 키로 다시 구독한다.
          await sub.unsubscribe().catch(() => undefined);
          if (reg && Notification.permission === "granted") {
            const fresh = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: serverKey });
            await api.subscribePush(fresh.toJSON());
            setMessage("알림 설정이 바뀌어 새로 구독했어요.");
            return setStatus("on");
          }
          setMessage("알림 설정이 바뀌어 다시 켜야 해요.");
          return setStatus("off");
        }
        // 권한이 이미 있으므로 만료된 구독은 여기서 조용히 새로 만든다(reg 는 위에서 받았다).
        if (reg && (await registerOrRenew(reg, sub, serverKey))) setMessage("만료된 알림 구독을 새로 만들었어요.");
        setStatus("on");
      } catch {
        // 새로 구독하다 실패했으면 브라우저 구독이 없을 수 있다. 그런데 "켜짐"으로 보이면 알림이 안 오는 줄 모른다.
        const still = reg ? await reg.pushManager.getSubscription().catch(() => null) : null;
        if (!still) {
          setStatus("off");
          setMessage("알림 구독이 끊겼어요. 다시 켜 주세요.");
          return;
        }
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
        await registerOrRenew(reg, sub, serverKey);
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
      let r = await api.testPush();
      if (r.push_sent === 0) {
        // 보내는 사이 푸시 서비스가 이 기기 구독을 만료로 거절했을 수 있다. 새로 구독해 한 번 더 보낸다.
        const reg = await registration();
        const sub = reg ? await reg.pushManager.getSubscription() : null;
        if (reg && sub && Notification.permission === "granted") {
          const { public_key } = await api.vapidPublicKey();
          if (await registerOrRenew(reg, sub, base64UrlToBytes(public_key))) {
            r = await api.testPush();
            setMessage(`만료된 알림 구독을 새로 만들어 다시 보냈어요. 푸시 ${r.push_sent}건, 웹훅 ${r.webhooks_sent}건.`);
            return;
          }
        }
      }
      setMessage(`시험 알림을 보냈어요. 푸시 ${r.push_sent}건, 웹훅 ${r.webhooks_sent}건.`);
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "시험 알림을 보내지 못했어요.");
    }
  }, []);

  return { status, message, enable, disable, test };
}
