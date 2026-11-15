// 오디오 프리로더. 세션 MP3 를 Cache API('audio-v1', sw.js 와 같은 캐시)에 받아 두고 Blob URL 로 재생한다.
// Cache API 가 없으면(비보안 컨텍스트) fetch → Blob 만 쓴다. 지하철 터널에서도 준비된 문장은 끊기지 않는다.

const CACHE_NAME = "audio-v1";
const CONCURRENCY = 3;

export interface PreloadStatus {
  ready: number;
  total: number;
}

export class ClipStore {
  private blobs = new Map<string, string>();
  private loading = new Map<string, Promise<string>>();
  private wanted: string[] = [];
  private listeners = new Set<() => void>();
  private status: PreloadStatus = { ready: 0, total: 0 };

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  getStatus(): PreloadStatus {
    return this.status;
  }

  has(url: string): boolean {
    return this.blobs.has(url);
  }

  /** 큐의 모든 클립을 받아 둔다. 실패는 조용히(재생 때 한 번 더 시도). */
  prefetch(urls: readonly string[]): void {
    this.wanted = Array.from(new Set(urls));
    this.updateStatus();
    const queue = this.wanted.filter((u) => !this.blobs.has(u));
    let index = 0;
    const worker = async () => {
      while (index < queue.length) {
        const url = queue[index++];
        try {
          await this.objectUrl(url);
        } catch {
          // 오프라인이거나 아직 생성 전인 클립. speechSynthesis 대체로 재생된다.
        }
      }
    };
    for (let i = 0; i < Math.min(CONCURRENCY, queue.length); i++) void worker();
  }

  /** 재생용 blob: URL. 없으면 캐시 → 네트워크 순으로 시도하고, 실패하면 throw. */
  objectUrl(url: string): Promise<string> {
    const ready = this.blobs.get(url);
    if (ready) return Promise.resolve(ready);
    const inflight = this.loading.get(url);
    if (inflight) return inflight;
    const task = this.load(url)
      .then((blobUrl) => {
        this.blobs.set(url, blobUrl);
        this.updateStatus();
        return blobUrl;
      })
      .finally(() => this.loading.delete(url));
    this.loading.set(url, task);
    return task;
  }

  private async load(url: string): Promise<string> {
    const cache = await openCache();
    let response = cache ? await cache.match(url) : undefined;
    if (!response) {
      const fresh = await fetch(url);
      if (!fresh.ok) throw new Error(`clip ${fresh.status}`);
      if (cache) await cache.put(url, fresh.clone()).catch(() => undefined);
      response = fresh;
    }
    const blob = await response.blob();
    return URL.createObjectURL(blob);
  }

  private updateStatus(): void {
    const ready = this.wanted.filter((u) => this.blobs.has(u)).length;
    if (ready === this.status.ready && this.wanted.length === this.status.total) return;
    this.status = { ready, total: this.wanted.length };
    this.listeners.forEach((l) => l());
  }
}

async function openCache(): Promise<Cache | null> {
  try {
    return "caches" in window ? await caches.open(CACHE_NAME) : null;
  } catch {
    return null;
  }
}
