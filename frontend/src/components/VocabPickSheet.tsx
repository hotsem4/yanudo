import { BookmarkCheck, BookmarkPlus, Check, Plus } from "lucide-react";
import type { Sentence, VocabEntry } from "../api/types";
import { sentenceWords } from "../lib/vocab";
import { studyStore } from "../state/store";
import { BottomSheet } from "./BottomSheet";

interface Props {
  open: boolean;
  onClose: () => void;
  sentence: Sentence | null;
  /** 지금 단어장(오버레이 반영). 아직 못 받았으면 null. */
  vocab: VocabEntry[] | null;
  onOpenVocab: () => void;
}

/**
 * 단어 담기 시트. 위에는 이 문장의 미리 만든 표현(뜻 포함), 아래에는 나머지 단어 칩.
 * 누르면 담기/빼기가 바로 바뀐다(오프라인에서도 이벤트로 쌓였다가 전송된다).
 */
export function VocabPickSheet({ open, onClose, sentence, vocab, onOpenVocab }: Props) {
  return (
    <BottomSheet open={open} onClose={onClose} title="단어 담기">
      {sentence && <Picker sentence={sentence} vocab={vocab ?? []} onOpenVocab={onOpenVocab} />}
    </BottomSheet>
  );
}

function Picker({ sentence, vocab, onOpenVocab }: { sentence: Sentence; vocab: VocabEntry[]; onOpenVocab: () => void }) {
  const terms = sentence.terms ?? [];
  const words = sentenceWords(sentence.en_text, terms);
  const saved = new Map(vocab.map((v) => [v.term_key, v]));
  const making = words.some((w) => saved.get(w.key)?.status === "PENDING");
  const savedHere = [...terms.map((t) => t.term_key), ...words.map((w) => w.key)].filter((k) => saved.has(k)).length;

  const toggle = (key: string, text: string) => {
    if (saved.has(key)) studyStore.record({ type: "VOCAB_REMOVE", term_key: key });
    else studyStore.record({ type: "VOCAB_ADD", text, sentence_id: sentence.id, meaning: null, note: null });
  };

  return (
    <div className="flex flex-col gap-6">
      <p lang="en" translate="no" className="break-words rounded-2xl bg-canvas px-4 py-3 text-[15px] font-semibold leading-snug text-ink">
        {sentence.en_text}
      </p>

      {terms.length > 0 && (
        <section>
          <h3 className="mb-2 text-[15px] font-semibold text-ink">이 문장의 표현</h3>
          <ul className="flex flex-col gap-2">
            {terms.map((t) => {
              const on = saved.has(t.term_key);
              return (
                <li key={t.term_key}>
                  <button
                    type="button"
                    aria-pressed={on}
                    onClick={() => toggle(t.term_key, t.text)}
                    className={`press flex min-h-14 w-full items-center gap-3 rounded-2xl px-4 py-2.5 text-left ${on ? "bg-royal text-surface" : "bg-tint text-ink"}`}
                  >
                    <span className="min-w-0 flex-1">
                      <span lang="en" translate="no" className="block break-words text-[15.5px] font-bold">
                        {t.text}
                      </span>
                      {t.meaning && <span className={`mt-0.5 block break-keep text-[13px] ${on ? "text-surface/85" : "text-muted-strong"}`}>{t.meaning}</span>}
                    </span>
                    {on ? (
                      <BookmarkCheck size={20} strokeWidth={2} aria-hidden className="shrink-0" />
                    ) : (
                      <BookmarkPlus size={20} strokeWidth={2} aria-hidden className="shrink-0 text-royal" />
                    )}
                    <span className="sr-only">{on ? "담김. 누르면 빼요" : "누르면 담아요"}</span>
                  </button>
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {words.length > 0 && (
        <section>
          <h3 className="mb-2 text-[15px] font-semibold text-ink">{terms.length > 0 ? "나머지 단어" : "이 문장의 단어"}</h3>
          <ul className="flex flex-wrap gap-2">
            {words.map((w) => {
              const on = saved.has(w.key);
              return (
                <li key={w.key}>
                  <button
                    type="button"
                    aria-pressed={on}
                    onClick={() => toggle(w.key, w.text)}
                    className={`press flex h-12 items-center gap-1.5 rounded-full pl-3.5 pr-4 text-[15px] font-semibold ${on ? "bg-royal text-surface" : "bg-tint text-ink"}`}
                  >
                    {on ? <Check size={16} strokeWidth={2} aria-hidden /> : <Plus size={16} strokeWidth={2} aria-hidden className="text-royal" />}
                    <span lang="en" translate="no">
                      {w.text}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
          <p className="mt-3 break-keep text-[12.5px] leading-relaxed text-muted" aria-live="polite">
            {making ? "뜻을 만드는 중… 잠시 뒤 단어장에서 뜻과 예문을 볼 수 있어요." : "단어를 담으면 뜻과 예문 3개를 만들어 단어장에 넣어 둬요."}
          </p>
        </section>
      )}

      <button
        type="button"
        onClick={onOpenVocab}
        className="press h-12 rounded-full bg-tint px-5 text-[14px] font-semibold tabular-nums text-royal"
      >
        {savedHere > 0 ? `단어장 보기 (이 문장에서 ${savedHere}개 담음)` : "단어장 보기"}
      </button>
    </div>
  );
}
