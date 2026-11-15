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
  const response = await fetch("/index.html", { cache: "no-store" });
  if (!response.ok) throw new Error("index.html " + response.status);
  const page = { status: response.status, statusText: response.statusText, headers: response.headers, body: await response.text() };
  const shell = await caches.open(SHELL_CACHE);
  await shell.addAll(SHELL_URLS.filter((u) => u !== "/" && u !== "/index.html"));
  // 자산을 다 받은 뒤에 HTML 을 바꾼다(설치가 중간에 실패해도 지금 쓰는 앱의 HTML 은 그대로).
  if (!(await updateShell(page))) throw new Error("shell assets incomplete");
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

// 네비게이션 응답을 본문까지 받는다. 리다이렉트는 그대로 넘긴다(본문을 다시 만들면 리다이렉트가 깨진다).
async function fetchPage(request) {
  const response = await fetch(request);
  if (response.type === "opaqueredirect") return { raw: response };
  return { status: response.status, statusText: response.statusText, headers: response.headers, body: await response.text() };
}

// 본문은 이미 풀어 읽었으므로 원래 헤더(content-encoding·content-length)를 붙이지 않는다. 형식만 남긴다.
function pageResponse(page) {
  if (page.raw) return page.raw;
  const headers = { "Content-Type": page.headers.get("content-type") || "text/html; charset=utf-8" };
  return new Response(page.body, { status: page.status, statusText: page.statusText, headers });
}

async function cachedShell() {
  const cached = (await caches.match("/index.html")) || (await caches.match("/"));
  return cached || new Response("오프라인입니다. 연결되면 다시 열어 주세요.", {
    status: 503,
    headers: { "Content-Type": "text/plain; charset=utf-8" },
  });
}

// 서버만 꺼진 경우(tailscale serve 가 502)나 PC 가 꺼져 응답이 없는 경우에도 캐시된 앱으로 오프라인 학습을 할 수 있게 한다.
// 본문까지 NAV_TIMEOUT_MS 안에 오지 않으면 캐시된 셸을 준다. 늦게 온 응답으로도 셸 갱신은 계속한다(event.waitUntil).
function navigate(event) {
  const loaded = fetchPage(event.request);
  event.waitUntil(loaded.then((page) => queueShellUpdate(page)).catch(() => undefined));
  event.respondWith(
    (async () => {
      try {
        const page = await Promise.race([loaded, new Promise((resolve) => setTimeout(() => resolve(null), NAV_TIMEOUT_MS))]);
        if (page && (page.raw || page.status < 500)) return pageResponse(page);
      } catch {
        /* 연결 실패 */
      }
      return cachedShell();
    })(),
  );
}

// 셸 갱신은 한 번에 하나씩(겹치면 앞 갱신의 옛 자산 정리가 뒤 갱신이 받은 새 자산을 지울 수 있다).
let shellUpdates = Promise.resolve();
const SHELL_UPDATE_LIMIT_MS = 30_000;
// 지금 차례인 갱신의 번호. 시간 초과로 밀려난 갱신은 번호가 달라져 HTML 게시·자산 정리를 하지 않는다.
let shellTurn = 0;
function queueShellUpdate(page) {
  // 자산 받기가 멈춰도 뒤의 갱신이 영원히 기다리지 않게 상한을 둔다.
  const limited = () => {
    const turn = ++shellTurn;
    return Promise.race([updateShell(page, turn), new Promise((resolve) => setTimeout(() => resolve(false), SHELL_UPDATE_LIMIT_MS))]);
  };
  shellUpdates = shellUpdates.then(limited).catch(() => false);
  return shellUpdates;
}

// 앱 HTML 이고 참조 자산을 모두 받았을 때만 index.html 을 바꾼다. 먼저 바꾸면 자산을 받기 전에 끊겼을 때 새 HTML + 옛 JS 만 남아
// 오프라인으로 부팅하지 못한다. 바꾼 뒤에는 새 HTML 이 참조하지 않는 옛 자산을 지운다. 바꿨으면 true.
async function updateShell(page, turn = ++shellTurn) {
  const current = () => turn === shellTurn;
  if (page.raw || page.status !== 200) return false;
  if (!(page.headers.get("content-type") || "").includes("text/html")) return false;
  const assets = Array.from(new Set(page.body.match(/\/assets\/[^"'\s)]+/g) || []));
  if (assets.length === 0) return false;
  const statics = await caches.open(STATIC_CACHE);
  for (const asset of assets) {
    if (!(await statics.match(asset))) {
      const fresh = await fetch(asset, { cache: "no-store" });
      if (!fresh.ok) return false;
      await statics.put(asset, fresh);
    }
  }
  const shell = await caches.open(SHELL_CACHE);
  const headers = { "Content-Type": page.headers.get("content-type") || "text/html; charset=utf-8" };
  if (!current()) return false;
  await shell.put("/index.html", new Response(page.body, { headers }));
  await shell.put("/", new Response(page.body, { headers }));
  // sw.js 가 바뀐 배포에서는 설치 중인 워커와 지금 워커가 같은 캐시를 함께 갱신할 수 있다. 상대의 옛 자산 정리가
  // 방금 받은 자산을 지웠으면 다시 받아, 게시한 HTML 이 참조하는 자산이 빠지지 않게 한다.
  for (const asset of assets) {
    if (!(await statics.match(asset))) {
      const again = await fetch(asset, { cache: "no-store" });
      // 다시 받지 못했으면 정리하지 않는다(남은 옛 자산이라도 있어야 다음 접속까지 버틴다).
      if (!again.ok) return true;
      await statics.put(asset, again);
    }
  }
  if (!current()) return true;
  // 정리는 지울 때마다 "지금 게시된 HTML"이 참조하는 자산을 남긴다(다른 워커가 그사이 새 HTML 을 게시했어도 그 자산은 지우지 않는다).
  for (const req of await statics.keys()) {
    const path = new URL(req.url).pathname;
    if (!path.startsWith("/assets/")) continue;
    const published = (await (await shell.match("/index.html"))?.text()) || "";
    if (!published.includes(path) && current()) await statics.delete(req);
  }
  return true;
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
      navigate(event);
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
