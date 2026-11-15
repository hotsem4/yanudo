/* 66 Habit 서비스 워커. 직접 작성한다(SPEC §6).
 * - 네비게이션: 네트워크 우선, 실패하면 캐시된 앱 셸
 * - /assets/*, 폰트: 캐시 우선
 * - /api/v1/audio/*: 캐시 우선. 페이지 프리로더와 같은 캐시(audio-v1)를 쓴다
 * - 그 밖의 /api/*: 네트워크만
 * - push / notificationclick
 */
const SHELL_CACHE = "shell-v1";
const STATIC_CACHE = "static-v1";
const AUDIO_CACHE = "audio-v1";
const KEEP = [SHELL_CACHE, STATIC_CACHE, AUDIO_CACHE];
const SHELL_URLS = ["/", "/index.html", "/manifest.json", "/icons/icon.svg", "/icons/icon-192.png"];

// 앱 셸 + 그 index.html 이 참조하는 해시 JS/CSS 를 함께 받아 둔다.
// SW 는 첫 로드가 끝난 뒤 등록되므로, 이렇게 하지 않으면 첫 설치 직후 오프라인 부팅 때 JS/CSS 가 없다.
async function precacheShell() {
  const shell = await caches.open(SHELL_CACHE);
  const statics = await caches.open(STATIC_CACHE);
  const response = await fetch("/index.html", { cache: "no-store" });
  if (!response.ok) throw new Error("index.html " + response.status);
  const html = await response.clone().text();
  const assets = Array.from(new Set(html.match(/\/assets\/[^"'\s)]+/g) || []));
  await shell.put("/index.html", response.clone());
  await shell.put("/", response);
  await shell.addAll(SHELL_URLS.filter((u) => u !== "/" && u !== "/index.html"));
  await statics.addAll(assets);
}

self.addEventListener("install", (event) => {
  // 필수 캐시에 실패하면 설치도 실패시켜, 다음 방문 때 다시 시도하게 한다(반쯤 캐시된 상태로 활성화하지 않는다).
  event.waitUntil(precacheShell().then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => !KEEP.includes(k)).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

async function networkFirstShell(request) {
  try {
    const response = await fetch(request);
    if (response.ok) {
      const cache = await caches.open(SHELL_CACHE);
      cache.put("/index.html", response.clone());
    }
    return response;
  } catch {
    const cached = (await caches.match("/index.html")) || (await caches.match("/"));
    return cached || new Response("오프라인입니다. 연결되면 다시 열어 주세요.", {
      status: 503,
      headers: { "Content-Type": "text/plain; charset=utf-8" },
    });
  }
}

async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  // 오디오 요소의 Range 요청도 전체 응답으로 답한다(Chrome 은 200 전체 응답을 받아들인다).
  const cached = await cache.match(request.url);
  if (cached) return cached;
  const response = await fetch(request.url, { credentials: "same-origin" });
  if (response.ok || response.type === "opaque") {
    cache.put(request.url, response.clone());
  }
  return response;
}

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;
  const url = new URL(request.url);

  if (url.origin === self.location.origin) {
    if (url.pathname.startsWith("/api/v1/audio/")) {
      event.respondWith(cacheFirst(request, AUDIO_CACHE));
      return;
    }
    if (url.pathname.startsWith("/api/")) return; // 네트워크만
    if (request.mode === "navigate") {
      event.respondWith(networkFirstShell(request));
      return;
    }
    if (url.pathname.startsWith("/assets/") || url.pathname.startsWith("/icons/")) {
      event.respondWith(cacheFirst(request, STATIC_CACHE));
    }
    return;
  }

  if (url.hostname === "fonts.googleapis.com" || url.hostname === "fonts.gstatic.com" || url.hostname === "cdn.jsdelivr.net") {
    event.respondWith(cacheFirst(request, STATIC_CACHE));
  }
});

self.addEventListener("push", (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    data = { body: event.data ? event.data.text() : "" };
  }
  const title = data.title || "66 Habit";
  event.waitUntil(
    self.registration.showNotification(title, {
      body: data.body || "오늘의 10문장이 기다리고 있어요.",
      icon: "/icons/icon-192.png",
      badge: "/icons/icon-192.png",
      tag: data.tag || "habit66",
      renotify: true,
      data: { url: data.url || "/" },
    }),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const target = new URL(event.notification.data?.url || "/", self.location.origin).href;
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((clients) => {
      for (const client of clients) {
        if (new URL(client.url).origin === self.location.origin) {
          return client.focus().then((c) => (c && "navigate" in c ? c.navigate(target) : c));
        }
      }
      return self.clients.openWindow(target);
    }),
  );
});
