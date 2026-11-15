import { useCallback, useEffect, useRef, useState } from "react";

export interface SpeechMatch {
  supported: boolean;
  listening: boolean;
  /** 화면에 보일 인식 문장(최선 후보). */
  transcript: string;
  /** 일치율 계산용 후보 문장들. 대안마다 따로 채점해 가장 높은 값을 쓴다(이어 붙이면 점수가 부풀려진다). */
  alternatives: string[];
  error: string | null;
  start: () => void;
  stop: () => void;
  reset: () => void;
}

const ERRORS: Record<string, string> = {
  "not-allowed": "마이크 권한이 꺼져 있어요. 브라우저 설정에서 마이크를 허용해 주세요.",
  "service-not-allowed": "이 기기에서는 음성 인식을 쓸 수 없어요.",
  "no-speech": "목소리가 들리지 않았어요. 다시 눌러 읽어 보세요.",
  "audio-capture": "마이크를 찾지 못했어요.",
  network: "음성 인식 서버에 연결하지 못했어요. 네트워크를 확인해 주세요.",
};

/** 브라우저 SpeechRecognition(en-US, 중간 결과 사용). */
export function useSpeechMatch(): SpeechMatch {
  const Ctor = typeof window !== "undefined" ? (window.SpeechRecognition ?? window.webkitSpeechRecognition) : undefined;
  const recRef = useRef<SpeechRecognition | null>(null);
  const [listening, setListening] = useState(false);
  const [transcript, setTranscript] = useState("");
  const [alternatives, setAlternatives] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  const stop = useCallback(() => {
    recRef.current?.stop();
  }, []);

  const reset = useCallback(() => {
    recRef.current?.abort();
    setTranscript("");
    setAlternatives([]);
    setError(null);
  }, []);

  const start = useCallback(() => {
    if (!Ctor) return;
    recRef.current?.abort();
    const rec = new Ctor();
    rec.lang = "en-US";
    rec.interimResults = true;
    rec.continuous = false;
    rec.maxAlternatives = 3;
    rec.onresult = (event) => {
      // 결과(발화 구간)는 이어 붙이고, 같은 구간의 대안끼리는 섞지 않는다: 대안 j 문장 = 각 구간의 j번째(없으면 첫째) 후보.
      let maxAlt = 1;
      for (let i = 0; i < event.results.length; i++) maxAlt = Math.max(maxAlt, event.results[i].length);
      const alts: string[] = [];
      for (let j = 0; j < maxAlt; j++) {
        const parts: string[] = [];
        for (let i = 0; i < event.results.length; i++) {
          const result = event.results[i];
          parts.push((result[j] ?? result[0]).transcript);
        }
        alts.push(parts.join(" ").trim());
      }
      setTranscript(alts[0] ?? "");
      setAlternatives(alts);
    };
    rec.onerror = (event) => {
      if (event.error !== "aborted") setError(ERRORS[event.error] ?? "음성을 인식하지 못했어요. 다시 시도해 주세요.");
    };
    rec.onend = () => setListening(false);
    recRef.current = rec;
    setError(null);
    setTranscript("");
    setAlternatives([]);
    try {
      rec.start();
      setListening(true);
    } catch {
      setListening(false);
    }
  }, [Ctor]);

  useEffect(() => () => recRef.current?.abort(), []);

  return { supported: !!Ctor, listening, transcript, alternatives, error, start, stop, reset };
}
