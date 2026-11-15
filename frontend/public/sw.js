/* 66 Habit 서비스 워커. 직접 작성한다(SPEC §6).
 * - 네비게이션: 네트워크 우선(3.5초), 실패·5xx 면 캐시된 앱 셸. 새 HTML 은 참조 자산을 다 받은 뒤에만 캐시한다
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

const NAV_TIMEOUT_MS = 3500;

// 서버만 꺼진 경우(tailscale serve 가 502) 나 PC 가 꺼져 응답이 없는 경우에도 캐시된 앱으로 오프라인 학습을 할 수 있게 한다.
async function networkFirstShell(event) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), NAV_TIMEOUT_MS);
  try {
    const response = await fetch(event.request, { signal: controller.signal });
    if (response.status >= 500) throw new Error("server " + response.status);
    if (response.ok) event.waitUntil(updateShell(response.clone()).catch(() => undefined));
    return response;
  } catch {
    const cached = (await caches.match("/index.html")) || (await caches.match("/"));
    return cached || new Response("오프라인입니다. 연결되면 다시 열어 주세요.", {
      status: 503,
      headers: { "Content-Type": "text/plain; charset=utf-8" },
    });
  } finally {
    clearTimeout(timer);
  }
}

// 새 index.html 은 그것이 참조하는 해시 JS/CSS 를 모두 받은 뒤에만 캐시한다. 먼저 바꾸면 자산을 받기 전에 끊겼을 때
// 새 HTML + 옛 JS 만 남아 오프라인으로 부팅하지 못한다. 바꾼 뒤에는 새 HTML 이 참조하지 않는 옛 자산을 지운다.
async function updateShell(response) {
  const html = await response.clone().text();
  const assets = Array.from(new Set(html.match(/\/assets\/[^"'\s)]+/g) || []));
  const statics = await caches.open(STATIC_CACHE);
  for (const asset of assets) {
    if (!(await statics.match(asset))) {
      const fresh = await fetch(asset, { cache: "no-store" });
      if (!fresh.ok) return;
      await statics.put(asset, fresh);
    }
  }
  const shell = await caches.open(SHELL_CACHE);
  await shell.put("/index.html", response.clone());
  await shell.put("/", response);
  const keep = new Set(assets);
  for (const req of await statics.keys()) {
    const path = new URL(req.url).pathname;
    if (path.startsWith("/assets/") && !keep.has(path)) await statics.delete(req);
  }
}

async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  // 오디오 요소의 Range 요청도 전체 응답으로 답한다(Chrome 은 200 전체 응답을 받아들인다).
  // 아이콘처럼 설치 때 다른 캐시(shell)에 넣어 둔 것도 찾도록 전체 캐시에서 본다.
  const cached = await caches.match(request.url);
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
      event.respondWith(networkFirstShell(event));
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
          // 열린 창을 새로 고치지 않는다(재생·모닝 락·입력 중인 상태가 사라진다). 앱이 메시지를 받아 화면만 바꾼다.
          client.postMessage({ type: "open-url", url: target });
          return client.focus();
        }
      }
      return self.clients.openWindow(target);
    }),
  );
});
