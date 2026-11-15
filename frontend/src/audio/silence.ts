// 무음 WAV(8kHz, 8bit, mono). 대기 시간의 시계로 쓴다:
// 같은 <audio> 요소에서 정확히 그 길이만큼 재생하고 `ended` 로 끝낸다. 화면이 꺼져도 setTimeout 처럼 밀리지 않고,
// 일시정지하면 currentTime 이 남은 시간을 보존하며, Media Session(잠금 화면·버즈 버튼)도 계속 살아 있다.

const SAMPLE_RATE = 8000;
const STEP_MS = 50;
const cache = new Map<number, string>();

function wavBlob(ms: number): Blob {
  const samples = Math.max(1, Math.round((ms / 1000) * SAMPLE_RATE));
  const buffer = new ArrayBuffer(44 + samples);
  const view = new DataView(buffer);
  const ascii = (offset: number, text: string) => {
    for (let i = 0; i < text.length; i++) view.setUint8(offset + i, text.charCodeAt(i));
  };
  ascii(0, "RIFF");
  view.setUint32(4, 36 + samples, true);
  ascii(8, "WAVE");
  ascii(12, "fmt ");
  view.setUint32(16, 16, true); // PCM 헤더 크기
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // mono
  view.setUint32(24, SAMPLE_RATE, true);
  view.setUint32(28, SAMPLE_RATE, true); // byte rate
  view.setUint16(32, 1, true); // block align
  view.setUint16(34, 8, true); // bits per sample
  ascii(36, "data");
  view.setUint32(40, samples, true);
  new Uint8Array(buffer, 44).fill(128); // 8bit unsigned 의 0
  return new Blob([buffer], { type: "audio/wav" });
}

/** ms 길이(50ms 단위로 반올림)의 무음 Blob URL. 같은 길이는 재사용한다. */
export function silenceUrl(ms: number): string {
  const key = Math.max(STEP_MS, Math.round(ms / STEP_MS) * STEP_MS);
  let url = cache.get(key);
  if (!url) {
    url = URL.createObjectURL(wavBlob(key));
    cache.set(key, url);
  }
  return url;
}

/** speechSynthesis 대체 재생 동안 loop 로 돌리는 1초 무음. */
export function silenceLoopUrl(): string {
  return silenceUrl(1000);
}
