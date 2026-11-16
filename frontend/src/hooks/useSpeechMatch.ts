import { useCallback, useEffect, useRef, useState } from "react";

export interface SpeechMatch {
  supported: boolean;
  listening: boolean;
  /** 화면에 보일 인식 문장(최선 후보). */
  transcript: string;
  /** 일치율 계산용 후보 문장들. 대안마다 따로 채점해 가장 높은 값을 쓴다(이어 붙이면 점수가 부풀려진다). */
  alternatives: string[];
  error: string | null;
  /** SpeechRecognition 오류 코드(not-allowed, audio-capture 등). 마이크를 아예 쓸 수 없는지 가릴 때 쓴다. */
  errorCode: string | null;
  start: () => void;
  stop: () => void;
  reset: () => void;
}

const ERRORS: Record<string, string> = {
  "not-allowed": "마이크 권한이 꺼져 있어요. 브라우저 설정에서 마이크를 허용한 뒤 앱을 다시 열어 주세요.",
  "service-not-allowed": "이 기기에서는 음성 인식을 쓸 수 없어요.",
  "no-speech": "목소리가 들리지 않았어요. 다시 눌러 읽어 보세요.",
  "audio-capture": "마이크를 찾지 못했어요.",
  network: "음성 인식 서버에 연결하지 못했어요. 네트워크를 확인해 주세요.",
};

/**
 * 브라우저 SpeechRecognition(en-US, 중간 결과 사용).
 * continuous: 말이 잠깐 끊겨도 인식을 이어 간다(끝내는 때는 부르는 쪽이 stop 으로 정한다). 기본은 끊기면 스스로 끝난다.
 */
export function useSpeechMatch({ continuous = false }: { continuous?: boolean } = {}): SpeechMatch {
  const Ctor = typeof window !== "undefined" ? (window.SpeechRecognition ?? window.webkitSpeechRecognition) : undefined;
  const recRef = useRef<SpeechRecognition | null>(null);
  const [listening, setListening] = useState(false);
  const [transcript, setTranscript] = useState("");
  const [alternatives, setAlternatives] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [errorCode, setErrorCode] = useState<string | null>(null);

  const stop = useCallback(() => {
    recRef.current?.stop();
  }, []);

  const reset = useCallback(() => {
    const rec = recRef.current;
    recRef.current = null;
    rec?.abort();
    setListening(false);
    setTranscript("");
    setAlternatives([]);
    setError(null);
    setErrorCode(null);
  }, []);

  const start = useCallback(() => {
    if (!Ctor) return;
    recRef.current?.abort();
    const rec = new Ctor();
    rec.lang = "en-US";
    rec.interimResults = true;
    rec.continuous = continuous;
    rec.maxAlternatives = 3;
    // 멈춘 직후 다시 시작하면 이전 인식기의 onend·onresult 가 늦게 올 수 있다. 지금 인식기의 것만 받는다.
    const current = () => recRef.current === rec;
    rec.onresult = (event) => {
      if (!current()) return;
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
      if (!current() || event.error === "aborted") return;
      setError(ERRORS[event.error] ?? "음성을 인식하지 못했어요. 다시 시도해 주세요.");
      setErrorCode(event.error);
    };
    rec.onend = () => {
      if (current()) setListening(false);
    };
    recRef.current = rec;
    setError(null);
    setErrorCode(null);
    setTranscript("");
    setAlternatives([]);
    try {
      rec.start();
      setListening(true);
    } catch {
      setListening(false);
    }
  }, [Ctor, continuous]);

  useEffect(() => () => recRef.current?.abort(), []);

  return { supported: !!Ctor, listening, transcript, alternatives, error, errorCode, start, stop, reset };
}
